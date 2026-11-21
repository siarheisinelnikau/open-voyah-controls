package org.openvoyah.controls.domain.vehicle

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

internal enum class HeadlightMode(val key: String) {
    AUTOMATIC("automatic"), LOW_BEAM("low_beam"), POSITION("position"), OFF("off"),
    ON("on"), DAYTIME_RUNNING("daytime_running"),
}

internal data class HeadlightObservation(
    val mode: HeadlightMode,
    val confirmedAtMillis: Long,
    val source: HeadlightSource? = null,
) {
    init { require(confirmedAtMillis > 0) }
}

internal enum class ExteriorLamp(val key: String) {
    HEADLIGHTS("headlights"), HIGH_BEAM("high_beam");

    fun supports(activity: LampActivity): Boolean = this == HEADLIGHTS || activity != LampActivity.DAYTIME_RUNNING
}

internal enum class LampActivity(val key: String) {
    OFF("off"), ON("on"), DAYTIME_RUNNING("daytime_running"),
}

internal data class LampObservation(
    val activity: LampActivity,
    val confirmedAtMillis: Long,
    val source: HeadlightSource? = null,
) {
    init { require(confirmedAtMillis > 0) }
}

internal data class EarlierHeadlightReport<T>(val value: T, val reportedAtMillis: Long) {
    init { require(reportedAtMillis > 0) }
}

/** A local window may close independently of the source window it observes. */
internal class HeadlightConfirmationWindow(val upstream: HeadlightConfirmationWindow? = null) {
    @Volatile
    private var open = true

    val isOpen: Boolean get() = open && upstream?.isOpen != false

    fun close() {
        open = false
    }
}

internal sealed interface HeadlightFeedback {
    data object Unavailable : HeadlightFeedback
    data object AwaitingController : HeadlightFeedback
    data object ReadersFinished : HeadlightFeedback
    data object SelectorAwaitingController : HeadlightFeedback
    data object SelectorUnavailable : HeadlightFeedback
    data class ActivityUnavailable(val lamp: ExteriorLamp) : HeadlightFeedback
    data class ReaderState(val input: HeadlightInput, val state: HeadlightInputState) : HeadlightFeedback
    data class EarlierSelectorReport(val reading: EarlierHeadlightReport<HeadlightMode>) : HeadlightFeedback
    data class EarlierLampReport(val lamp: ExteriorLamp, val reading: EarlierHeadlightReport<LampActivity>) : HeadlightFeedback {
        init { require(lamp.supports(reading.value)) }
    }

    sealed interface Timed : HeadlightFeedback {
        val eventElapsedNanos: Long
        val source: HeadlightSource?
        val confirmationWindow: HeadlightConfirmationWindow?
        val isCurrent: Boolean get() = confirmationWindow?.isOpen != false

        fun withConfirmationWindow(window: HeadlightConfirmationWindow): Timed = when (this) {
            is Confirmed -> copy(confirmationWindow = window)
            is ActivityConfirmed -> copy(confirmationWindow = window)
        }
    }

    /** A fresh controller event. SDK cache values and acknowledgements are outside this contract. */
    data class Confirmed(
        val observation: HeadlightObservation,
        override val eventElapsedNanos: Long,
        override val confirmationWindow: HeadlightConfirmationWindow? = null,
    ) : Timed {
        init { require(eventElapsedNanos > 0) }

        override val source: HeadlightSource? get() = observation.source
    }

    data class ActivityConfirmed(
        val lamp: ExteriorLamp,
        val observation: LampObservation,
        override val eventElapsedNanos: Long,
        override val confirmationWindow: HeadlightConfirmationWindow? = null,
    ) : Timed {
        init { require(eventElapsedNanos > 0 && lamp.supports(observation.activity)) }

        override val source: HeadlightSource? get() = observation.source
    }
}

internal data class LampStatus(
    private val confirmation: HeadlightFeedback.ActivityConfirmed? = null,
    val lastConfirmed: LampObservation? = null,
    val saveFailed: Boolean = false,
    val earlierReport: EarlierHeadlightReport<LampActivity>? = null,
) {
    val current: LampObservation?
        get() = confirmation?.takeIf { it.isCurrent }?.observation

    fun snapshot(): LampStatus {
        val report = confirmation?.takeIf { it.confirmationWindow != null } ?: return this
        return copy(confirmation = report.takeIf { it.isCurrent }?.copy(confirmationWindow = null))
    }
}

internal data class HeadlightStatus(
    val feedback: HeadlightFeedback? = null,
    val lastConfirmed: HeadlightObservation? = null,
    val saveFailed: Boolean = false,
    val lamps: Map<ExteriorLamp, LampStatus> = emptyMap(),
    val earlierReport: EarlierHeadlightReport<HeadlightMode>? = null,
    val inputStates: Map<HeadlightInput, HeadlightInputState> = emptyMap(),
) {
    val currentObservation: HeadlightObservation?
        get() = (feedback as? HeadlightFeedback.Confirmed)?.takeIf { it.isCurrent }?.observation
}

