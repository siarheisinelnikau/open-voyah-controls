package org.openvoyah.controls.platform.vehicle.automation

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
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
        } else channelFlow {
            send(unknownTelemetry())
            try {
                QingganHeadlightReader.observeReadOnly(app, additionalObserver = {
                    VehicleCallbacks(unknownTelemetry(), diagnostic) { trySend(it) }
                }) { state ->
                    diagnostic.update { it.copy(readerState = state) }
                    if (state != HeadlightInputState.SUBSCRIBED) trySend(unknownTelemetry())
                    Log.i(LOG_TAG, "event=vehicle_observer state=${state.name.lowercase()} product=${identity.product}")
                }.collect { /* Lighting continues through the existing monitor and history. */ }
            } finally {
                diagnostic.update { it.copy(connected = false) }
                trySend(unknownTelemetry())
            }
            // An absent/denied source is stable until wake or the owning collector restarts.
            awaitCancellation()
        }
    }.catch { failure ->
        if (failure is CancellationException || (failure !is Exception && failure !is LinkageError)) throw failure
        diagnostic.update { it.copy(connected = false) }
        Log.w(LOG_TAG, "event=vehicle_observer outcome=read_failed failure=${failure.javaClass.simpleName}")
        emit(unknownTelemetry())
        awaitCancellation()
    }.flowOn(Dispatchers.IO)

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
            synchronized(this) {
                if (closed) return
                started = true
                diagnostic.value = NativeVehicleDiagnosticState(readerState = diagnostic.value.readerState,
                    connected = true, sessionId = telemetry.sessionId)
                publish(telemetry)
            }
            // Reuse the factory getGearStatus snapshot once, solely in diagnostics. It is not a transition.
            val (cached, outcome) = readCachedGear(service)
            synchronized(this) {
                if (!closed) diagnostic.update { it.copy(cachedGear = cached, cachedGearOutcome = outcome) }
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
                    Log.i(LOG_TAG, "event=vehicle_signal signal=$signal value=${value ?: "unknown"} " +
                        "outcome=${if (value == null) "unavailable" else "observed"} elapsed_ns=$elapsed session=${current.sessionId}")
                }
                if (current.gear != previous.gear) logSignal("gear", current.gear?.value?.key)
                if (current.drivingMode != previous.drivingMode) logSignal("driving_mode", current.drivingMode?.value?.key)
                if (current.outsideCelsius != previous.outsideCelsius)
                    logSignal("outside_temperature", current.outsideCelsius?.value?.toString())
                for (door in VehicleDoor.entries) {
                    if (current.doors[door] != previous.doors[door]) logSignal("door_${door.key}", current.doors[door]?.value?.key)
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

private const val SERVICE_DESCRIPTOR = "com.qinggan.canbus.ICanBusService"
private const val LISTENER_DESCRIPTOR = "com.qinggan.canbus.ICanBusServiceCallback"
private const val GET_GEAR_STATUS = 6
private const val LOG_TAG = "OpenVoyahControls"
