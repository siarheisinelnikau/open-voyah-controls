package org.openvoyah.controls.platform.vehicle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import org.openvoyah.controls.domain.vehicle.AutomotiveHeadlightSignal
import org.openvoyah.controls.domain.vehicle.ExteriorLamp
import org.openvoyah.controls.domain.vehicle.HeadlightFeedback
import org.openvoyah.controls.domain.vehicle.HeadlightInput
import org.openvoyah.controls.domain.vehicle.HeadlightInputState
import org.openvoyah.controls.domain.vehicle.HeadlightSource

internal const val HEADLIGHT_SWITCH_PERMISSION = "android.car.permission.CONTROL_CAR_EXTERIOR_LIGHTS"
internal const val HEADLIGHT_ACTIVITY_PERMISSION = "android.car.permission.CAR_EXTERIOR_LIGHTS"

internal fun factoryHeadlightFeedback(context: Context): Flow<HeadlightFeedback> {
    val app = context.applicationContext
    return interactiveHeadlightFeedback({ changed -> watchDeviceInteractivity(app, changed) }) { publish ->
        fun report(input: HeadlightInput, state: HeadlightInputState) {
            publish(HeadlightFeedback.ReaderState(input, state))
        }
        readHeadlightSources(
            factory = flow {
                emitAll(QingganHeadlightReader.observe(app) { report(HeadlightInput.FACTORY_SELECTOR, it) })
            },
            automotive = flow { emitAll(automotiveFeedback(app, ::report)) },
            onSourceFailure = { source, failure ->
                val type = failure.javaClass.simpleName.ifEmpty { "Unknown" }
                Log.w(LOG_TAG, "event=headlight_source source=${source.key} outcome=reader_failed failure=$type elapsed_ms=${SystemClock.elapsedRealtime()}")
                for (input in HeadlightInput.entries) {
                    if (input.source == source) report(input, HeadlightInputState.READ_FAILED)
                }
            },
        ) { source, feedback ->
            if (publish(feedback.withConfirmationSource(source))) logHeadlightObservation(source.key, feedback)
        }
    }
}

internal fun HeadlightFeedback.withConfirmationSource(source: HeadlightSource): HeadlightFeedback = when (this) {
    is HeadlightFeedback.Confirmed -> copy(observation = observation.copy(source = source))
    is HeadlightFeedback.ActivityConfirmed -> copy(observation = observation.copy(source = source))
    else -> this
}

private data class HeadlightReport(val source: HeadlightSource, val feedback: HeadlightFeedback)

internal suspend fun readHeadlightSources(
    factory: Flow<HeadlightFeedback>,
    automotive: Flow<HeadlightFeedback>,
    onSourceFailure: (source: HeadlightSource, failure: Throwable) -> Unit = { _, _ -> },
    publish: (source: HeadlightSource, feedback: HeadlightFeedback) -> Unit,
): Unit {
    val selectors = HeadlightSource.entries.associateWithTo(mutableMapOf()) { source ->
        HeadlightReport(source, HeadlightFeedback.SelectorAwaitingController)
    }
    val sourceTimes = mutableMapOf<HeadlightSource, Long>()
    var latestPublished = 0L
    var selected: HeadlightReport? = null

    val factoryReports = factory.reportsFrom(HeadlightSource.FACTORY, onSourceFailure)
    val automotiveReports = automotive.reportsFrom(HeadlightSource.ANDROID_AUTOMOTIVE, onSourceFailure)
    merge(factoryReports, automotiveReports).collect { report ->
        when (val feedback = report.feedback) {
            is HeadlightFeedback.EarlierSelectorReport, is HeadlightFeedback.EarlierLampReport -> {
                if (report.source == HeadlightSource.ANDROID_AUTOMOTIVE) publish(report.source, feedback)
                return@collect
            }
            is HeadlightFeedback.ReaderState -> {
                if (feedback.input.source == report.source) publish(report.source, feedback)
                return@collect
            }
            is HeadlightFeedback.ActivityConfirmed, is HeadlightFeedback.ActivityUnavailable -> {
                publish(report.source, feedback)
                return@collect
            }
            is HeadlightFeedback.Confirmed -> {
                val time = feedback.eventElapsedNanos
                if (time <= latestPublished || time <= (sourceTimes[report.source] ?: 0L)) return@collect
                sourceTimes[report.source] = time
            }
            else -> Unit
        }
        selectors[report.source] = report

        // Prefer the factory's specific switch modes once it has a confirmation.
        val candidates = HeadlightSource.entries.map(selectors::getValue)
        val next = candidates.firstOrNull {
            val confirmation = it.feedback as? HeadlightFeedback.Confirmed
            confirmation != null && (it == selected || confirmation.eventElapsedNanos > latestPublished)
        } ?: candidates.firstOrNull { it.feedback != HeadlightFeedback.SelectorUnavailable }
            ?.copy(feedback = HeadlightFeedback.SelectorAwaitingController)
            ?: HeadlightReport(report.source, HeadlightFeedback.SelectorUnavailable)
        if (next.feedback == selected?.feedback) return@collect
        selected = next
        (next.feedback as? HeadlightFeedback.Confirmed)?.let { latestPublished = it.eventElapsedNanos }
        publish(next.source, next.feedback)
    }
}

