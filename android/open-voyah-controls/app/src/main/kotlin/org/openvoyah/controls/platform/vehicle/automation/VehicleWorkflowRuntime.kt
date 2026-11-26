package org.openvoyah.controls.platform.vehicle.automation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.data.climate.FridaSeasonalClimatePort
import org.openvoyah.controls.data.climate.LocalSeasonalClimatePreferences
import org.openvoyah.controls.data.routines.RoutinePreferences
import org.openvoyah.controls.data.rules.ConditionalRulesPreferences
import org.openvoyah.controls.data.vehicle.LocalHeadlightHistory
import org.openvoyah.controls.data.vehicle.automation.FridaVehicleAutomationPort
import org.openvoyah.controls.data.vehicle.automation.LocalDrivingModeHistory
import org.openvoyah.controls.data.vehicle.automation.LocalVehicleAutomationPreferences
import org.openvoyah.controls.data.vehicle.comfort.PlaybackComfortPreferences
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.diagnostics.LogLevel
import org.openvoyah.controls.domain.climate.ClimateConditionState
import org.openvoyah.controls.domain.climate.SeasonalClimateEdgeEngine
import org.openvoyah.controls.domain.climate.SeasonalClimatePolicy
import org.openvoyah.controls.domain.rules.ConditionalRuleEngine
import org.openvoyah.controls.domain.rules.RuleAttemptOutcome
import org.openvoyah.controls.domain.rules.RuleDecision
import org.openvoyah.controls.domain.rules.RuleHandoff
import org.openvoyah.controls.domain.rules.RuleInputs
import org.openvoyah.controls.domain.rules.RuleReading
import org.openvoyah.controls.domain.trips.TripReadings
import org.openvoyah.controls.domain.vehicle.HeadlightMonitor
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationController
import org.openvoyah.controls.domain.vehicle.automation.VehicleForegroundLeases
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetrySession
import org.openvoyah.controls.domain.vehicle.automation.withVehicleSignalExpiry
import org.openvoyah.controls.platform.vehicle.factoryHeadlightFeedback
import org.openvoyah.controls.platform.trips.TripRuntime
import org.openvoyah.controls.platform.trips.TripCarPermissions
import org.openvoyah.controls.platform.ui.VehicleRoutineLauncher
import org.openvoyah.controls.platform.vehicle.comfort.observePlaybackComfort
import org.openvoyah.controls.platform.vehicle.comfort.AmbientLight
import org.openvoyah.controls.platform.vehicle.comfort.AmbientLightReading

internal data class VehicleRuntimeState(
    val backgroundConnected: Boolean = false,
    val automaticConfigured: Boolean = false,
    val automaticRunning: Boolean = false,
) {
    val foregroundOnly: Boolean get() = automaticRunning && !backgroundConnected
}

