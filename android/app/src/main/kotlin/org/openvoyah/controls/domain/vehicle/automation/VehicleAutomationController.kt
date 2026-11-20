package org.openvoyah.controls.domain.vehicle.automation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.domain.vehicle.automation.DrivingMode
import org.openvoyah.controls.domain.vehicle.automation.DrivingModeHistory
import org.openvoyah.controls.domain.vehicle.automation.DrivingModeMemoryEngine
import org.openvoyah.controls.domain.vehicle.automation.DrivingModeRecord
import org.openvoyah.controls.domain.vehicle.automation.HeadlightGearAction
import org.openvoyah.controls.domain.vehicle.automation.HeadlightGearEngine
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationCapabilities
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationOutcome
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationPort
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationSettings
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationStore
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignalKind
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignal

internal class DrivingModeRestorePrompt(
    val record: DrivingModeRecord,
    val sessionId: String,
    val settings: VehicleAutomationSettings,
    val actualMode: VehicleSignal<DrivingMode>,
    val sessionStartedAtElapsedNanos: Long,
)

internal data class VehicleAutomationState(
    val settings: VehicleAutomationSettings? = null,
    val settingsFailed: Boolean = false,
    val historyFailed: Boolean = false,
    val saving: Boolean = false,
    val working: Boolean = false,
    val saveFailed: Boolean = false,
    val telemetry: VehicleTelemetry? = null,
    val lighting: HeadlightStatus = HeadlightStatus(),
    val rememberedMode: DrivingModeRecord? = null,
    val actualMode: DrivingMode? = null,
    val canRememberCurrent: Boolean = false,
    val restoreCandidate: DrivingModeRecord? = null,
    val capabilities: VehicleAutomationCapabilities = VehicleAutomationCapabilities(),
    val prompt: DrivingModeRestorePrompt? = null,
    val outcome: VehicleAutomationOutcome? = null,
)