private fun Flow<HeadlightFeedback>.reportsFrom(
    source: HeadlightSource,
    onSourceFailure: (HeadlightSource, Throwable) -> Unit,
): Flow<HeadlightReport> = catch { failure ->
    if (failure is CancellationException || (failure !is Exception && failure !is LinkageError)) throw failure
    onSourceFailure(source, failure)
}.onCompletion { failure ->
    if (failure == null) {
        emit(HeadlightFeedback.SelectorUnavailable)
        if (source == HeadlightSource.ANDROID_AUTOMOTIVE) {
            for (lamp in ExteriorLamp.entries) emit(HeadlightFeedback.ActivityUnavailable(lamp))
        }
    }
}.map { feedback ->
    val scoped = when (feedback) {
        HeadlightFeedback.AwaitingController -> HeadlightFeedback.SelectorAwaitingController
        HeadlightFeedback.Unavailable -> HeadlightFeedback.SelectorUnavailable
        else -> feedback
    }
    HeadlightReport(source, scoped)
}

internal suspend fun watchDeviceInteractivity(app: Context, changed: (Boolean, Long) -> Unit): Unit =
    withContext(Dispatchers.Main.immediate) {
        val power = app.getSystemService(PowerManager::class.java)
        fun report(trigger: String, interactive: Boolean) {
            val elapsed = SystemClock.elapsedRealtimeNanos()
            changed(interactive, elapsed)
            Log.i(LOG_TAG, "event=headlight_power trigger=$trigger interactive=$interactive elapsed_ms=${elapsed / NANOS_PER_MILLISECOND}")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> report("screen_off", false)
                    Intent.ACTION_SCREEN_ON -> report("screen_on", power?.isInteractive == true)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            // Initial state and broadcasts are serialized on the main thread. These broadcasts are not sticky.
            report("initial", power?.isInteractive == true)
            awaitCancellation()
        } finally {
            app.unregisterReceiver(receiver)
        }
    }

private fun logHeadlightObservation(source: String, feedback: HeadlightFeedback) {
    when (feedback) {
        is HeadlightFeedback.Confirmed -> Log.i(LOG_TAG, "event=headlight_feedback source=$source mode=${feedback.observation.mode.key} " +
            "elapsed_ms=${feedback.eventElapsedNanos / NANOS_PER_MILLISECOND} confirmed_at_ms=${feedback.observation.confirmedAtMillis}")
        is HeadlightFeedback.ActivityConfirmed -> Log.i(LOG_TAG,
            "event=headlight_activity source=$source lamp=${feedback.lamp.key} activity=${feedback.observation.activity.key} " +
                "elapsed_ms=${feedback.eventElapsedNanos / NANOS_PER_MILLISECOND} confirmed_at_ms=${feedback.observation.confirmedAtMillis}")
        else -> Unit
    }
}

