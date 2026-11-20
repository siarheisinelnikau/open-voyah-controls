package org.openvoyah.controls.domain.vehicle.automation

import kotlinx.coroutines.flow.Flow

/** Exact software identity; a model family alone never qualifies a physical action. */
internal data class VehicleIdentity(
    val product: String,
    val fingerprint: String,
    val sdk: Int,
    val abi: String,
)

internal enum class VehicleGear(val key: String) {
    PARK("park"), REVERSE("reverse"), NEUTRAL("neutral"), DRIVE("drive"),
}

internal enum class VehicleDoor(val key: String) {
    DRIVER("driver"), PASSENGER("passenger"), REAR_LEFT("rear_left"), REAR_RIGHT("rear_right"),
}

internal enum class VehicleDoorState(val key: String) { CLOSED("closed"), OPEN("open") }

internal enum class DrivingMode(val key: String) {
    ECO("eco"), COMFORT("comfort"), SPORT("sport"), OFFROAD("offroad"), INDIVIDUAL("individual"), SNOW("snow"),
}

internal enum class VehicleSignalKind(val key: String) {
    GEAR("gear"), SPEED("speed"), DOORS("doors"), OUTSIDE_TEMPERATURE("outside_temperature"), DRIVING_MODE("driving_mode"),
}

/** Revokes already queued snapshots before the consumer can process the disconnection event. */
internal class VehicleTelemetryWindow {
    @Volatile private var open = true
    val isOpen: Boolean get() = open
    fun close() { open = false }
}

/** Fresh controller callback only. Cached getters, dispatch receipts and saved history are excluded. */
internal data class VehicleSignal<T>(
    val value: T,
    val observedAtElapsedNanos: Long,
    val receivedAtMillis: Long,
    val sourceId: String,
    val sessionId: String,
) {
    init {
        require(observedAtElapsedNanos > 0 && receivedAtMillis > 0)
        require(sourceId.isNotBlank() && sourceId.length <= 96)
        require(sessionId.isNotBlank() && sessionId.length <= 96)
    }

    fun isFreshFor(telemetry: VehicleTelemetry, nowElapsedNanos: Long, maxAgeMillis: Long = 5_000): Boolean {
        if (maxAgeMillis !in 0..60_000 || telemetry.confirmationWindow?.isOpen == false || sessionId != telemetry.sessionId ||
            observedAtElapsedNanos < telemetry.sessionStartedAtElapsedNanos || nowElapsedNanos < observedAtElapsedNanos
        ) return false
        return nowElapsedNanos - observedAtElapsedNanos <= maxAgeMillis * 1_000_000
    }
}

/** A reconnect or wake creates a new session and starts with unknown values. */
internal data class VehicleTelemetry(
    val identity: VehicleIdentity,
    val sessionId: String,
    val sessionStartedAtElapsedNanos: Long,
    val gear: VehicleSignal<VehicleGear>? = null,
    val speedKph: VehicleSignal<Float>? = null,
    val doors: Map<VehicleDoor, VehicleSignal<VehicleDoorState>> = emptyMap(),
    val outsideCelsius: VehicleSignal<Float>? = null,
    val drivingMode: VehicleSignal<DrivingMode>? = null,
    val unavailable: Set<VehicleSignalKind> = VehicleSignalKind.entries.toSet(),
    val confirmationWindow: VehicleTelemetryWindow? = null,
    val gearExpiredByAge: Boolean = false,
) {
    init {
        require(sessionId.isNotBlank() && sessionId.length <= 96)
        require(sessionStartedAtElapsedNanos > 0)
    }

    fun isStationary(nowElapsedNanos: Long): Boolean =
        VehicleSignalKind.GEAR !in unavailable && VehicleSignalKind.SPEED !in unavailable &&
            gear?.let { it.value == VehicleGear.PARK && it.isFreshFor(this, nowElapsedNanos) } == true &&
            speedKph?.let { it.value == 0f && it.isFreshFor(this, nowElapsedNanos) } == true
}

/** The source can revoke a session before its replacement reaches observers. */
internal class VehicleTelemetrySession(telemetry: VehicleTelemetry) {
    private val identity = telemetry.identity
    private val id = telemetry.sessionId
    private val startedAt = telemetry.sessionStartedAtElapsedNanos
    private val window = telemetry.confirmationWindow

    fun matches(current: VehicleTelemetry?): Boolean =
        current != null && current.identity == identity && current.sessionId == id &&
            current.sessionStartedAtElapsedNanos == startedAt && current.confirmationWindow === window &&
            window?.isOpen != false
}

internal fun interface VehicleTelemetryMonitor {
    fun observe(): Flow<VehicleTelemetry>
}