/** One process owner. The existing accessibility service holds the background lease; UI is optional. */
internal class VehicleWorkflowRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    // No exception handler on purpose. A failure that nothing below handles ends the lease coroutine or a shared
    // state for good, and a runtime that keeps showing its last lamp state while it no longer observes anything is
    // worse than a process that restarts together with its accessibility service. The uncaught-exception hook of
    // Diagnostics records the failure before Android ends the process.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val background = MutableStateFlow<Any?>(null)
    private val foreground = MutableStateFlow(VehicleForegroundLeases<suspend (RuleDecision) -> Int?>())
    private val mutable = MutableStateFlow(VehicleRuntimeState())
    val state = mutable.asStateFlow()
    val source = NativeVehicleTelemetryMonitor(app)
    val telemetry = source.observe().withVehicleSignalExpiry(SystemClock::elapsedRealtimeNanos)
        .stateIn(scope, SharingStarted.WhileSubscribed(0, 0), source.unknownTelemetry())
    val trips = TripRuntime(app, telemetry)
    val headlights = HeadlightMonitor(factoryHeadlightFeedback(app), LocalHeadlightHistory(app), reportFailure = { failure ->
        ControlsLog.warn("headlight_monitor", failure, "outcome", "feedback_failed")
    }).observe().stateIn(scope, SharingStarted.WhileSubscribed(0, 0), HeadlightStatus())
    private val automation = LocalVehicleAutomationPreferences(app)
    private val rules = ConditionalRulesPreferences(app)
    private val routines = RoutinePreferences(app)
    private val climates = LocalSeasonalClimatePreferences(app)
    private val playback = PlaybackComfortPreferences(app)
    val climatePort = FridaSeasonalClimatePort(app) { telemetry.value }
    val controller = VehicleAutomationController(automation, LocalDrivingModeHistory(app), telemetry, headlights,
        FridaVehicleAutomationPort(app, { telemetry.value }, { headlights.value }),
        SystemClock::elapsedRealtimeNanos, scope, { ControlsLog.line(LogLevel.INFO, it) })
    private val handoff = RuleHandoff<RuleDecision>()
    private val ruleClock = MutableStateFlow<RuleReading?>(null)

    init {
        scope.launch {
            combine(background, foreground, playback.observe()) { service, screens, saved ->
                saved.getOrNull()?.takeIf { it.configured && (service != null || screens.hasOwners) }
            }.distinctUntilChanged().collectLatest { settings ->
                if (settings != null) observePlaybackComfort(app, playback, settings, telemetry) {
                    background.value != null || foreground.value.hasOwners
                }
            }
        }
        scope.launch {
            combine(background, foreground) { service, screens -> (service != null) to screens.hasOwners }
                .distinctUntilChanged().collect { (service, screen) -> trips.setLease(service, screen) }
        }
        scope.launch {
            combine(background, foreground, automation.observe(), climates.observe(), rules.observe()) {
                    service, screens, savedAutomation, savedClimate, savedRules ->
                val vehicle = savedAutomation.getOrNull()
                val climate = savedClimate.getOrNull()
                val configured = vehicle?.headlights?.enabled == true || vehicle?.drivingMode?.enabled == true ||
                    climate?.summer?.enabled == true || climate?.winter?.enabled == true ||
                    savedRules.getOrNull()?.entries?.any { it.enabled } == true
                val allowed = service != null || screens.hasOwners
                val monitoring = allowed && (configured || screens.hasConfirmationOwner)
                RuntimeLease(service != null, configured, monitoring)
            }.distinctUntilChanged().onEach { lease ->
                mutable.value = VehicleRuntimeState(lease.background, lease.configured, lease.monitoring && lease.configured)
            }.map { it.monitoring }.distinctUntilChanged().collectLatest { monitoring ->
                if (!monitoring) return@collectLatest
                controller.startMonitoring()
                ControlsLog.info("vehicle_workflow_runtime", "outcome", "started", "background", state.value.backgroundConnected)
                try {
                    coroutineScope {
                        launch { observeRules() }
                        launch { observeClimate() }
                        awaitCancellation()
                    }
                } finally {
                    handoff.clear()
                    controller.stopMonitoring()
                    ControlsLog.info("vehicle_workflow_runtime", "outcome", "stopped")
                }
            }
        }
    }

    fun foregroundStarted(owner: Any, monitor: Boolean, dispatch: suspend (RuleDecision) -> Int?) {
        val previous = foreground.value
        val current = previous.started(owner, monitor, dispatch)
        foreground.value = current
        if (previous.hasConfirmationOwner && !current.hasConfirmationOwner) controller.cancelManualActions()
    }

    fun foregroundStopped(owner: Any) {
        val previous = foreground.value
        val current = previous.stopped(owner)
        foreground.value = current
        if (previous.hasConfirmationOwner && !current.hasConfirmationOwner) controller.cancelManualActions()
    }

    /** Public Activity intents never contain executable rule data. Claims are bounded and process local. */
    fun claim(intent: Intent): RuleDecision? {
        val token = try {
            intent.getStringExtra(VehicleRoutineLauncher.HANDOFF)
        } catch (failure: RuntimeException) {
            // Any app can start the exported activity with a Parcelable this process cannot unparcel.
            ControlsLog.warn("rule_handoff", failure, "outcome", "unreadable_extras")
            intent.replaceExtras(null as android.os.Bundle?)
            return null
        } ?: return null
        intent.removeExtra(VehicleRoutineLauncher.HANDOFF)
        return handoff.claim(token, SystemClock.elapsedRealtimeNanos(), ::isCurrent)
    }

    fun isCurrent(decision: RuleDecision) = checkRule(decision, running = false)

    fun staysCurrent(decision: RuleDecision) = checkRule(decision, running = true)

    private fun checkRule(decision: RuleDecision, running: Boolean): Boolean = try {
        val conditions = decision.rule.conditions
        val live = trips.snapshot.live.takeIf { conditions.chargePercent != null }
        val light = if (conditions.ambientLux != null) AmbientLight.readings(app).value else null
        val clock = ruleClock.value.takeIf { conditions.timeWindow != null }
        state.value.automaticRunning && (background.value != null || foreground.value.hasOwners) &&
            decision.isCurrent(rules.current(),
            // The store verifies the exact selected receipt; no replacement routine is accepted.
            org.openvoyah.controls.domain.routines.RoutineLibrary(entries = listOf(routines.withCurrentPlan(decision.routine) { decision.routine })),
            telemetry.value, SystemClock.elapsedRealtimeNanos(), currentRuleInputs(light, live, clock, recheckClock = true), running)
    } catch (_: Exception) { false }

    private fun currentRuleInputs(
        light: AmbientLightReading?, trip: TripReadings?, clock: RuleReading?, recheckClock: Boolean = false,
    ): RuleInputs {
        if (light == null && trip == null && clock == null ||
            app.getSystemService(PowerManager::class.java)?.isInteractive != true) return RuleInputs()
        val charge = trip?.levels?.chargePercent
        val receivedAt = trip?.chargeTimestampNanos
        val source = trip?.session
        val energy = if (charge != null && receivedAt != null && source != null &&
            app.checkSelfPermission(TripCarPermissions.ENERGY) == PackageManager.PERMISSION_GRANTED) {
            RuleReading(charge, receivedAt, "automotive_charge:$source")
        } else null
        val localMinute = clock?.takeIf {
            if (!recheckClock) return@takeIf true
            val now = LocalDateTime.now()
            now.year >= 2020 && it.value == (now.hour * 60 + now.minute).toDouble()
        }
        return RuleInputs(light?.let { RuleReading(it.lux.toDouble(), it.receivedAtNanos, "android_illuminance") }, energy, localMinute)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeRules(): Unit = coroutineScope {
        val engine = ConditionalRuleEngine()
        val saved = rules.observe().shareIn(this, SharingStarted.Eagerly, replay = 1)
        val inputs = saved.map { value ->
            val enabled = value.getOrNull()?.entries.orEmpty().filter { it.enabled }.map { it.conditions }
            Triple(enabled.any { it.ambientLux != null }, enabled.any { it.chargePercent != null }, enabled.any { it.timeWindow != null })
        }.distinctUntilChanged().flatMapLatest { (light, energy, time) ->
            val ambient = if (light) AmbientLight.readings(app) else flowOf(null)
            val charge = if (energy) trips.state.map { it.live }.distinctUntilChanged() else flowOf(null)
            val clock = (if (time) observeRuleClock(app) else flowOf(null)).onEach { ruleClock.value = it }
            combine(ambient, charge, clock) { lux, battery, minute -> currentRuleInputs(lux, battery, minute) }
        }
        try {
            combine(saved, routines.observe(), telemetry, inputs) { rules, library, car, input -> Triple(rules, library, car to input) }
                .collect { (current, library, sources) ->
                    val (car, input) = sources
                    val settings = current.getOrNull()
                    val available = library.getOrNull()
                    if (settings == null || available == null) { engine.invalidate(); return@collect }
                    for (decision in engine.evaluate(settings, available, car, SystemClock.elapsedRealtimeNanos(), input)) {
                        var outcome = RuleAttemptOutcome.CANCELLED
                        try {
                            if (!withContext(Dispatchers.IO) { isCurrent(decision) }) continue
                            val target = foreground.value.latest
                            if (target != null) {
                                val result = supervisorScope { async {
                                    if (!foreground.value.retains(target)) throw CancellationException("Routine window lease expired")
                                    target.dispatch(decision)
                                }.await() }
                                outcome = when (result) {
                                    null -> RuleAttemptOutcome.COMPLETED
                                    R.string.routine_chain_unconfirmed -> RuleAttemptOutcome.UNCONFIRMED
                                    else -> RuleAttemptOutcome.FAILED
                                }
                            } else {
                                val token = handoff.reserve(decision, SystemClock.elapsedRealtimeNanos()) ?: continue
                                try {
                                    VehicleRoutineLauncher(app).launch(decision.routine.plan.target, token)
                                    outcome = RuleAttemptOutcome.UNCONFIRMED
                                } catch (_: RuntimeException) { handoff.discard(token); outcome = RuleAttemptOutcome.FAILED }
                            }
                        } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
                        catch (_: Exception) { outcome = RuleAttemptOutcome.UNCONFIRMED }
                        finally {
                            engine.completeAttempt(decision, outcome)
                            ControlsLog.info("conditional_rule_attempt", "rule", decision.rule.id, "outcome", outcome)
                        }
                    }
                }
        } finally {
            ruleClock.value = null
            engine.invalidate()
        }
    }

    private suspend fun observeClimate() {
        val engine = SeasonalClimateEdgeEngine()
        try {
            combine(climates.observe(), telemetry) { saved, car -> saved to car }.collect { (saved, car) ->
                val settings = saved.getOrNull()
                if (settings == null) { engine.invalidate(); return@collect }
                try {
                    for (plan in engine.observe(settings, car, SystemClock.elapsedRealtimeNanos())) {
                        if (!climatePort.approved(plan)) continue
                        val session = VehicleTelemetrySession(car)
                        val current = climates.observe().first().getOrNull()
                        val latest = telemetry.value
                        if (current != settings || !session.matches(latest) ||
                            SeasonalClimatePolicy.assess(plan.season, plan.preset, latest, SystemClock.elapsedRealtimeNanos()) !=
                                ClimateConditionState.READY) continue
                        val result = climatePort.apply(plan, session)
                        ControlsLog.info("seasonal_climate_automatic", "season", plan.season, "outcome", result.status,
                            "completed", result.completedCommands)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    engine.invalidate()
                    ControlsLog.warn("seasonal_climate_automatic", "outcome", "unconfirmed", "error", failure)
                }
            }
        } finally { engine.invalidate() }
    }

    private data class RuntimeLease(val background: Boolean, val configured: Boolean, val monitoring: Boolean)

    companion object {
        @Volatile private var instance: VehicleWorkflowRuntime? = null
        @JvmStatic fun get(context: Context): VehicleWorkflowRuntime = instance ?: synchronized(this) {
            instance ?: VehicleWorkflowRuntime(context).also { instance = it }
        }
        @JvmStatic fun serviceConnected(context: Context, owner: Any) { get(context).background.value = owner }
        @JvmStatic fun serviceDisconnected(context: Context, owner: Any) {
            get(context).background.update { if (it === owner) null else it }
        }
    }
}
