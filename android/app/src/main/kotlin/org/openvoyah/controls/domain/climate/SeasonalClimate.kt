package org.openvoyah.controls.domain.climate

import kotlinx.coroutines.flow.Flow
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoorState
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.domain.vehicle.automation.VehicleIdentity
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetrySession

internal enum class ClimateSeason(val key: String) { SUMMER("summer"), WINTER("winter") }
internal enum class ClimateGearCondition(val key: String) { PARK("park"), DRIVE("drive") }

/** Existing front setpoints and optional seat ventilation/heating; zero leaves a seat unchanged. */
internal data class SeasonalClimatePreset(
    val enabled: Boolean = false,
    val outsideThresholdCelsius: Int,
    val driverCelsius: Int = 22,
    val passengerCelsius: Int = 22,
    val fanLevel: Int = 3,
    val gearCondition: ClimateGearCondition = ClimateGearCondition.PARK,
    val requireDoorsClosed: Boolean = true,
    val driverSeatLevel: Int = 0,
    val passengerSeatLevel: Int = 0,
) {
    init {
        require(outsideThresholdCelsius in -30..45)
        require(driverCelsius in 16..30 && passengerCelsius in 16..30)
        require(fanLevel in 1..8)
        require(driverSeatLevel in 0..3 && passengerSeatLevel in 0..3)
    }
}

internal data class SeasonalClimateSettings(
    val summer: SeasonalClimatePreset = SeasonalClimatePreset(outsideThresholdCelsius = 24),
    val winter: SeasonalClimatePreset = SeasonalClimatePreset(outsideThresholdCelsius = 5, driverCelsius = 24, passengerCelsius = 24),
) {
    init { require(winter.outsideThresholdCelsius < summer.outsideThresholdCelsius) }

    fun preset(season: ClimateSeason): SeasonalClimatePreset = if (season == ClimateSeason.SUMMER) summer else winter
    fun withPreset(season: ClimateSeason, preset: SeasonalClimatePreset): SeasonalClimateSettings = when (season) {
        ClimateSeason.SUMMER -> copy(summer = preset)
        ClimateSeason.WINTER -> copy(winter = preset)
    }
}

internal enum class ClimateConditionState {
    DISABLED, WAITING_GEAR, WAITING_SPEED, PARK_REQUIRED, DRIVE_REQUIRED,
    WAITING_TEMPERATURE, TEMPERATURE_NOT_MET, WAITING_DOORS, DOORS_OPEN, READY,
}

internal object SeasonalClimatePolicy {
    /**
     * Gear, outside temperature and doors are reported only when they change, so their last value in the current
     * session counts; a reconnect or wake starts with unknown values. Park holds the car: this source admits no speed,
     * and the launcher agent reads live gear and speed again before every Park-only write.
     */
    fun assess(
        season: ClimateSeason,
        preset: SeasonalClimatePreset,
        telemetry: VehicleTelemetry?,
        nowElapsedNanos: Long,
    ): ClimateConditionState {
        if (!preset.enabled) return ClimateConditionState.DISABLED
        val current = telemetry ?: return ClimateConditionState.WAITING_GEAR
        val gear = current.gear?.takeIf { it.isCurrentIn(current, nowElapsedNanos) }?.value
            ?: return ClimateConditionState.WAITING_GEAR
        val required = if (preset.gearCondition == ClimateGearCondition.PARK) VehicleGear.PARK else VehicleGear.DRIVE
        if (gear != required) return if (required == VehicleGear.PARK) ClimateConditionState.PARK_REQUIRED else ClimateConditionState.DRIVE_REQUIRED
        if (required == VehicleGear.PARK && !current.isStationary(nowElapsedNanos)) return ClimateConditionState.PARK_REQUIRED
        val temperature = current.outsideCelsius?.takeIf {
            it.isCurrentIn(current, nowElapsedNanos) && it.value.isFinite() && it.value in -60f..70f
        }?.value ?: return ClimateConditionState.WAITING_TEMPERATURE
        val reached = if (season == ClimateSeason.SUMMER) temperature >= preset.outsideThresholdCelsius
            else temperature <= preset.outsideThresholdCelsius
        if (!reached) return ClimateConditionState.TEMPERATURE_NOT_MET
        if (preset.requireDoorsClosed) {
            val known = current.doors.filterValues { it.isCurrentIn(current, nowElapsedNanos) }
            if (known.values.any { it.value != VehicleDoorState.CLOSED }) return ClimateConditionState.DOORS_OPEN
            if (known.size < VehicleDoor.entries.size) return ClimateConditionState.WAITING_DOORS
        }
        return ClimateConditionState.READY
    }
}