private fun HeadlightStatus.withInactiveTransport(
    status: HeadlightFeedback, keepInputStates: Boolean = false,
): HeadlightStatus =
    copy(feedback = status, earlierReport = null, inputStates = if (keepInputStates) inputStates else emptyMap(),
        lamps = lamps.mapValues { (_, lamp) -> lamp.copy(confirmation = null, earlierReport = null) })

private fun HeadlightStatus.snapshot(): HeadlightStatus {
    val selector = feedback as? HeadlightFeedback.Confirmed
    val stableFeedback = if (selector?.confirmationWindow != null) {
        selector.takeIf { it.isCurrent }?.copy(confirmationWindow = null) ?: HeadlightFeedback.Unavailable
    } else feedback
    return copy(feedback = stableFeedback, lamps = lamps.mapValues { (_, lamp) -> lamp.snapshot() })
}

internal interface HeadlightHistory {
    fun read(): HeadlightObservation?
    fun save(observation: HeadlightObservation): Boolean
    fun readLamps(): Map<ExteriorLamp, LampObservation>
    fun saveLamp(lamp: ExteriorLamp, observation: LampObservation): Boolean
}

internal class HeadlightMonitor(
    private val feedback: Flow<HeadlightFeedback>,
    private val history: HeadlightHistory,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Receives a reader failure that ends the feedback; platform callers log it, the domain stays free of logging. */
    private val reportFailure: (Exception) -> Unit = {},
) {
    fun observe(): Flow<HeadlightStatus> = flow {
        var state = HeadlightStatus(HeadlightFeedback.AwaitingController, history.read(),
            lamps = history.readLamps().mapValues { (_, record) -> LampStatus(lastConfirmed = record) })
        var newestSelector = 0L
        val newestLamp = mutableMapOf<ExteriorLamp, Long>()
        emit(state)
        feedback.catch { failure ->
            // An Error is not an unavailable reader. It must reach the owner instead of reading as a lamp state.
            if (failure !is Exception) throw failure
            reportFailure(failure)
            emit(HeadlightFeedback.Unavailable)
        }.collect { report ->
            state = when (report) {
                is HeadlightFeedback.ReaderState -> state.copy(inputStates = state.inputStates + (report.input to report.state))
                is HeadlightFeedback.Confirmed -> {
                    if (report.eventElapsedNanos <= newestSelector) return@collect
                    newestSelector = report.eventElapsedNanos
                    val observation = report.observation
                    val saved = observation == state.lastConfirmed || history.save(observation)
                    val selector = if (report.isCurrent) report else
                        state.feedback.takeUnless { it is HeadlightFeedback.Confirmed } ?: HeadlightFeedback.Unavailable
                    state.copy(feedback = selector, lastConfirmed = if (saved) observation else state.lastConfirmed,
                        saveFailed = !saved, earlierReport = null)
                }
                is HeadlightFeedback.ActivityConfirmed -> {
                    if (report.eventElapsedNanos <= (newestLamp[report.lamp] ?: 0L)) return@collect
                    newestLamp[report.lamp] = report.eventElapsedNanos
                    val previous = state.lamps[report.lamp]?.lastConfirmed
                    val observation = report.observation
                    val saved = observation == previous || history.saveLamp(report.lamp, observation)
                    val lamp = LampStatus(report.takeIf { it.isCurrent }, if (saved) observation else previous,
                        saveFailed = !saved)
                    state.copy(lamps = state.lamps + (report.lamp to lamp))
                }
                is HeadlightFeedback.EarlierSelectorReport -> {
                    if (newestSelector > 0 || state.lastConfirmed != null) return@collect
                    state.copy(earlierReport = report.reading)
                }
                is HeadlightFeedback.EarlierLampReport -> {
                    val lamp = state.lamps[report.lamp] ?: LampStatus()
                    if (report.lamp in newestLamp || lamp.lastConfirmed != null) return@collect
                    state.copy(lamps = state.lamps + (report.lamp to lamp.copy(earlierReport = report.reading)))
                }
                is HeadlightFeedback.ActivityUnavailable -> {
                    val lamp = state.lamps[report.lamp] ?: return@collect
                    state.copy(lamps = state.lamps + (report.lamp to lamp.copy(confirmation = null)))
                }
                HeadlightFeedback.SelectorAwaitingController -> state.copy(feedback = HeadlightFeedback.AwaitingController)
                HeadlightFeedback.SelectorUnavailable -> state.copy(feedback = HeadlightFeedback.Unavailable)
                HeadlightFeedback.ReadersFinished -> state.withInactiveTransport(HeadlightFeedback.Unavailable, keepInputStates = true)
                HeadlightFeedback.AwaitingController, HeadlightFeedback.Unavailable -> state.withInactiveTransport(report)
            }
            emit(state)
        }
        emit(state.withInactiveTransport(HeadlightFeedback.Unavailable))
    }.flowOn(io)
        // Resolve availability after the handoff from storage and publish an immutable snapshot.
        .map(HeadlightStatus::snapshot)
        .distinctUntilChanged()
}
