package org.openvoyah.controls.platform.vehicle.automation

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.openvoyah.controls.data.climate.FridaSeasonalClimatePort
import org.openvoyah.controls.data.climate.LocalSeasonalClimatePreferences
import org.openvoyah.controls.data.routines.RoutinePreferences
import org.openvoyah.controls.data.rules.ConditionalRulesPreferences
import org.openvoyah.controls.data.vehicle.LocalHeadlightHistory
import org.openvoyah.controls.data.vehicle.automation.FridaVehicleAutomationPort
import org.openvoyah.controls.data.vehicle.automation.LocalDrivingModeHistory
import org.openvoyah.controls.data.vehicle.automation.LocalVehicleAutomationPreferences
import org.openvoyah.controls.domain.climate.ClimateConditionState
import org.openvoyah.controls.domain.climate.SeasonalClimateEdgeEngine
import org.openvoyah.controls.domain.climate.SeasonalClimatePolicy
import org.openvoyah.controls.domain.rules.ConditionalRuleEngine
import org.openvoyah.controls.domain.rules.RuleAttemptOutcome
import org.openvoyah.controls.domain.rules.RuleDecision
import org.openvoyah.controls.domain.rules.RuleHandoff
import org.openvoyah.controls.domain.vehicle.HeadlightMonitor
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationController
import org.openvoyah.controls.domain.vehicle.automation.VehicleForegroundLeases
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetrySession
import org.openvoyah.controls.domain.vehicle.automation.withVehicleSignalExpiry
import org.openvoyah.controls.platform.vehicle.factoryHeadlightFeedback
import org.openvoyah.controls.platform.ui.VehicleRoutineLauncher

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val background = MutableStateFlow<Any?>(null)
    private val foreground = MutableStateFlow(VehicleForegroundLeases<suspend (RuleDecision) -> Int?>())
    private val mutable = MutableStateFlow(VehicleRuntimeState())
    val state = mutable.asStateFlow()
    val source = NativeVehicleTelemetryMonitor(app)
    val telemetry = source.observe().withVehicleSignalExpiry(SystemClock::elapsedRealtimeNanos)
        .stateIn(scope, SharingStarted.WhileSubscribed(0, 0), source.unknownTelemetry())
    val headlights = HeadlightMonitor(factoryHeadlightFeedback(app), LocalHeadlightHistory(app)).observe()
        .stateIn(scope, SharingStarted.WhileSubscribed(0, 0), HeadlightStatus())
    private val automation = LocalVehicleAutomationPreferences(app)
    private val rules = ConditionalRulesPreferences(app)
    private val routines = RoutinePreferences(app)
    private val climates = LocalSeasonalClimatePreferences(app)
    val climatePort = FridaSeasonalClimatePort(app) { telemetry.value }
    val controller = VehicleAutomationController(automation, LocalDrivingModeHistory(app), telemetry, headlights,
        FridaVehicleAutomationPort(app, { telemetry.value }, { headlights.value }),
        SystemClock::elapsedRealtimeNanos, scope, { Log.i(TAG, it) })
    private val handoff = RuleHandoff<RuleDecision>()

    init {
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
                Log.i(TAG, "event=vehicle_workflow_runtime outcome=started background=${state.value.backgroundConnected}")
                try {
                    coroutineScope {
                        launch { observeRules() }
                        launch { observeClimate() }
                        awaitCancellation()
                    }
                } finally {
                    handoff.clear()
                    controller.stopMonitoring()
                    Log.i(TAG, "event=vehicle_workflow_runtime outcome=stopped")
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
        val token = intent.getStringExtra(VehicleRoutineLauncher.HANDOFF) ?: return null
        intent.removeExtra(VehicleRoutineLauncher.HANDOFF)
        return handoff.claim(token, SystemClock.elapsedRealtimeNanos(), ::isCurrent)
    }

    fun isCurrent(decision: RuleDecision): Boolean = try {
        state.value.automaticRunning && decision.isCurrent(rules.current(),
            // The store verifies the exact selected receipt; no replacement routine is accepted.
            org.openvoyah.controls.domain.routines.RoutineLibrary(entries = listOf(routines.withCurrentPlan(decision.routine) { decision.routine })),
            telemetry.value, SystemClock.elapsedRealtimeNanos())
    } catch (_: Exception) { false }

    private suspend fun observeRules() {
        val engine = ConditionalRuleEngine()
        try {
            combine(rules.observe(), routines.observe(), telemetry) { saved, library, car -> Triple(saved, library, car) }
                .collect { (saved, library, car) ->
                    val settings = saved.getOrNull()
                    val available = library.getOrNull()
                    if (settings == null || available == null) { engine.invalidate(); return@collect }
                    for (decision in engine.evaluate(settings, available, car, SystemClock.elapsedRealtimeNanos())) {
                        var outcome = RuleAttemptOutcome.CANCELLED
                        try {
                            if (!withContext(Dispatchers.IO) { isCurrent(decision) }) continue
                            val target = foreground.value.latest
                            if (target != null) {
                                val result = supervisorScope { async {
                                    if (!foreground.value.retains(target)) throw CancellationException("Routine window lease expired")
                                    target.dispatch(decision)
                                }.await() }
                                outcome = if (result == null) RuleAttemptOutcome.COMPLETED else RuleAttemptOutcome.FAILED
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
                            Log.i(TAG, "event=conditional_rule_attempt rule=${decision.rule.id} outcome=${outcome.name.lowercase()}")
                        }
                    }
                }
        } finally { engine.invalidate() }
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
                        Log.i(TAG, "event=seasonal_climate_automatic season=${plan.season.name.lowercase()} " +
                            "outcome=${result.status.name.lowercase()} completed=${result.completedCommands}")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    engine.invalidate()
                    Log.w(TAG, "event=seasonal_climate_automatic outcome=unconfirmed error=${failure.javaClass.simpleName}")
                }
            }
        } finally { engine.invalidate() }
    }

    private data class RuntimeLease(val background: Boolean, val configured: Boolean, val monitoring: Boolean)

    companion object {
        private const val TAG = "OpenVoyahControls"
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
