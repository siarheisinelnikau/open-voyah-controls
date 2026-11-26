package org.openvoyah.controls.domain.rules

import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.routines.RoutineLibrary
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoorState
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.domain.vehicle.automation.VehicleIdentity
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry

internal enum class DoorRequirement(val key: String) { ALL_CLOSED("all_closed"), ANY_OPEN("any_open") }

/** The selected routine is retained as a receipt: changed actions require saving the rule again. */
internal data class ConditionalRule(
    val id: String,
    val name: String,
    val routine: NamedRoutine,
    val gear: VehicleGear? = null,
    val doors: DoorRequirement? = null,
    val enabled: Boolean = false,
    val cooldownSeconds: Int = 30,
    val conditions: RuleConditions = RuleConditions.EMPTY,
) {
    init {
        require(NamedRoutine.validId(id) && NamedRoutine.normalizeName(name) == name)
        require(gear != null || doors != null || !conditions.isEmpty)
        require(cooldownSeconds in MIN_COOLDOWN_SECONDS..MAX_COOLDOWN_SECONDS)
    }

    fun currentRoutine(library: RoutineLibrary): NamedRoutine? = library.entries.firstOrNull { it.id == routine.id }
        ?.takeIf { it == routine }

    companion object {
        const val MIN_COOLDOWN_SECONDS = 10
        const val MAX_COOLDOWN_SECONDS = 600
    }
}

internal data class ConditionalRuleLibrary(val entries: List<ConditionalRule> = emptyList()) {
    init {
        require(entries.size <= MAX_RULES && entries.distinctBy { it.id }.size == entries.size)
        require(entries.indices.none { i -> entries.take(i).any { it.name.equals(entries[i].name, ignoreCase = true) } })
    }

    fun save(original: ConditionalRule?, next: ConditionalRule): ConditionalRuleLibrary {
        if (original == null) {
            require(entries.none { it.id == next.id }) { "Rule changed" }
            require(entries.size < MAX_RULES) { "Rule limit reached" }
        } else {
            require(original.id == next.id && entries.firstOrNull { it.id == original.id } == original) { "Rule changed" }
        }
        return copy(entries = if (original == null) entries + next else entries.map { if (it.id == next.id) next else it })
    }

    fun remove(original: ConditionalRule): ConditionalRuleLibrary {
        require(entries.firstOrNull { it.id == original.id } == original) { "Rule changed" }
        return copy(entries = entries.filterNot { it.id == original.id })
    }

    companion object { const val MAX_RULES = 12 }
}

internal data class RuleDecision(
    val rule: ConditionalRule,
    val routine: NamedRoutine,
    val identity: VehicleIdentity,
    val sessionId: String,
    val reservedAtElapsedNanos: Long,
    val signalSources: Map<String, String>,
) {
    /** Recheck immediately before dispatch; a queued decision does not survive edits or source loss. */
    fun isCurrent(
        library: ConditionalRuleLibrary,
        routines: RoutineLibrary,
        telemetry: VehicleTelemetry,
        nowElapsedNanos: Long,
        inputs: RuleInputs = RuleInputs(),
        running: Boolean = false,
    ): Boolean = library.entries.firstOrNull { it.id == rule.id } == rule && rule.enabled &&
        rule.currentRoutine(routines) == routine && identity == telemetry.identity && sessionId == telemetry.sessionId &&
        reservedAtElapsedNanos >= telemetry.sessionStartedAtElapsedNanos && nowElapsedNanos >= reservedAtElapsedNanos &&
        nowElapsedNanos - reservedAtElapsedNanos <= (if (running) EXECUTION_MAX_AGE else HANDOFF_MAX_AGE) &&
        rule.evidence(telemetry, nowElapsedNanos, inputs).let { evidence ->
            evidence.matches == true && evidence.stamps.mapValues { it.value.sourceId } == signalSources
        }

    companion object {
        private const val HANDOFF_MAX_AGE = 5_000_000_000L
        private const val EXECUTION_MAX_AGE = 80_000_000_000L
    }
}

internal enum class RuleAttemptOutcome { COMPLETED, FAILED, UNCONFIRMED, CANCELLED }

