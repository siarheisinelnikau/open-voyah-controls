package org.openvoyah.controls.platform.vehicle

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.openvoyah.controls.domain.vehicle.ExteriorLamp
import org.openvoyah.controls.domain.vehicle.HeadlightConfirmationWindow
import org.openvoyah.controls.domain.vehicle.HeadlightFeedback
import org.openvoyah.controls.domain.vehicle.HeadlightInput
import org.openvoyah.controls.domain.vehicle.HeadlightInputState

private class InteractivePeriod(val interactive: Boolean, val startedAtNanos: Long)
private class ReadingBoundary(val feedback: HeadlightFeedback)

private data class PublishedReading(
    val state: HeadlightFeedback? = null,
    val lastConfirmation: HeadlightFeedback.Timed? = null,
    val earlierReport: HeadlightFeedback? = null,
) {
    fun confirm(feedback: HeadlightFeedback.Timed): PublishedReading {
        if (feedback.eventElapsedNanos <= (lastConfirmation?.eventElapsedNanos ?: 0L)) return this
        val previous = state as? HeadlightFeedback.Timed
        val previousWindow = previous?.confirmationWindow
        val upstream = feedback.confirmationWindow
        val window = previousWindow?.takeIf {
            previous?.source == feedback.source && it.upstream === upstream && it.isOpen
        } ?: run {
            previousWindow?.close()
            HeadlightConfirmationWindow(upstream)
        }
        val confirmation = feedback.withConfirmationWindow(window)
        return PublishedReading(state = confirmation, lastConfirmation = confirmation)
    }

    fun unavailable(feedback: HeadlightFeedback? = null): PublishedReading {
        // Revocation also applies to confirmations already being written to history.
        lastConfirmation?.confirmationWindow?.close()
        return copy(state = feedback, earlierReport = earlierReport.takeIf { feedback != null })
    }
}

private data class InteractivePublication(
    val boundary: ReadingBoundary = ReadingBoundary(HeadlightFeedback.Unavailable),
    val selector: PublishedReading = PublishedReading(),
    val lamps: Map<ExteriorLamp, PublishedReading> = emptyMap(),
    val inputStates: Map<HeadlightInput, HeadlightInputState> = emptyMap(),
) {
    fun reset(feedback: HeadlightFeedback) = copy(
        boundary = ReadingBoundary(feedback),
        selector = selector.unavailable(),
        lamps = lamps.mapValues { (_, lamp) -> lamp.unavailable() },
        inputStates = if (feedback == HeadlightFeedback.ReadersFinished) inputStates else emptyMap(),
    )

    fun accept(feedback: HeadlightFeedback): InteractivePublication = when (feedback) {
        is HeadlightFeedback.ReaderState -> copy(inputStates = inputStates + (feedback.input to feedback.state))
        is HeadlightFeedback.Confirmed -> copy(selector = selector.confirm(feedback))
        is HeadlightFeedback.ActivityConfirmed -> updateLamp(feedback.lamp) { confirm(feedback) }
        is HeadlightFeedback.EarlierSelectorReport -> copy(selector = selector.copy(earlierReport = feedback))
        is HeadlightFeedback.EarlierLampReport -> updateLamp(feedback.lamp) { copy(earlierReport = feedback) }
        is HeadlightFeedback.ActivityUnavailable -> updateLamp(feedback.lamp) { unavailable(feedback) }
        HeadlightFeedback.SelectorUnavailable, HeadlightFeedback.SelectorAwaitingController ->
            copy(selector = selector.unavailable(feedback))
        HeadlightFeedback.AwaitingController, HeadlightFeedback.Unavailable, HeadlightFeedback.ReadersFinished -> reset(feedback)
    }

    private fun updateLamp(lamp: ExteriorLamp, update: PublishedReading.() -> PublishedReading): InteractivePublication {
        val reading = lamps[lamp] ?: PublishedReading()
        return copy(lamps = lamps + (lamp to reading.update()))
    }
}

