package org.openvoyah.controls.domain.vehicle.automation

import kotlinx.coroutines.flow.Flow

/** Only actual controller observations enter this history; requests and UI selections never do. */
internal data class DrivingModeRecord(
    val mode: DrivingMode,
    val identity: VehicleIdentity,
    val sourceId: String,
    val sessionId: String,
    val confirmedAtMillis: Long,
) {
    init {
        require(sourceId.isNotBlank() && sourceId.length <= 96)
        require(sessionId.isNotBlank() && sessionId.length <= 96)
        require(sourceId.none { it.isISOControl() } && sessionId.none { it.isISOControl() })
        require(identity.product.isNotBlank() && identity.product.length <= 96 && identity.product.none { it.isISOControl() })
        require(identity.fingerprint.isNotBlank() && identity.fingerprint.length <= 256 && identity.fingerprint.none { it.isISOControl() })
        require(identity.abi.isNotBlank() && identity.abi.length <= 32 && identity.abi.none { it.isISOControl() })
        require(identity.sdk in 28..100)
        require(confirmedAtMillis > 0)
    }
}

internal interface DrivingModeHistory {
    fun observe(): Flow<Result<DrivingModeRecord?>>
    suspend fun save(record: DrivingModeRecord)
}

internal data class DrivingModeMemoryEvaluation(
    val save: DrivingModeRecord? = null,
    val restore: DrivingModeRecord? = null,
    val actual: DrivingMode? = null,
)

/** A startup reset cannot replace the previous trip's mode before a real trip or a later mode change. */
internal class DrivingModeMemoryEngine {
    private var session: String? = null
    private var sessionStartedAt: Long? = null
    private var identity: VehicleIdentity? = null
    private var enabled: Boolean? = null
    private var baseline: DrivingMode? = null
    private var previousGear: VehicleGear? = null
    private var departed = false
    private var newestMode: VehicleSignal<DrivingMode>? = null
    private var newestGearNanos = 0L

    fun reset() {
        session = null
        sessionStartedAt = null
        identity = null
        enabled = null
        baseline = null
        previousGear = null
        departed = false
        newestMode = null
        newestGearNanos = 0
    }

    fun evaluate(
        settings: DrivingModeMemorySettings,
        remembered: DrivingModeRecord?,
        telemetry: VehicleTelemetry,
        nowNanos: Long,
    ): DrivingModeMemoryEvaluation {
        if (session != telemetry.sessionId || identity != telemetry.identity || enabled != settings.enabled ||
            sessionStartedAt != telemetry.sessionStartedAtElapsedNanos) {
            reset()
            session = telemetry.sessionId
            sessionStartedAt = telemetry.sessionStartedAtElapsedNanos
            identity = telemetry.identity
            enabled = settings.enabled
        }
        val sample = telemetry.drivingMode?.takeIf {
            it.isCurrentIn(telemetry, nowNanos)
        }
        val previousMode = newestMode
        // A new gear sample cannot make regressed or contradictory mode feedback current.
        if (sample != null && previousMode != null &&
            (sample.sourceId != previousMode.sourceId || sample.observedAtElapsedNanos < previousMode.observedAtElapsedNanos ||
                sample.observedAtElapsedNanos == previousMode.observedAtElapsedNanos && sample != previousMode)) {
            return DrivingModeMemoryEvaluation()
        }
        // Mode and gear are reported only on change; their last value in this session stays valid.
        val gear = telemetry.gear?.takeIf { it.isCurrentIn(telemetry, nowNanos) }
        var departingNow = false
        var completedTrip = false
        if (gear == null) {
            previousGear = null
        } else if (gear.observedAtElapsedNanos > newestGearNanos) {
            newestGearNanos = gear.observedAtElapsedNanos
            if (previousGear == VehicleGear.PARK && gear.value == VehicleGear.DRIVE) {
                departingNow = true
                departed = true
            }
            if (previousGear == VehicleGear.DRIVE && gear.value == VehicleGear.PARK) {
                completedTrip = true
                departed = true
            }
            previousGear = gear.value
        }
        val actual = sample?.value
        val target = remembered?.takeIf { it.identity == telemetry.identity && it.mode != actual }
        if (!settings.enabled || sample == null) return DrivingModeMemoryEvaluation(actual = actual)
        val restore = target?.takeIf { telemetry.isStationary(nowNanos) }
        if (sample == previousMode && !departingNow && !completedTrip)
            return DrivingModeMemoryEvaluation(restore = restore, actual = actual)
        newestMode = sample
        val first = baseline == null
        val changed = baseline != null && sample.value != baseline
        if (first) baseline = sample.value
        val sameVehicleHistory = remembered?.takeIf { it.identity == telemetry.identity }
        // A mismatched stored identity remains visible for diagnosis and is never sent to this vehicle.
        val maySave = departed && (sameVehicleHistory == null || target == null || changed || completedTrip)
        if (!maySave) return DrivingModeMemoryEvaluation(restore = restore, actual = actual)
        baseline = sample.value
        val record = DrivingModeRecord(sample.value, telemetry.identity, sample.sourceId, sample.sessionId, sample.receivedAtMillis)
        val newer = remembered == null || record.confirmedAtMillis > remembered.confirmedAtMillis || remembered.identity != record.identity
        val sameSelection = remembered?.let { it.mode == record.mode && it.identity == record.identity &&
            it.sourceId == record.sourceId && it.sessionId == record.sessionId } == true
        val save = record.takeIf { newer && !sameSelection }
        return DrivingModeMemoryEvaluation(save = save, restore = restore.takeIf { save == null }, actual = actual)
    }

    fun acceptCurrent(settings: DrivingModeMemorySettings, telemetry: VehicleTelemetry, nowNanos: Long): DrivingModeRecord? {
        if (!settings.enabled || !telemetry.isStationary(nowNanos)) return null
        val actual = telemetry.drivingMode?.takeIf {
            it.isCurrentIn(telemetry, nowNanos)
        } ?: return null
        if (session == telemetry.sessionId && sessionStartedAt == telemetry.sessionStartedAtElapsedNanos &&
            identity == telemetry.identity && newestMode?.let { previous ->
                actual.sourceId != previous.sourceId || actual.observedAtElapsedNanos < previous.observedAtElapsedNanos ||
                    actual.observedAtElapsedNanos == previous.observedAtElapsedNanos && actual != previous
            } == true) return null
        baseline = actual.value
        return DrivingModeRecord(actual.value, telemetry.identity, actual.sourceId, actual.sessionId, actual.receivedAtMillis)
    }
}