internal data class RuleAttempt(val decision: RuleDecision, val outcome: RuleAttemptOutcome)

/** Event driven and Android free. Returned edges are consumed before any external action begins. */
internal class ConditionalRuleEngine {
    private data class State(
        val rule: ConditionalRule,
        val identity: VehicleIdentity,
        val session: String,
        val sessionStartedAt: Long,
        var previousMatch: Boolean? = null,
        var previousTelemetry: VehicleTelemetry? = null,
        var previousInputs: RuleInputs? = null,
        var stamps: Map<String, SignalStamp> = emptyMap(),
        var attemptedAt: Long? = null,
    )

    private val states = mutableMapOf<String, State>()
    private var lastEvaluationAt = 0L
    private val attempts = mutableMapOf<String, RuleAttempt>()

    /** Initial matches, reconnects, stale inputs and edits only seed the next known transition. */
    @Synchronized
    fun evaluate(
        library: ConditionalRuleLibrary,
        routines: RoutineLibrary,
        telemetry: VehicleTelemetry,
        nowElapsedNanos: Long,
        inputs: RuleInputs = RuleInputs(),
    ): List<RuleDecision> {
        require(nowElapsedNanos > 0)
        if (nowElapsedNanos < lastEvaluationAt) states.clear()
        lastEvaluationAt = nowElapsedNanos
        val enabled = library.entries.filter { it.enabled && it.currentRoutine(routines) != null }
        states.keys.retainAll(enabled.map { it.id }.toSet())
        attempts.keys.retainAll(library.entries.map { it.id }.toSet())
        return enabled.mapNotNull { rule ->
            val state = states[rule.id]?.takeIf {
                it.rule == rule && it.identity == telemetry.identity && it.session == telemetry.sessionId &&
                    it.sessionStartedAt == telemetry.sessionStartedAtElapsedNanos
            } ?: State(rule, telemetry.identity, telemetry.sessionId, telemetry.sessionStartedAtElapsedNanos).also { states[rule.id] = it }
            val evidence = rule.evidence(telemetry, nowElapsedNanos, inputs)
            if (evidence.stamps.any { (name, stamp) -> state.stamps[name]?.sourceId?.let { it != stamp.sourceId } == true }) {
                // Replacement sources establish a new baseline even if they reuse the session ID.
                state.previousMatch = evidence.matches
                state.previousTelemetry = telemetry
                state.previousInputs = inputs
                state.stamps = evidence.stamps
                return@mapNotNull null
            }
            if (evidence.matches == null || evidence.stamps.regressedFrom(state.stamps)) {
                state.previousMatch = null
                state.previousTelemetry = null
                state.previousInputs = null
                return@mapNotNull null
            }
            // The previous state must still be valid in this session to arm a transition.
            val wasMatching = state.previousMatch?.takeIf { previous ->
                val earlier = state.previousInputs ?: RuleInputs()
                state.previousTelemetry?.let { rule.evidence(it, nowElapsedNanos, earlier).matches } == previous
            }
            state.previousMatch = evidence.matches
            state.previousTelemetry = telemetry
            state.previousInputs = inputs
            state.stamps = state.stamps + evidence.stamps
            if (wasMatching != false || evidence.matches != true) return@mapNotNull null
            val previousAttempt = state.attemptedAt
            if (previousAttempt != null && nowElapsedNanos - previousAttempt < rule.cooldownSeconds * 1_000_000_000L)
                return@mapNotNull null
            // Reserve before returning. A failure, cancellation or ambiguous response never replays this edge.
            state.attemptedAt = nowElapsedNanos
            RuleDecision(rule, rule.routine, telemetry.identity, telemetry.sessionId, nowElapsedNanos,
                evidence.stamps.mapValues { it.value.sourceId })
        }
    }

    @Synchronized
    fun completeAttempt(decision: RuleDecision, outcome: RuleAttemptOutcome) {
        val state = states[decision.rule.id] ?: return
        if (state.rule == decision.rule && state.session == decision.sessionId && state.identity == decision.identity &&
            state.attemptedAt == decision.reservedAtElapsedNanos
        ) attempts[decision.rule.id] = RuleAttempt(decision, outcome)
    }

