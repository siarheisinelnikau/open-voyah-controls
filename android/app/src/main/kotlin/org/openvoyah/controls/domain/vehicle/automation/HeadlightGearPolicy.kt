package org.openvoyah.controls.domain.vehicle.automation

import kotlinx.coroutines.flow.Flow
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.HeadlightStatus

internal enum class HeadlightGearAction(val key: String, val mode: HeadlightMode?) {
    KEEP("keep", null), AUTOMATIC("automatic", HeadlightMode.AUTOMATIC),
    LOW_BEAM("low_beam", HeadlightMode.LOW_BEAM), POSITION("position", HeadlightMode.POSITION),
    OFF("off", HeadlightMode.OFF);

    // The observed native PositionLamp and LampOff plans both require Park and zero speed.
    val allowedInDrive: Boolean get() = this != OFF && this != POSITION
}

internal data class HeadlightGearSettings(
    val enabled: Boolean = false,
    val onDrive: HeadlightGearAction = HeadlightGearAction.AUTOMATIC,
    val onPark: HeadlightGearAction = HeadlightGearAction.KEEP,
) {
    init { require(onDrive.allowedInDrive) }
}

internal data class DrivingModeMemorySettings(val enabled: Boolean = false)

internal data class VehicleAutomationSettings(
    val headlights: HeadlightGearSettings = HeadlightGearSettings(),
    val drivingMode: DrivingModeMemorySettings = DrivingModeMemorySettings(),
)

internal interface VehicleAutomationStore {
    fun observe(): Flow<Result<VehicleAutomationSettings>>
    suspend fun update(expected: VehicleAutomationSettings, replacement: VehicleAutomationSettings)
}

internal data class HeadlightGearDecision(
    val settings: HeadlightGearSettings,
    val identity: VehicleIdentity,
    val sessionId: String,
    val sessionStartedAtElapsedNanos: Long,
    val gear: VehicleSignal<VehicleGear>,
    val action: HeadlightGearAction,
) {
    /** Recheck immediately before a native request; a reserved transition is never replayed. */
    fun isCurrent(current: HeadlightGearSettings, telemetry: VehicleTelemetry, lighting: HeadlightStatus, now: Long): Boolean =
        current == settings && current.enabled && telemetry.identity == identity && telemetry.sessionId == sessionId &&
            telemetry.sessionStartedAtElapsedNanos == sessionStartedAtElapsedNanos &&
            VehicleSignalKind.GEAR !in telemetry.unavailable && telemetry.gear == gear &&
            gear.isFreshFor(telemetry, now) && lighting.currentObservation != null &&
            lighting.currentObservation?.mode != action.mode &&
            (action.allowedInDrive || telemetry.isStationary(now))
}

/** Consumes a fresh D/P edge before dispatch, including blocked and ambiguous attempts. */
internal class HeadlightGearEngine(private val minimumIntervalMillis: Long = 3_000) {
    private var settings: HeadlightGearSettings? = null
    private var session: String? = null
    private var sessionStartedAt: Long? = null
    private var identity: VehicleIdentity? = null
    private var previousGear: VehicleGear? = null
    private var newestGearNanos = 0L
    private var attemptedAtNanos: Long? = null

    init { require(minimumIntervalMillis in 0..60_000) }

    fun reset() {
        settings = null
        session = null
        sessionStartedAt = null
        identity = null
        previousGear = null
        newestGearNanos = 0
        attemptedAtNanos = null
    }

    fun evaluate(
        configuration: HeadlightGearSettings,
        telemetry: VehicleTelemetry,
        lighting: HeadlightStatus,
        nowNanos: Long,
    ): HeadlightGearDecision? {
        val rearm = settings != configuration || session != telemetry.sessionId || identity != telemetry.identity ||
            sessionStartedAt != telemetry.sessionStartedAtElapsedNanos
        if (rearm) {
            settings = configuration
            session = telemetry.sessionId
            sessionStartedAt = telemetry.sessionStartedAtElapsedNanos
            identity = telemetry.identity
            previousGear = null
            newestGearNanos = 0
        }
        val gear = telemetry.gear?.takeIf { VehicleSignalKind.GEAR !in telemetry.unavailable && it.isFreshFor(telemetry, nowNanos) }
        if (gear == null) {
            previousGear = null
            return null
        }
        if (gear.observedAtElapsedNanos <= newestGearNanos) return null
        newestGearNanos = gear.observedAtElapsedNanos
        val before = previousGear
        previousGear = gear.value
        if (rearm || before == null || before == gear.value || !configuration.enabled) return null
        val action = when (gear.value) {
            VehicleGear.DRIVE -> configuration.onDrive
            VehicleGear.PARK -> configuration.onPark
            else -> HeadlightGearAction.KEEP
        }
        if (action == HeadlightGearAction.KEEP) return null
        val last = attemptedAtNanos
        if (last != null && (nowNanos < last || nowNanos - last < minimumIntervalMillis * 1_000_000)) return null
        // Reserve before examining availability. Late speed/light/capability updates cannot replay this edge.
        attemptedAtNanos = nowNanos
        return HeadlightGearDecision(configuration, telemetry.identity, telemetry.sessionId,
            telemetry.sessionStartedAtElapsedNanos, gear, action)
            .takeIf { it.isCurrent(configuration, telemetry, lighting, nowNanos) }
    }
}

internal data class VehicleAutomationCapabilities(
    val headlightActions: Set<HeadlightGearAction> = emptySet(),
    val drivingModes: Set<DrivingMode> = emptySet(),
)

internal enum class VehicleAutomationOutcome { BLOCKED, REQUESTED, CONFIRMED, UNCONFIRMED, FAILED }

/** Native admission belongs to the adapter; semantic values never imply an OEM numeric encoding. */
internal interface VehicleAutomationPort {
    fun capabilities(identity: VehicleIdentity?): VehicleAutomationCapabilities
    suspend fun setHeadlight(decision: HeadlightGearDecision): VehicleAutomationOutcome
    suspend fun restoreDrivingMode(record: DrivingModeRecord, telemetry: VehicleTelemetry): VehicleAutomationOutcome
}

/** No reviewed H97D setter or per-command device evidence is currently packaged. */
internal object UnqualifiedVehicleAutomationPort : VehicleAutomationPort {
    override fun capabilities(identity: VehicleIdentity?) = VehicleAutomationCapabilities()
    override suspend fun setHeadlight(decision: HeadlightGearDecision) = VehicleAutomationOutcome.BLOCKED
    override suspend fun restoreDrivingMode(record: DrivingModeRecord, telemetry: VehicleTelemetry) = VehicleAutomationOutcome.BLOCKED
}