/** Shared automatic work is scoped to an explicit runtime lease, independent of screen lifetime. */
internal class VehicleAutomationController(
    private val store: VehicleAutomationStore,
    private val history: DrivingModeHistory,
    private val telemetry: Flow<VehicleTelemetry>,
    private val lighting: Flow<HeadlightStatus>,
    private val port: VehicleAutomationPort,
    private val nowNanos: () -> Long,
    scope: CoroutineScope,
    private val logEvent: (String) -> Unit = {},
) {
    private val tasks = scope
    private val mutable = MutableStateFlow(VehicleAutomationState())
    private val headlights = HeadlightGearEngine()
    private val driving = DrivingModeMemoryEngine()
    private var monitoring: Job? = null
    private var restoreWork: Job? = null
    private var restoreOwner: Any? = null
    private var generation = 0L
    val state = mutable.asStateFlow()

    fun startMonitoring() {
        if (monitoring?.isActive == true) return
        val active = ++generation
        log("event=vehicle_automation_monitor outcome=started")
        monitoring = tasks.launch {
            combine(store.observe(), history.observe(), telemetry, lighting) { config, remembered, car, lamps ->
                Observation(config, remembered, car, lamps)
            }.collect { observation ->
                if (active != generation) return@collect
                val (configuration, remembered, car, lamps) = observation
                val settings = configuration.getOrNull()
                if (settings == null) headlights.reset()
                if (settings == null || remembered.isFailure) driving.reset()
                val now = nowNanos()
                val memory = settings?.takeIf { remembered.isSuccess }?.let {
                    driving.evaluate(it.drivingMode, remembered.getOrNull(), car, now)
                }
                val capabilities = try { port.capabilities(car.identity) } catch (_: RuntimeException) { VehicleAutomationCapabilities() }
                mutable.update { before ->
                    val prompt = before.prompt?.takeIf { prompt ->
                        settings == prompt.settings && car.sessionId == prompt.sessionId &&
                            car.sessionStartedAtElapsedNanos == prompt.sessionStartedAtElapsedNanos &&
                            remembered.getOrNull() == prompt.record && prompt.record.mode in capabilities.drivingModes &&
                            memory?.restore == prompt.record && car.drivingMode == prompt.actualMode && car.isStationary(now)
                    }
                    before.copy(settings = settings, settingsFailed = configuration.isFailure,
                        historyFailed = remembered.isFailure, telemetry = car, lighting = lamps,
                        rememberedMode = remembered.getOrNull(), actualMode = memory?.actual,
                        canRememberCurrent = settings?.drivingMode?.enabled == true && remembered.isSuccess &&
                            car.isStationary(now) && VehicleSignalKind.DRIVING_MODE !in car.unavailable &&
                            car.drivingMode?.isFreshFor(car, now) == true,
                        restoreCandidate = memory?.restore, capabilities = capabilities, prompt = prompt)
                }
                memory?.save?.takeIf { active == generation }?.let { record ->
                    try { history.save(record) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        log("event=driving_mode_memory outcome=save_failed error=${failure.javaClass.simpleName}")
                        if (active == generation) mutable.update { it.copy(historyFailed = true) }
                    }
                }
                if (settings != null) {
                    val decision = headlights.evaluate(settings.headlights, car, lamps, now)
                    if (decision != null) {
                        if (decision.action !in capabilities.headlightActions) {
                            log("event=headlight_gear_action gear=${decision.gear.value.key} action=${decision.action.key} outcome=unqualified")
                            mutable.update { it.copy(outcome = VehicleAutomationOutcome.BLOCKED) }
                        } else if (!mutable.value.working && active == generation &&
                            decision.isCurrent(settings.headlights, car, lamps, nowNanos())) {
                            log("event=headlight_gear_action gear=${decision.gear.value.key} action=${decision.action.key} outcome=dispatching")
                            mutable.update { it.copy(working = true) }
                            val outcome = attempt { port.setHeadlight(decision) }
                            log("event=headlight_gear_action gear=${decision.gear.value.key} action=${decision.action.key} outcome=${outcome.name.lowercase()}")
                            if (active == generation) mutable.update { it.copy(working = false, outcome = outcome) }
                        }
                    }
                }
            }
        }
    }

    fun stopMonitoring() {
        generation++
        if (monitoring != null) log("event=vehicle_automation_monitor outcome=stopped")
        monitoring?.cancel()
        monitoring = null
        restoreOwner = null
        restoreWork?.cancel()
        restoreWork = null
        headlights.reset()
        driving.reset()
        mutable.update { it.copy(telemetry = null, actualMode = null, canRememberCurrent = false,
            restoreCandidate = null, prompt = null, working = false, settingsFailed = false,
            historyFailed = false, saveFailed = false, outcome = null) }
    }

    fun setHeadlightsEnabled(enabled: Boolean) = change { copy(headlights = headlights.copy(enabled = enabled)) }
    fun setDriveAction(action: HeadlightGearAction) {
        if (action.allowedInDrive) change { copy(headlights = headlights.copy(onDrive = action)) }
    }
    fun setParkAction(action: HeadlightGearAction) = change { copy(headlights = headlights.copy(onPark = action)) }
    fun setDrivingMemoryEnabled(enabled: Boolean) = change { copy(drivingMode = drivingMode.copy(enabled = enabled)) }

    private fun change(edit: VehicleAutomationSettings.() -> VehicleAutomationSettings) {
        val before = mutable.value
        val settings = before.settings ?: return
        if (before.saving || before.working) return
        val next = settings.edit()
        if (next == settings) return
        mutable.value = before.copy(saving = true, saveFailed = false, prompt = null, outcome = null)
        tasks.launch {
            try {
                store.update(settings, next)
                mutable.update { it.copy(saving = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                log("event=vehicle_automation_settings outcome=save_failed error=${failure.javaClass.simpleName}")
                mutable.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    fun prepareRestore() {
        val before = mutable.value
        val settings = before.settings ?: return
        val target = before.restoreCandidate ?: return
        val car = before.telemetry ?: return
        val actual = car.drivingMode
        if (before.working || before.saving || !car.isStationary(nowNanos()) || target.mode !in before.capabilities.drivingModes ||
            VehicleSignalKind.DRIVING_MODE in car.unavailable || actual == null || !actual.isFreshFor(car, nowNanos())) {
            mutable.update { it.copy(outcome = VehicleAutomationOutcome.BLOCKED) }
            return
        }
        mutable.value = before.copy(prompt = DrivingModeRestorePrompt(target, car.sessionId, settings, actual,
            car.sessionStartedAtElapsedNanos), outcome = null)
    }

    fun dismissRestore() { mutable.update { it.copy(prompt = null) } }

    /** Hiding the confirmation owner cannot leave a queued manual request alive. */
    fun cancelManualActions() {
        restoreOwner = null
        val work = restoreWork
        restoreWork = null
        work?.cancel()
        mutable.update { it.copy(prompt = null, working = if (work != null) false else it.working) }
    }

    fun confirmRestore(prompt: DrivingModeRestorePrompt) {
        val before = mutable.value
        val car = before.telemetry ?: return
        if (before.prompt !== prompt || before.working || before.saving || before.settings != prompt.settings ||
            before.restoreCandidate != prompt.record || car.sessionId != prompt.sessionId ||
            car.sessionStartedAtElapsedNanos != prompt.sessionStartedAtElapsedNanos ||
            car.drivingMode != prompt.actualMode ||
            prompt.record.identity != car.identity || !car.isStationary(nowNanos()) ||
            VehicleSignalKind.DRIVING_MODE in car.unavailable ||
            car.drivingMode?.isFreshFor(car, nowNanos()) != true ||
            prompt.record.mode !in before.capabilities.drivingModes) {
            if (before.prompt === prompt) mutable.update { it.copy(prompt = null, outcome = VehicleAutomationOutcome.BLOCKED) }
            return
        }
        mutable.value = before.copy(prompt = null, working = true)
        val owner = Any()
        restoreOwner = owner
        restoreWork = tasks.launch {
            try {
                val current = mutable.value
                val latest = current.telemetry
                if (restoreOwner !== owner || current.settings != prompt.settings || current.rememberedMode != prompt.record ||
                    latest == null || latest.sessionId != car.sessionId || latest.identity != car.identity ||
                    latest.sessionStartedAtElapsedNanos != car.sessionStartedAtElapsedNanos ||
                    latest.drivingMode != car.drivingMode || !latest.isStationary(nowNanos()) ||
                    latest.drivingMode?.isFreshFor(latest, nowNanos()) != true) {
                    if (restoreOwner === owner) mutable.update { it.copy(outcome = VehicleAutomationOutcome.BLOCKED) }
                    return@launch
                }
                log("event=driving_mode_restore mode=${prompt.record.mode.key} outcome=dispatching")
                val outcome = attempt { port.restoreDrivingMode(prompt.record, latest) }
                log("event=driving_mode_restore mode=${prompt.record.mode.key} outcome=${outcome.name.lowercase()}")
                // Native receipts do not replace actual-mode history. Only feedback can save it.
                if (restoreOwner === owner) mutable.update { it.copy(outcome = outcome) }
            } finally {
                if (restoreOwner === owner) {
                    restoreOwner = null
                    restoreWork = null
                    mutable.update { it.copy(working = false) }
                }
            }
        }
    }

    fun rememberCurrent() {
        val before = mutable.value
        val settings = before.settings ?: return
        val car = before.telemetry ?: return
        if (before.working || before.saving || before.historyFailed) return
        val record = driving.acceptCurrent(settings.drivingMode, car, nowNanos()) ?: run {
            mutable.update { it.copy(canRememberCurrent = false, outcome = VehicleAutomationOutcome.BLOCKED) }
            return
        }
        mutable.value = before.copy(saving = true, saveFailed = false, prompt = null)
        tasks.launch {
            try { history.save(record); mutable.update { it.copy(saving = false) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(saving = false, saveFailed = true) } }
        }
    }

    private suspend fun attempt(action: suspend () -> VehicleAutomationOutcome): VehicleAutomationOutcome = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        log("event=vehicle_automation_request outcome=unconfirmed error=${failure.javaClass.simpleName}")
        VehicleAutomationOutcome.UNCONFIRMED
    }

    private fun log(event: String) { runCatching { logEvent("$event elapsed_nanos=${nowNanos()}") } }

    private data class Observation(
        val settings: Result<VehicleAutomationSettings>, val remembered: Result<DrivingModeRecord?>,
        val telemetry: VehicleTelemetry, val lighting: HeadlightStatus,
    )
}