internal data class ClimateSetpointCommand(val id: String, val value: Int)

internal data class SeasonalClimatePlan(val season: ClimateSeason, val preset: SeasonalClimatePreset) {
    val commands: List<ClimateSetpointCommand> get() = buildList {
        add(ClimateSetpointCommand("SetDriverTemperature", preset.driverCelsius))
        add(ClimateSetpointCommand("SetPassengerTemperature", preset.passengerCelsius))
        add(ClimateSetpointCommand("SetFanSpeed", preset.fanLevel))
        val operation = if (season == ClimateSeason.SUMMER) "Condition" else "Heat"
        for ((seat, level) in listOf("Driver" to preset.driverSeatLevel, "Passenger" to preset.passengerSeatLevel)) {
            val suffix = when (level) { 1 -> "One"; 2 -> "Two"; 3 -> "Tree"; else -> null }
            if (suffix != null) add(ClimateSetpointCommand("Enable${operation}${seat}Level$suffix", level))
        }
    }
}

internal enum class ClimateApplyStatus { BLOCKED, ACCEPTED, VERIFIED, PARTIAL, UNCONFIRMED, BUSY, RECENT }

/** One shared reservation prevents an automatic and a manual confirmation from sending the same group twice. */
internal class ClimateApplyGuard(private val cooldownNanos: Long = 600_000_000_000L) {
    private val reservations = mutableMapOf<SeasonalClimatePlan, Long>()
    private var newestClock = 0L

    init { require(cooldownNanos > 0) }

    @Synchronized
    fun tryReserve(plan: SeasonalClimatePlan, nowElapsedNanos: Long): Boolean {
        require(nowElapsedNanos > 0)
        if (nowElapsedNanos < newestClock) return false
        newestClock = nowElapsedNanos
        reservations.entries.removeAll { nowElapsedNanos - it.value >= cooldownNanos }
        if (plan in reservations || reservations.size >= MAX_RECENT_PLANS) return false
        reservations[plan] = nowElapsedNanos
        return true
    }

    private companion object { const val MAX_RECENT_PLANS = 32 }
}
internal data class ClimateApplyResult(val status: ClimateApplyStatus, val completedCommands: Int = 0) {
    init { require(completedCommands in 0..5) }
}

internal interface SeasonalClimatePort {
    fun approved(plan: SeasonalClimatePlan): Boolean
    suspend fun apply(plan: SeasonalClimatePlan, session: VehicleTelemetrySession): ClimateApplyResult
}

internal interface SeasonalClimateStore {
    fun observe(): Flow<Result<SeasonalClimateSettings>>
    suspend fun update(expected: SeasonalClimateSettings, replacement: SeasonalClimateSettings)
}

internal class SeasonalClimateChanged : IllegalStateException("Seasonal climate settings changed")

/** Reserves a fresh false-to-true edge before dispatch. Reconnect, edits and unknown samples only seed. */
internal class SeasonalClimateEdgeEngine(private val cooldownMillis: Long = 600_000) {
    private data class Session(val id: String, val identity: VehicleIdentity, val startedAt: Long)
    private data class Stamp(val observedAt: Long, val value: String, val source: String)
    init { require(cooldownMillis in 1_000..86_400_000) }
    private var settings: SeasonalClimateSettings? = null
    private var session: Session? = null
    private var previousTelemetry: VehicleTelemetry? = null
    private var stamps: Map<String, Stamp> = emptyMap()
    private var lastEvaluationAt = 0L
    private val lastAttempt = mutableMapOf<ClimateSeason, Long>()

