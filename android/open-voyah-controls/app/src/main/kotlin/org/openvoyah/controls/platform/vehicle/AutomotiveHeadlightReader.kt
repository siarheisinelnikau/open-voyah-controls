package org.openvoyah.controls.platform.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.ArraySet
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.vehicle.AutomotiveHeadlightSignal
import org.openvoyah.controls.domain.vehicle.AutomotiveHeadlightSamples
import org.openvoyah.controls.domain.vehicle.HeadlightConfirmationWindow
import org.openvoyah.controls.domain.vehicle.HeadlightFeedback

@RequiresApi(Build.VERSION_CODES.Q)
@OptIn(ExperimentalCoroutinesApi::class)
internal object AutomotiveHeadlightReader {
    private const val GLOBAL_AREA = 0

    fun observe(
        app: Context,
        selectorAllowed: Boolean,
        activityAllowed: Boolean,
        report: (AutomotiveHeadlightSignal, AutomotiveHeadlightReaderOutcome) -> Unit = ::logAutomotiveHeadlightReader,
    ): Flow<HeadlightFeedback> = connections(app).catch { failure ->
        if (!failure.isReadFailure()) throw failure
        logReaderFailure(failure, "all", "connect")
        emit(null)
    }.flatMapLatest { connection ->
        val manager = try {
            connection?.takeIf { it.window.isOpen }?.car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        } catch (failure: Throwable) {
            if (!failure.isReadFailure()) throw failure
            logReaderFailure(failure, "all", "property_service")
            null
        }
        automotiveHeadlightChannels(selectorAllowed, activityAllowed) { signal ->
            flow {
                // Either link transition can replace a pending disconnect. Clear only this property's live value.
                emit(signal.unavailable)
                if (manager == null || connection == null || !connection.window.isOpen) {
                    report(signal, AutomotiveHeadlightReaderOutcome.NOT_CONNECTED)
                } else {
                    emitAll(observeProperty(manager, signal, connection.window, report))
                }
            }.catch { failure ->
                if (!failure.isReadFailure()) throw failure
                val outcome = if (failure is SecurityException) AutomotiveHeadlightReaderOutcome.ACCESS_DENIED
                    else AutomotiveHeadlightReaderOutcome.READ_FAILED
                logReaderFailure(failure, signal.lamp?.key ?: "selector", "subscribe")
                report(signal, outcome)
                emit(signal.unavailable)
            }
        }
    }

    private fun connections(app: Context): Flow<AutomotiveHeadlightConnection<Car>?> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Android11HeadlightConnection.observe(app)
        else android10Connections(app)

