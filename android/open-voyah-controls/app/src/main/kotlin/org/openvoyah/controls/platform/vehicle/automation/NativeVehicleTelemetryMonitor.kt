package org.openvoyah.controls.platform.vehicle.automation

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.vehicle.HeadlightInputState
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleIdentity
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetryMonitor
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetryWindow
import org.openvoyah.controls.platform.vehicle.QingganHeadlightReader
import org.openvoyah.controls.platform.vehicle.QingganReadOnlyCallbacks
import org.openvoyah.controls.platform.vehicle.watchDeviceInteractivity

internal data class CachedNativeGear(
    val ordinal: Int,
    val value: Int,
    val queriedAtMillis: Long,
)

internal data class NativeCallbackSample(
    val values: List<Int>,
    val receivedAtMillis: Long,
    val receivedAtElapsedNanos: Long,
    val count: Long,
)

internal data class NativeVehicleDiagnosticState(
    val readerState: HeadlightInputState? = null,
    val connected: Boolean = false,
    val sessionId: String? = null,
    val cachedGear: CachedNativeGear? = null,
    val cachedGearOutcome: String = "not_read",
    val callbacks: Map<String, NativeCallbackSample> = emptyMap(),
)

/** No timers or vehicle writes. Collection owns the binding and screen-off revokes its session. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class NativeVehicleTelemetryMonitor(context: Context) : VehicleTelemetryMonitor {
    private val app = context.applicationContext
    val identity = VehicleIdentity(Build.PRODUCT, Build.FINGERPRINT, Build.VERSION.SDK_INT,
        Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
    private val diagnostic = MutableStateFlow(NativeVehicleDiagnosticState())
    val diagnostics = diagnostic.asStateFlow()

    fun unknownTelemetry(): VehicleTelemetry = VehicleTelemetry(identity, UUID.randomUUID().toString(),
        SystemClock.elapsedRealtimeNanos().coerceAtLeast(1))

    override fun observe(): Flow<VehicleTelemetry> = callbackFlow<Boolean> {
        watchDeviceInteractivity(app) { interactive, _ -> trySend(interactive) }
    }.distinctUntilChanged().flatMapLatest { interactive ->
        if (!interactive) {
            diagnostic.update { it.copy(connected = false) }
            flowOf(unknownTelemetry())
        } else channelFlow<VehicleTelemetry> {
            send(unknownTelemetry())
            try {
                QingganHeadlightReader.observeReadOnly(app, additionalObserver = {
                    VehicleCallbacks(unknownTelemetry(), diagnostic) { trySend(it) }
                }) { state ->
                    diagnostic.update { it.copy(readerState = state) }
                    if (state != HeadlightInputState.SUBSCRIBED) trySend(unknownTelemetry())
                    ControlsLog.info("vehicle_observer", "state", state, "product", identity.product)
                }.collect { /* Lighting continues through the existing monitor and history. */ }
            } finally {
                diagnostic.update { it.copy(connected = false) }
                trySend(unknownTelemetry())
            }
            // An absent/denied source is stable until wake or the owning collector restarts.
            awaitCancellation()
        }.unknownAfterFailure()
    }.unknownAfterFailure().flowOn(Dispatchers.IO)

    /**
     * Applied to each interactive period, a reader failure ends only that period: the interactivity watcher keeps
     * running and the next wake subscribes again. The outer use still covers a failure of the watcher itself.
     */
    private fun Flow<VehicleTelemetry>.unknownAfterFailure(): Flow<VehicleTelemetry> = catch { failure ->
        if (failure is CancellationException || (failure !is Exception && failure !is LinkageError)) throw failure
        diagnostic.update { it.copy(connected = false) }
        ControlsLog.warn("vehicle_observer", "outcome", "read_failed", "failure", failure)
        emit(unknownTelemetry())
        awaitCancellation()
    }

    private class VehicleCallbacks(
        initial: VehicleTelemetry,
        private val diagnostic: MutableStateFlow<NativeVehicleDiagnosticState>,
        private val publish: (VehicleTelemetry) -> Unit,
    ) : QingganReadOnlyCallbacks {
        private val reducer = PinnedVehicleTelemetryReducer(initial.copy(confirmationWindow = VehicleTelemetryWindow()))
        private val telemetry get() = reducer.telemetry
        private var started = false
        private var closed = false

        override fun onConnected(service: IBinder) {
            val session = synchronized(this) {
                if (closed) return
                started = true
                diagnostic.value = NativeVehicleDiagnosticState(readerState = diagnostic.value.readerState,
                    connected = true, sessionId = telemetry.sessionId)
                publish(telemetry)
                telemetry.sessionId
            }
            // The factory getGearStatus snapshot is read once. It is not a transition and never a current gear: it only
            // gives the session's first gear callback (Park to Drive after a wake) the gear it changed from.
            val (cached, outcome) = readCachedGear(service)
            synchronized(this) {
                if (closed) return
                diagnostic.update { it.copy(cachedGear = cached, cachedGearOutcome = outcome) }
                val previous = telemetry
                val current = cached?.let { reducer.seedGearAtConnect(session, it.ordinal, it.value) } ?: previous
                if (current != previous) publish(current)
                val result = when {
                    current != previous -> "baseline"
                    cached == null -> outcome
                    PinnedVehicleCallbacks.gear(cached.ordinal, cached.value) == null -> "unavailable"
                    else -> "superseded"
                }
                ControlsLog.info("vehicle_signal", "signal", "gear", "value", current.gearAtConnect?.key ?: "unknown",
                    "source", "service_cache", "outcome", result, "session", session)
            }
            seedDrivingMode(service)
        }

        /**
         * The VCU reports the driving mode only when it changes, so a new session would wait for the driver to switch
         * modes before Save or Restore could work. The service keeps the latest VCU frame in its cache
         * (getVehicleState(DRIVING_MODE_SET_FB)); that value is the actual mode, not the requested one, and seeds the
         * session once. A change callback that arrived first wins.
         */
        private fun seedDrivingMode(service: IBinder) {
            val (value, outcome) = readCachedDrivingMode(service)
            synchronized(this) {
                if (closed) return
                // Stamped under the lock, so a callback handled meanwhile cannot look newer than the seed.
                val elapsed = SystemClock.elapsedRealtimeNanos()
                val received = System.currentTimeMillis()
                if (value == null || telemetry.drivingMode != null) {
                    ControlsLog.info("vehicle_signal", "signal", "driving_mode", "source", "service_cache",
                        "outcome", if (value == null) outcome else "superseded", "session", telemetry.sessionId)
                    return
                }
                val previous = telemetry
                val current = reducer.accept(PinnedVehicleCallbacks.VEHICLE_STATE,
                    PinnedVehicleCallbacks.drivingModeSnapshot(value), elapsed, received)
                if (current == previous) return
                publish(current)
                ControlsLog.info("vehicle_signal", "signal", "driving_mode", "value", current.drivingMode?.value?.key ?: "unknown",
                    "source", "service_cache", "outcome", if (current.drivingMode == null) "unavailable" else "observed",
                    "elapsed_ns", elapsed, "session", current.sessionId)
            }
        }

        @Synchronized
        override fun onCallback(code: Int, data: Parcel): Boolean {
            if (closed || !started) return true
            val integers = PinnedVehicleCallbacks.expectedIntegers(code) ?: return true
            data.enforceInterface(LISTENER_DESCRIPTOR)
            if (data.dataAvail() != integers * Int.SIZE_BYTES) return true
            val values = List(integers) { data.readInt() }
            val key = PinnedVehicleCallbacks.diagnosticKey(code, values) ?: return true
            val elapsed = SystemClock.elapsedRealtimeNanos()
            val received = System.currentTimeMillis()
            if (elapsed <= 0 || received <= 0) return true
            diagnostic.update { previous ->
                val count = ((previous.callbacks[key]?.count ?: 0) + 1).coerceAtMost(1_000_000_000)
                previous.copy(callbacks = previous.callbacks + (key to NativeCallbackSample(values, received, elapsed, count)))
            }
            val previous = telemetry
            val current = reducer.accept(code, values, elapsed, received)
            if (current != previous) {
                publish(current)
                fun logSignal(signal: String, value: String?) {
                    ControlsLog.info("vehicle_signal", "signal", signal, "value", value ?: "unknown",
                        "outcome", if (value == null) "unavailable" else "observed", "elapsed_ns", elapsed,
                        "session", current.sessionId)
                }
                // Every callback stamps a new sample time; only a changed value is worth a journal entry.
                if (current.gear?.value != previous.gear?.value) logSignal("gear", current.gear?.value?.key)
                if (current.drivingMode?.value != previous.drivingMode?.value) logSignal("driving_mode", current.drivingMode?.value?.key)
                if (current.outsideCelsius?.value != previous.outsideCelsius?.value)
                    logSignal("outside_temperature", current.outsideCelsius?.value?.toString())
                for (door in VehicleDoor.entries) {
                    if (current.doors[door]?.value != previous.doors[door]?.value) {
                        logSignal("door_${door.key}", current.doors[door]?.value?.key)
                    }
                }
            }
            return true
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            telemetry.confirmationWindow?.close()
            diagnostic.update { it.copy(connected = false) }
            publish(VehicleTelemetry(telemetry.identity, UUID.randomUUID().toString(),
                SystemClock.elapsedRealtimeNanos().coerceAtLeast(1)))
        }
    }
}

