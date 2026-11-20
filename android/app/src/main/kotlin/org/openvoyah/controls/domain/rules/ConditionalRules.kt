package org.openvoyah.controls.domain.rules

import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.routines.RoutineLibrary
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoorState
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.domain.vehicle.automation.VehicleIdentity
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignalKind
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
) {
    init {
        require(NamedRoutine.validId(id) && NamedRoutine.normalizeName(name) == name)
        require(gear != null || doors != null)
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
    ): Boolean = library.entries.firstOrNull { it.id == rule.id } == rule && rule.enabled &&
        rule.currentRoutine(routines) == routine && identity == telemetry.identity && sessionId == telemetry.sessionId &&
        reservedAtElapsedNanos >= telemetry.sessionStartedAtElapsedNanos && nowElapsedNanos >= reservedAtElapsedNanos &&
        nowElapsedNanos - reservedAtElapsedNanos <= 5_000_000_000L &&
        rule.evidence(telemetry, nowElapsedNanos).let { evidence ->
            evidence.matches == true && evidence.stamps.mapValues { it.value.sourceId } == signalSources
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
            val evidence = rule.evidence(telemetry, nowElapsedNanos)
            if (evidence.stamps.any { (name, stamp) -> state.stamps[name]?.sourceId?.let { it != stamp.sourceId } == true }) {
                // Replacement sources establish a new baseline even if they reuse the session ID.
                state.previousMatch = evidence.matches
                state.previousTelemetry = telemetry
                state.stamps = evidence.stamps
                return@mapNotNull null
            }
            if (evidence.matches == null || evidence.stamps.regressedFrom(state.stamps)) {
                state.previousMatch = null
                state.previousTelemetry = null
                return@mapNotNull null
            }
            // A silent source can expire between callbacks; its old false state cannot arm a later true sample.
            val wasMatching = state.previousMatch?.takeIf { previous ->
                state.previousTelemetry?.let { rule.evidence(it, nowElapsedNanos).matches } == previous
            }
            state.previousMatch = evidence.matches
            state.previousTelemetry = telemetry
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

private fun ConditionalRule.evidence(telemetry: VehicleTelemetry, now: Long): RuleEvidence {
    val stamps = mutableMapOf<String, SignalStamp>()
    val matches = mutableListOf<Boolean>()
    if (gear != null) {
        val sample = telemetry.gear?.takeIf {
            VehicleSignalKind.GEAR !in telemetry.unavailable && it.isFreshFor(telemetry, now)
        } ?: return RuleEvidence(null, emptyMap())
        stamps["gear"] = SignalStamp(sample.observedAtElapsedNanos, sample.value.key, sample.sourceId)
        matches += sample.value == gear
    }
    if (doors != null) {
        if (VehicleSignalKind.DOORS in telemetry.unavailable) return RuleEvidence(null, emptyMap())
        val known = telemetry.doors.filterValues { it.isFreshFor(telemetry, now) }
        known.forEach { (door, sample) -> stamps[door.key] = SignalStamp(sample.observedAtElapsedNanos, sample.value.key, sample.sourceId) }
        val anyOpen = known.values.any { it.value == VehicleDoorState.OPEN }
        val allClosed = known.size == VehicleDoor.entries.size && known.values.all { it.value == VehicleDoorState.CLOSED }
        if (!anyOpen && !allClosed) return RuleEvidence(null, emptyMap())
        matches += if (doors == DoorRequirement.ANY_OPEN) anyOpen else allClosed
    }
    return RuleEvidence(matches.all { it }, stamps)
}