    @Suppress("DEPRECATION")
    internal fun android10Connections(app: Context): Flow<AutomotiveHeadlightConnection<Car>?> =
        automotiveHeadlightConnections { changed ->
            val listener = object : ServiceConnection {
                @Volatile var car: Car? = null

                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    car?.let { changed(it, true) }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    car?.let { changed(it, false) }
                }
            }
            // The public Android 10 listener overload creates a disconnected Car.
            val car = Car.createCar(app, listener) ?: return@automotiveHeadlightConnections null
            listener.car = car
            val disconnect: () -> Unit = {
                listener.car = null
                runCatching { car.disconnect() }
            }
            try {
                car.connect()
            } catch (failure: Throwable) {
                disconnect()
                throw failure
            }
            disconnect
        }

    @Suppress("DEPRECATION")
    private fun observeProperty(
        manager: CarPropertyManager,
        signal: AutomotiveHeadlightSignal,
        connectionWindow: HeadlightConfirmationWindow,
        report: (AutomotiveHeadlightSignal, AutomotiveHeadlightReaderOutcome) -> Unit,
    ): Flow<HeadlightFeedback> = flow {
        val property = when (signal) {
            AutomotiveHeadlightSignal.SELECTOR -> VehiclePropertyIds.HEADLIGHTS_SWITCH
            AutomotiveHeadlightSignal.HEADLIGHTS -> VehiclePropertyIds.HEADLIGHTS_STATE
            AutomotiveHeadlightSignal.HIGH_BEAM -> VehiclePropertyIds.HIGH_BEAM_LIGHTS_STATE
        }
        // The scoped list method is present on Android 10 and does not query unrelated properties.
        val config = manager.getPropertyList(ArraySet<Int>(1).apply { add(property) }).firstOrNull { it.propertyId == property }
        val unsupported = when {
            config == null -> AutomotiveHeadlightReaderOutcome.PROPERTY_NOT_LISTED
            !config.isReadable(signal) -> AutomotiveHeadlightReaderOutcome.INCOMPATIBLE_CONFIG
            else -> null
        }
        if (unsupported != null) {
            currentCoroutineContext().ensureActive()
            report(signal, unsupported)
            emit(signal.unavailable)
            return@flow
        }

        if (signal == AutomotiveHeadlightSignal.SELECTOR) emit(HeadlightFeedback.SelectorAwaitingController)
        if (!connectionWindow.isOpen) return@flow
        val subscription = AutomotiveHeadlightSubscription(SystemClock.elapsedRealtimeNanos(), signal, connectionWindow)
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (value.propertyId != property || value.areaId != GLOBAL_AREA) return
                val available = value.status == CarPropertyValue.STATUS_AVAILABLE
                subscription.accept(if (available) value.value else null, value.timestamp,
                    SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis(), available)
            }

            // This callback reports setProperty errors. Read status arrives through onChangeEvent.
            override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
        }
        try {
            val registered = manager.registerCallback(callback, property, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            currentCoroutineContext().ensureActive()
            if (!connectionWindow.isOpen) return@flow
            if (registered) {
                report(signal, AutomotiveHeadlightReaderOutcome.SUBSCRIBED)
                emitAll(subscription.feedback)
            } else {
                report(signal, AutomotiveHeadlightReaderOutcome.REGISTRATION_REJECTED)
                emit(signal.unavailable)
            }
        } finally {
            subscription.close()
            // Some API implementations retain the local listener entry even when the registration fails.
            runCatching { manager.unregisterCallback(callback, property) }
        }
    }

    private fun CarPropertyConfig<*>.isReadable(signal: AutomotiveHeadlightSignal): Boolean {
        if (!isGlobalProperty || propertyType != Int::class.javaObjectType) return false
        return if (signal == AutomotiveHeadlightSignal.SELECTOR) {
            access == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ || access == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE
        } else {
            access == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ && changeMode == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
private object Android11HeadlightConnection {
    fun observe(app: Context): Flow<AutomotiveHeadlightConnection<Car>?> = automotiveHeadlightConnections { changed ->
        val car = Car.createCar(app, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
            changed(connected, ready)
        }
        car?.let { { runCatching { it.disconnect() } } }
    }
}

internal class AutomotiveHeadlightConnection<T : Any>(val car: T) {
    val window = HeadlightConfirmationWindow()
}

internal fun <T : Any> automotiveHeadlightConnections(
    connect: ((T, Boolean) -> Unit) -> (() -> Unit)?,
): Flow<AutomotiveHeadlightConnection<T>?> = callbackFlow {
    val gate = Any()
    var activeWindow: HeadlightConfirmationWindow? = null
    var stopped = false
    var disconnect: (() -> Unit)? = null
    trySend(null)
    try {
        disconnect = connect { car, ready ->
            synchronized(gate) {
                if (stopped) return@synchronized
                // Both transition events may be conflated before the property readers resume.
                activeWindow?.close()
                val connection = if (ready) AutomotiveHeadlightConnection(car) else null
                activeWindow = connection?.window
                trySend(connection)
            }
        }
        if (disconnect == null) close() else awaitClose()
    } finally {
        synchronized(gate) {
            stopped = true
            activeWindow?.close()
        }
        disconnect?.invoke()
    }
}.buffer(Channel.CONFLATED)

internal class AutomotiveHeadlightSubscription(
    subscribedAtNanos: Long,
    private val signal: AutomotiveHeadlightSignal,
    private val connectionWindow: HeadlightConfirmationWindow? = null,
) {
    private val samples = AutomotiveHeadlightSamples(subscribedAtNanos, signal)
    private val pending = Channel<HeadlightFeedback>(capacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var lastQueued: HeadlightFeedback? = null
    private var window: HeadlightConfirmationWindow? = null
    private var closed = false

    val feedback: Flow<HeadlightFeedback> = pending.receiveAsFlow()

    @Synchronized
    fun accept(value: Any?, timestampNanos: Long, receivedAtNanos: Long, receivedAtMillis: Long, available: Boolean = true) {
        if (closed || connectionWindow?.isOpen == false) return
        val report = samples.accept(value, timestampNanos, receivedAtNanos, receivedAtMillis, available) ?: return
        if (report == lastQueued) return
        lastQueued = report
        // Revoke before sending because recovery samples can displace the availability event.
        if (report == signal.unavailable) {
            window?.close()
            window = null
        }
        val event = if (report is HeadlightFeedback.Timed) {
            val active = window ?: HeadlightConfirmationWindow(connectionWindow).also { window = it }
            report.withConfirmationWindow(active)
        } else {
            report
        }
        pending.trySend(event)
    }

    @Synchronized
    fun close() {
        closed = true
        window?.close()
        pending.close()
    }
}

internal fun automotiveHeadlightChannels(
    selectorAllowed: Boolean,
    activityAllowed: Boolean,
    read: (AutomotiveHeadlightSignal) -> Flow<HeadlightFeedback>,
): Flow<HeadlightFeedback> {
    fun channel(signal: AutomotiveHeadlightSignal, allowed: Boolean): Flow<HeadlightFeedback> {
        if (!allowed) return flowOf(signal.unavailable)
        return flow { emitAll(read(signal)) }.catch { failure ->
            if (!failure.isReadFailure()) throw failure
            logReaderFailure(failure, signal.lamp?.key ?: "selector", "channel")
            emit(signal.unavailable)
        }
    }
    return merge(
        channel(AutomotiveHeadlightSignal.SELECTOR, selectorAllowed),
        channel(AutomotiveHeadlightSignal.HEADLIGHTS, activityAllowed),
        channel(AutomotiveHeadlightSignal.HIGH_BEAM, activityAllowed),
    )
}

private fun Throwable.isReadFailure(): Boolean =
    this !is CancellationException && (this is RuntimeException || this is LinkageError)

/** The reader state names the outcome; only this entry keeps the exception type behind it. */
private fun logReaderFailure(failure: Throwable, signal: String, stage: String) {
    ControlsLog.warn("headlight_reader", failure, "source", "android_automotive", "signal", signal, "stage", stage)
}