    fun observe(
        configuration: SeasonalClimateSettings,
        telemetry: VehicleTelemetry,
        nowElapsedNanos: Long,
    ): List<SeasonalClimatePlan> {
        if (nowElapsedNanos <= 0) { invalidate(); return emptyList() }
        val currentSession = Session(telemetry.sessionId, telemetry.identity, telemetry.sessionStartedAtElapsedNanos)
        val currentStamps = stamps(telemetry)
        if (settings != configuration || session != currentSession || nowElapsedNanos < lastEvaluationAt) {
            settings = configuration
            session = currentSession
            previousTelemetry = telemetry
            stamps = currentStamps
            lastEvaluationAt = nowElapsedNanos
            return emptyList()
        }
        lastEvaluationAt = nowElapsedNanos
        val regressed = currentStamps.any { (name, stamp) -> stamps[name]?.let { before ->
            stamp.source != before.source || stamp.observedAt < before.observedAt || stamp.observedAt == before.observedAt && stamp != before
        } == true }
        if (regressed) {
            // Retain the newest admitted stamps so stale frames cannot seed a fabricated next edge.
            stamps = stamps + currentStamps.filter { (name, stamp) ->
                stamps[name]?.let { stamp.observedAt > it.observedAt || stamp == it } != false
            }
            previousTelemetry = null
            return emptyList()
        }
        val previous = previousTelemetry
        previousTelemetry = telemetry
        stamps = stamps + currentStamps
        return ClimateSeason.entries.mapNotNull { season ->
            // A known false observation must still be fresh when the true observation arrives.
            val before = previous?.let { match(season, configuration, it, nowElapsedNanos) }
            val current = match(season, configuration, telemetry, nowElapsedNanos)
            val attempted = lastAttempt[season]
            val cooledDown = attempted == null || (nowElapsedNanos >= attempted &&
                nowElapsedNanos - attempted >= cooldownMillis * 1_000_000)
            if (before == false && current == true && cooledDown) {
                lastAttempt[season] = nowElapsedNanos
                SeasonalClimatePlan(season, configuration.preset(season))
            } else null
        }
    }

    /** Configuration/source failures only seed recovery; preserve reserved-at cooldowns. */
    fun invalidate() {
        settings = null
        session = null
        previousTelemetry = null
        stamps = emptyMap()
        lastEvaluationAt = 0L
    }

    private fun stamps(telemetry: VehicleTelemetry): Map<String, Stamp> = buildMap {
        telemetry.gear?.let { put("gear", Stamp(it.observedAtElapsedNanos, it.value.key, it.sourceId)) }
        telemetry.speedKph?.let { put("speed", Stamp(it.observedAtElapsedNanos, it.value.toString(), it.sourceId)) }
        telemetry.outsideCelsius?.let { put("outside", Stamp(it.observedAtElapsedNanos, it.value.toString(), it.sourceId)) }
        telemetry.doors.forEach { (door, signal) -> put("door_${door.key}", Stamp(signal.observedAtElapsedNanos, signal.value.key, signal.sourceId)) }
    }

    private fun match(season: ClimateSeason, settings: SeasonalClimateSettings, telemetry: VehicleTelemetry, now: Long): Boolean? =
        when (SeasonalClimatePolicy.assess(season, settings.preset(season), telemetry, now)) {
            ClimateConditionState.READY -> true
            ClimateConditionState.DISABLED, ClimateConditionState.TEMPERATURE_NOT_MET, ClimateConditionState.DOORS_OPEN,
            ClimateConditionState.PARK_REQUIRED, ClimateConditionState.DRIVE_REQUIRED -> false
            else -> null
        }
}