    @Synchronized
    fun lastAttempt(id: String): RuleAttempt? = attempts[id]

    /** Call when the foreground owner stops, even if the source has not yet reported disconnection. */
    @Synchronized
    fun invalidate() { states.clear() }
}

private data class SignalStamp(val elapsedNanos: Long, val value: String, val sourceId: String)
private data class RuleEvidence(val matches: Boolean?, val stamps: Map<String, SignalStamp>)

private fun Map<String, SignalStamp>.regressedFrom(before: Map<String, SignalStamp>) = any { (key, sample) ->
    val previous = before[key]
    previous != null && (sample.elapsedNanos < previous.elapsedNanos ||
        sample.elapsedNanos == previous.elapsedNanos && sample.value != previous.value)
}

private fun ConditionalRule.evidence(telemetry: VehicleTelemetry, now: Long, inputs: RuleInputs): RuleEvidence {
    val unavailable = RuleEvidence(null, emptyMap())
    if (telemetry.confirmationWindow?.isOpen == false) return unavailable
    val stamps = mutableMapOf<String, SignalStamp>()
    val matches = mutableListOf<Boolean>()
    if (gear != null) {
        // Gear and doors are reported only on change, so their last value in this session stays valid.
        val sample = telemetry.gear?.takeIf { it.isCurrentIn(telemetry, now) } ?: return unavailable
        stamps["gear"] = SignalStamp(sample.observedAtElapsedNanos, sample.value.key, sample.sourceId)
        matches += sample.value == gear
    }
    if (doors != null) {
        val known = telemetry.doors.filterValues { it.isCurrentIn(telemetry, now) }
        known.forEach { (door, sample) -> stamps[door.key] = SignalStamp(sample.observedAtElapsedNanos, sample.value.key, sample.sourceId) }
        val anyOpen = known.values.any { it.value == VehicleDoorState.OPEN }
        val allClosed = known.size == VehicleDoor.entries.size && known.values.all { it.value == VehicleDoorState.CLOSED }
        if (!anyOpen && !allClosed) return unavailable
        matches += if (doors == DoorRequirement.ANY_OPEN) anyOpen else allClosed
    }
    conditions.outsideCelsius?.let { condition ->
        val sample = telemetry.outsideCelsius?.takeIf { it.value.isFinite() && it.isCurrentIn(telemetry, now) } ?: return unavailable
        stamps["temperature"] = SignalStamp(sample.observedAtElapsedNanos, sample.value.toString(), sample.sourceId)
        matches += condition.matches(sample.value.toDouble())
    }
    conditions.drivingMode?.let { condition ->
        val sample = telemetry.drivingMode?.takeIf { it.isCurrentIn(telemetry, now) } ?: return unavailable
        stamps["mode"] = SignalStamp(sample.observedAtElapsedNanos, sample.value.key, sample.sourceId)
        matches += sample.value == condition
    }
    conditions.ambientLux?.let { condition ->
        val sample = inputs.ambientLux?.takeIf { it.usable(now, Long.MAX_VALUE) && it.value in 0.0..100_000.0 } ?: return unavailable
        stamps["ambient"] = SignalStamp(sample.observedAtNanos, sample.value.toString(), sample.source)
        matches += condition.matches(sample.value)
    }
    conditions.chargePercent?.let { condition ->
        val sample = inputs.chargePercent?.takeIf { it.usable(now, 6_000_000_000L) && it.value in 0.0..100.0 } ?: return unavailable
        stamps["charge"] = SignalStamp(sample.observedAtNanos, sample.value.toString(), sample.source)
        matches += condition.matches(sample.value)
    }
    conditions.timeWindow?.let { range ->
        val sample = inputs.localMinute?.takeIf { it.usable(now, 120_000_000_000L) && it.value in 0.0..1439.0 } ?: return unavailable
        stamps["clock"] = SignalStamp(sample.observedAtNanos, sample.value.toString(), sample.source)
        val minute = sample.value.toInt()
        matches += if (range.startMinute < range.endMinute) minute in range.startMinute until range.endMinute
            else minute >= range.startMinute || minute < range.endMinute
    }
    return RuleEvidence(matches.all { it }, stamps)
}