internal fun interactiveHeadlightFeedback(
    watchPower: suspend ((interactive: Boolean, elapsedNanos: Long) -> Unit) -> Unit,
    read: suspend ((HeadlightFeedback) -> Boolean) -> Unit,
): Flow<HeadlightFeedback> = flow {
    var previous: InteractivePublication? = null
    readInteractivePeriods(watchPower, read).collect { publication ->
        val last = previous
        val reset = publication.boundary !== last?.boundary
        if (reset) emit(publication.boundary.feedback)

        // Clear each lost signal before a confirmation for any signal can wait on history storage.
        emitAvailability(last?.selector, publication.selector, reset)
        for ((lamp, reading) in publication.lamps) {
            emitAvailability(last?.lamps?.get(lamp), reading, reset)
        }
        val inputs = if (reset) null else last?.inputStates
        if (publication.inputStates !== inputs) {
            for ((input, state) in publication.inputStates) {
                if (inputs?.get(input) != state) emit(HeadlightFeedback.ReaderState(input, state))
            }
        }
        emitObservation(last?.selector, publication.selector, reset)
        for ((lamp, reading) in publication.lamps) {
            emitObservation(last?.lamps?.get(lamp), reading, reset)
        }
        previous = publication
    }
}

private suspend fun FlowCollector<HeadlightFeedback>.emitAvailability(
    previous: PublishedReading?, current: PublishedReading, reset: Boolean,
) {
    val state = current.state ?: return
    if (state !is HeadlightFeedback.Timed && (reset || state != previous?.state)) emit(state)
}

private suspend fun FlowCollector<HeadlightFeedback>.emitObservation(
    previous: PublishedReading?, current: PublishedReading, reset: Boolean,
) {
    val confirmation = current.lastConfirmation
    val earlier = current.earlierReport
    if (confirmation != null && confirmation != previous?.lastConfirmation) {
        emit(confirmation)
    } else if (earlier != null && (reset || earlier != previous?.earlierReport)) {
        emit(earlier)
    }
}

// A conflated publication carries the latest state and confirmation for every signal.
private fun readInteractivePeriods(
    watchPower: suspend ((interactive: Boolean, elapsedNanos: Long) -> Unit) -> Unit,
    read: suspend ((HeadlightFeedback) -> Boolean) -> Unit,
): Flow<InteractivePublication> = channelFlow {
    val periods = Channel<InteractivePeriod>(Channel.CONFLATED)
    val gate = Any()
    var active: InteractivePeriod? = null
    var publication = InteractivePublication()

    fun change(interactive: Boolean, elapsedNanos: Long) {
        val next = InteractivePeriod(interactive, elapsedNanos)
        synchronized(gate) {
            active = next.takeIf { it.interactive }
            publication = publication.reset(if (interactive) HeadlightFeedback.AwaitingController else HeadlightFeedback.Unavailable)
            trySend(publication)
            periods.trySend(next)
        }
    }

    val watcher = launch {
        try {
            watchPower(::change)
        } finally {
            change(false, 0L)
            periods.close()
        }
    }
    try {
        periods.receiveAsFlow().collectLatest { period ->
            if (!period.interactive) return@collectLatest
            read { feedback ->
                // Revoke publication at the Android boundary, even while the previous listener is being released.
                synchronized(gate) {
                    if (active !== period ||
                        (feedback is HeadlightFeedback.Timed && feedback.eventElapsedNanos < period.startedAtNanos)) {
                        return@synchronized false
                    }
                    val next = publication.accept(feedback)
                    if (next == publication) return@synchronized feedback !is HeadlightFeedback.Timed
                    publication = next
                    trySend(publication).isSuccess
                }
            }
            synchronized(gate) {
                if (active === period) {
                    active = null
                    publication = publication.reset(HeadlightFeedback.ReadersFinished)
                    trySend(publication)
                }
            }
        }
    } finally {
        synchronized(gate) {
            active = null
            publication = publication.reset(HeadlightFeedback.Unavailable)
        }
        watcher.cancel()
    }
}.buffer(Channel.CONFLATED)
