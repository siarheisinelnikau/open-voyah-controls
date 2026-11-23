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
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignal

internal class DrivingModeRestorePrompt(
    val record: DrivingModeRecord,
    val sessionId: String,
    val settings: VehicleAutomationSettings,
    val actualMode: VehicleSignal<DrivingMode>,
    val sessionStartedAtElapsedNanos: Long,
)

/** What the driving-mode memory card tells the driver after Save or Restore. */
internal enum class DrivingModeMemoryNotice { SAVED, RESTORE_SENT, RESTORED, RESTORE_FAILED, RESTORE_NEEDS_PARK }

internal data class DrivingModeMemoryMessage(val notice: DrivingModeMemoryNotice, val mode: DrivingMode)

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
    /** The session knows the car is out of Park or moving, so a manual restore waits. */
    val restoreNeedsPark: Boolean = false,
    val capabilities: VehicleAutomationCapabilities = VehicleAutomationCapabilities(),
    val prompt: DrivingModeRestorePrompt? = null,
    val outcome: VehicleAutomationOutcome? = null,
    val memoryMessage: DrivingModeMemoryMessage? = null,
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
                // The adapter's checked I/O failures must not escape into the shared runtime scope.
                val capabilities = try { port.capabilities(car.identity) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { VehicleAutomationCapabilities() }
                mutable.update { before ->
                    val prompt = before.prompt?.takeIf { prompt ->
                        settings == prompt.settings && car.sessionId == prompt.sessionId &&
                            car.sessionStartedAtElapsedNanos == prompt.sessionStartedAtElapsedNanos &&
                            remembered.getOrNull() == prompt.record && prompt.record.mode in capabilities.drivingModes &&
                            memory?.restore == prompt.record && car.drivingMode == prompt.actualMode &&
                            car.allowsDrivingModeRestore(now)
                    }
                    // A sent restore is confirmed only by the actual mode the VCU reports afterwards.
                    val message = before.memoryMessage?.let { sent ->
                        if (sent.notice == DrivingModeMemoryNotice.RESTORE_SENT && memory?.actual == sent.mode)
                            DrivingModeMemoryMessage(DrivingModeMemoryNotice.RESTORED, sent.mode) else sent
                    }
                    val confirmedNow = message?.notice == DrivingModeMemoryNotice.RESTORED &&
                        before.memoryMessage?.notice == DrivingModeMemoryNotice.RESTORE_SENT
                    before.copy(settings = settings, settingsFailed = configuration.isFailure,
                        historyFailed = remembered.isFailure, telemetry = car, lighting = lamps,
                        rememberedMode = remembered.getOrNull(), actualMode = memory?.actual,
                        canRememberCurrent = settings != null && remembered.isSuccess &&
                            car.drivingMode?.isCurrentIn(car, now) == true,
                        restoreCandidate = memory?.restore, capabilities = capabilities, prompt = prompt,
                        restoreNeedsPark = !car.allowsDrivingModeRestore(now),
                        memoryMessage = message, outcome = if (confirmedNow) null else before.outcome)
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
                            mutable.update { it.copy(outcome = VehicleAutomationOutcome.BLOCKED, memoryMessage = null) }
                        } else if (!mutable.value.working && active == generation &&
                            decision.isCurrent(settings.headlights, car, lamps, nowNanos())) {
                            log("event=headlight_gear_action gear=${decision.gear.value.key} action=${decision.action.key} outcome=dispatching")
                            mutable.update { it.copy(working = true) }
                            val outcome = attempt { port.setHeadlight(decision) }
                            log("event=headlight_gear_action gear=${decision.gear.value.key} action=${decision.action.key} outcome=${outcome.name.lowercase()}")
                            if (active == generation) mutable.update { it.copy(working = false, outcome = outcome, memoryMessage = null) }
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
            historyFailed = false, saveFailed = false, outcome = null, memoryMessage = null) }
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
        mutable.value = before.copy(saving = true, saveFailed = false, prompt = null, outcome = null, memoryMessage = null)
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
        if (!car.allowsDrivingModeRestore(nowNanos())) {
            mutable.update { it.copy(outcome = null,
                memoryMessage = DrivingModeMemoryMessage(DrivingModeMemoryNotice.RESTORE_NEEDS_PARK, target.mode)) }
            return
        }
        if (before.working || before.saving || target.mode !in before.capabilities.drivingModes ||
            actual == null || !actual.isCurrentIn(car, nowNanos())) {
            mutable.update { it.copy(outcome = VehicleAutomationOutcome.BLOCKED, memoryMessage = null) }
            return
        }
        mutable.value = before.copy(prompt = DrivingModeRestorePrompt(target, car.sessionId, settings, actual,
            car.sessionStartedAtElapsedNanos), outcome = null, memoryMessage = null)
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
            prompt.record.identity != car.identity || !car.allowsDrivingModeRestore(nowNanos()) ||
            car.drivingMode?.isCurrentIn(car, nowNanos()) != true ||
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
                    latest.drivingMode != car.drivingMode || !latest.allowsDrivingModeRestore(nowNanos()) ||
                    latest.drivingMode?.isCurrentIn(latest, nowNanos()) != true) {
                    if (restoreOwner === owner) mutable.update { it.copy(outcome = VehicleAutomationOutcome.BLOCKED) }
                    return@launch
                }
                log("event=driving_mode_restore mode=${prompt.record.mode.key} outcome=dispatching")
                val outcome = attempt { port.restoreDrivingMode(prompt.record, latest) }
                log("event=driving_mode_restore mode=${prompt.record.mode.key} outcome=${outcome.name.lowercase()}")
                // Native receipts do not replace actual-mode history. Only feedback can save it.
                val notice = when (outcome) {
                    VehicleAutomationOutcome.REQUESTED -> DrivingModeMemoryNotice.RESTORE_SENT
                    VehicleAutomationOutcome.CONFIRMED -> DrivingModeMemoryNotice.RESTORED
                    else -> DrivingModeMemoryNotice.RESTORE_FAILED
                }
                if (restoreOwner === owner) mutable.update {
                    // The VCU feedback can arrive before this receipt; then the restore is already confirmed.
                    val arrived = notice == DrivingModeMemoryNotice.RESTORE_SENT && it.actualMode == prompt.record.mode
                    it.copy(outcome = if (arrived) null else outcome, memoryMessage = DrivingModeMemoryMessage(
                        if (arrived) DrivingModeMemoryNotice.RESTORED else notice, prompt.record.mode))
                }
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
        if (before.settings == null) return
        val car = before.telemetry ?: return
        if (before.working || before.saving || before.historyFailed) return
        val record = driving.acceptCurrent(car, nowNanos()) ?: run {
            mutable.update { it.copy(canRememberCurrent = false, outcome = VehicleAutomationOutcome.BLOCKED, memoryMessage = null) }
            return
        }
        mutable.value = before.copy(saving = true, saveFailed = false, prompt = null, outcome = null, memoryMessage = null)
        tasks.launch {
            try {
                history.save(record)
                log("event=driving_mode_memory action=save mode=${record.mode.key} outcome=saved")
                mutable.update { it.copy(saving = false,
                    memoryMessage = DrivingModeMemoryMessage(DrivingModeMemoryNotice.SAVED, record.mode)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                log("event=driving_mode_memory action=save outcome=save_failed error=${failure.javaClass.simpleName}")
                mutable.update { it.copy(saving = false, saveFailed = true) }
            }
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