private fun readCachedGear(service: IBinder): Pair<CachedNativeGear?, String> {
    val request = Parcel.obtain()
    val reply = Parcel.obtain()
    return try {
        request.writeInterfaceToken(SERVICE_DESCRIPTOR)
        if (!service.transact(GET_GEAR_STATUS, request, reply, 0)) return null to "request_rejected"
        reply.readException()
        if (reply.dataAvail() != 3 * Int.SIZE_BYTES || reply.readInt() != 1) return null to "unavailable"
        CachedNativeGear(reply.readInt(), reply.readInt(), System.currentTimeMillis()) to "cached_snapshot_only"
    } catch (_: Exception) {
        null to "read_failed"
    } finally {
        request.recycle()
        reply.recycle()
    }
}

/** ICanBusService.getVehicleState(VehicleState): -1 when the service has no VCU frame yet. Read-only. */
internal fun readCachedDrivingMode(service: IBinder): Pair<Int?, String> {
    val request = Parcel.obtain()
    val reply = Parcel.obtain()
    return try {
        request.writeInterfaceToken(SERVICE_DESCRIPTOR)
        request.writeInt(1) // Parcelable present
        request.writeInt(PinnedVehicleCallbacks.DRIVING_MODE_FEEDBACK_ORDINAL)
        request.writeInt(PinnedVehicleCallbacks.DRIVING_MODE_FEEDBACK_ID)
        if (!service.transact(GET_VEHICLE_STATE, request, reply, 0)) return null to "request_rejected"
        reply.readException()
        if (reply.dataAvail() != Int.SIZE_BYTES) return null to "unavailable"
        reply.readInt().takeIf { it in 1..6 }?.let { it to "cached_snapshot" } ?: (null to "unavailable")
    } catch (_: Exception) {
        null to "read_failed"
    } finally {
        request.recycle()
        reply.recycle()
    }
}

private const val SERVICE_DESCRIPTOR = "com.qinggan.canbus.ICanBusService"
private const val GET_VEHICLE_STATE = 57
private const val LISTENER_DESCRIPTOR = "com.qinggan.canbus.ICanBusServiceCallback"
private const val GET_GEAR_STATUS = 6