private const val LOG_TAG = "OpenVoyahControls"
private const val NANOS_PER_MILLISECOND = 1_000_000L

internal enum class AutomotiveHeadlightReaderOutcome(val key: String, val state: HeadlightInputState) {
    UNSUPPORTED_SYSTEM("unsupported_system", HeadlightInputState.PLATFORM_UNAVAILABLE),
    PERMISSION_NOT_GRANTED("permission_not_granted", HeadlightInputState.PERMISSION_DENIED),
    NOT_CONNECTED("not_connected", HeadlightInputState.DISCONNECTED),
    PROPERTY_NOT_LISTED("property_not_listed", HeadlightInputState.PROPERTY_UNAVAILABLE),
    INCOMPATIBLE_CONFIG("incompatible_config", HeadlightInputState.INCOMPATIBLE_PROPERTY),
    REGISTRATION_REJECTED("registration_rejected", HeadlightInputState.SUBSCRIPTION_REJECTED),
    ACCESS_DENIED("access_denied", HeadlightInputState.PERMISSION_DENIED),
    READ_FAILED("read_failed", HeadlightInputState.READ_FAILED),
    SUBSCRIBED("subscribed", HeadlightInputState.SUBSCRIBED),
}

internal fun logAutomotiveHeadlightReader(signal: AutomotiveHeadlightSignal, outcome: AutomotiveHeadlightReaderOutcome) {
    val key = signal.lamp?.key ?: "selector"
    Log.i(LOG_TAG, "event=headlight_reader source=android_automotive signal=$key outcome=${outcome.key} " +
        "elapsed_ms=${SystemClock.elapsedRealtime()}")
}

private fun automotiveFeedback(
    app: Context,
    onReaderState: (HeadlightInput, HeadlightInputState) -> Unit,
): Flow<HeadlightFeedback> {
    fun report(signal: AutomotiveHeadlightSignal, outcome: AutomotiveHeadlightReaderOutcome) {
        val input = when (signal) {
            AutomotiveHeadlightSignal.SELECTOR -> HeadlightInput.ANDROID_SELECTOR
            AutomotiveHeadlightSignal.HEADLIGHTS -> HeadlightInput.ANDROID_HEADLAMPS
            AutomotiveHeadlightSignal.HIGH_BEAM -> HeadlightInput.ANDROID_HIGH_BEAM
        }
        logAutomotiveHeadlightReader(signal, outcome)
        onReaderState(input, outcome.state)
    }
    val unsupported = flowOf(HeadlightFeedback.SelectorUnavailable)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
        for (signal in AutomotiveHeadlightSignal.entries) report(signal, AutomotiveHeadlightReaderOutcome.UNSUPPORTED_SYSTEM)
        return unsupported
    }
    val selectorAllowed = app.checkSelfPermission(HEADLIGHT_SWITCH_PERMISSION) == PackageManager.PERMISSION_GRANTED
    val activityAllowed = app.checkSelfPermission(HEADLIGHT_ACTIVITY_PERMISSION) == PackageManager.PERMISSION_GRANTED
    for (signal in AutomotiveHeadlightSignal.entries) {
        val allowed = if (signal.lamp == null) selectorAllowed else activityAllowed
        if (!allowed) report(signal, AutomotiveHeadlightReaderOutcome.PERMISSION_NOT_GRANTED)
    }
    if (!selectorAllowed && !activityAllowed) return unsupported

    // Keep optional Car API references out of the shared application's entry point.
    return flow { emitAll(AutomotiveHeadlightReader.observe(app, selectorAllowed, activityAllowed, ::report)) }.catch { failure ->
        if (failure !is LinkageError) throw failure
        for (signal in AutomotiveHeadlightSignal.entries) report(signal, AutomotiveHeadlightReaderOutcome.UNSUPPORTED_SYSTEM)
        for (lamp in ExteriorLamp.entries) emit(HeadlightFeedback.ActivityUnavailable(lamp))
        emitAll(unsupported)
    }
}
