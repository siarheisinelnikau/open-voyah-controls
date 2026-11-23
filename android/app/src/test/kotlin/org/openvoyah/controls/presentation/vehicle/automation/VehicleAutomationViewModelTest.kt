package org.openvoyah.controls.presentation.vehicle.automation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.openvoyah.controls.domain.vehicle.HeadlightFeedback
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.HeadlightObservation
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.domain.vehicle.automation.DrivingMode
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationController
import org.openvoyah.controls.domain.vehicle.automation.DrivingModeHistory
import org.openvoyah.controls.domain.vehicle.automation.DrivingModeMemoryMessage
import org.openvoyah.controls.domain.vehicle.automation.DrivingModeMemoryNotice
import org.openvoyah.controls.domain.vehicle.automation.DrivingModeMemorySettings
import org.openvoyah.controls.domain.vehicle.automation.DrivingModeRecord
import org.openvoyah.controls.domain.vehicle.automation.HeadlightGearAction
import org.openvoyah.controls.domain.vehicle.automation.HeadlightGearDecision
import org.openvoyah.controls.domain.vehicle.automation.HeadlightGearSettings
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationCapabilities
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationOutcome
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationPort
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationSettings
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationStore
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.domain.vehicle.automation.VehicleIdentity
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignal
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetryWindow
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignalKind
import kotlin.coroutines.CoroutineContext

class VehicleAutomationViewModelTest {
    private val identity = VehicleIdentity("fixture", "fixture/build:1", 30, "x86_64")
    private val config = VehicleAutomationSettings(HeadlightGearSettings(true, HeadlightGearAction.LOW_BEAM), DrivingModeMemorySettings(true))
    private fun car(gear: VehicleGear, at: Long, mode: DrivingMode = DrivingMode.COMFORT) = VehicleTelemetry(identity, "trip", 1,
        gear = VehicleSignal(gear, at, 1_000, "factory_feedback", "trip"),
        speedKph = VehicleSignal(0f, at, 1_000, "factory_feedback", "trip"),
        drivingMode = VehicleSignal(mode, at, 1_000, "vcu_feedback", "trip"), unavailable = emptySet())

    @Test fun foregroundOwnerDispatchesOnceAndStopInvalidatesLiveState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = FakeStore(config)
        val history = FakeHistory()
        val telemetry = MutableStateFlow(car(VehicleGear.PARK, 100))
        val lights = MutableStateFlow(HeadlightStatus(HeadlightFeedback.Confirmed(HeadlightObservation(HeadlightMode.AUTOMATIC, 1_000), 1)))
        val port = FakePort()
        var now = 100L
        val model = VehicleAutomationViewModel(store, history, telemetry, lights, port, { now }, scope)
        try {
            model.startMonitoring()
            withTimeout(5_000) { model.state.first { it.settings != null } }
            assertEquals(0, port.lightRequests)
            now = 200
            telemetry.value = car(VehicleGear.DRIVE, 200)
            withTimeout(5_000) { model.state.first { it.outcome == VehicleAutomationOutcome.REQUESTED } }
            assertEquals(1, port.lightRequests)
            assertEquals(HeadlightMode.AUTOMATIC, model.state.value.lighting.currentObservation?.mode)
            model.stopMonitoring()
            assertNull(model.state.value.telemetry)
            model.startMonitoring()
            withTimeout(5_000) { model.state.first { it.telemetry != null } }
            assertEquals(1, port.lightRequests)
        } finally { model.stopMonitoring(); scope.cancel() }
    }

    @Test fun reconnectInvalidatesRestoreConfirmationAndReceiptsNeverSaveMode() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val remembered = DrivingModeRecord(DrivingMode.SPORT, identity, "vcu_feedback", "previous", 500)
        val history = FakeHistory(remembered)
        val telemetry = MutableStateFlow(car(VehicleGear.PARK, 100))
        val port = FakePort()
        val model = VehicleAutomationViewModel(FakeStore(config), history, telemetry, MutableStateFlow(HeadlightStatus()), port, { 100 }, scope)
        try {
            model.startMonitoring()
            withTimeout(5_000) { model.state.first { it.restoreCandidate != null } }
            model.prepareRestore()
            val prompt = model.state.value.prompt
            assertNotNull(prompt)
            telemetry.value = telemetry.value.copy(sessionId = "wake", sessionStartedAtElapsedNanos = 100)
            withTimeout(5_000) { model.state.first { it.prompt == null } }
            model.confirmRestore(prompt!!)
            assertEquals(0, port.modeRequests)
            assertEquals(remembered, history.values.value.getOrNull())
            assertEquals(0, history.saves)
        } finally { model.stopMonitoring(); scope.cancel() }
    }

    @Test fun revocationWindowBlocksConfirmationBeforeUnknownSnapshotIsCollected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val remembered = DrivingModeRecord(DrivingMode.SPORT, identity, "vcu_feedback", "previous", 500)
        val window = VehicleTelemetryWindow()
        val telemetry = MutableStateFlow(car(VehicleGear.PARK, 100).copy(confirmationWindow = window))
        val port = FakePort()
        val history = FakeHistory(remembered)
        val model = VehicleAutomationViewModel(FakeStore(config), history, telemetry, MutableStateFlow(HeadlightStatus()), port, { 100 }, scope)
        try {
            model.startMonitoring()
            withTimeout(5_000) { model.state.first { it.restoreCandidate != null } }
            model.prepareRestore()
            val prompt = checkNotNull(model.state.value.prompt)
            window.close() // Disconnect revokes a snapshot even while its invalidation event is queued.
            model.confirmRestore(prompt)
            assertNull(model.state.value.prompt)
            assertEquals(VehicleAutomationOutcome.BLOCKED, model.state.value.outcome)
            assertEquals(0, port.modeRequests)
            assertEquals(remembered, history.values.value.getOrNull())
        } finally { model.stopMonitoring(); scope.cancel() }
    }

    @Test fun actualModeLossInvalidatesPendingConfirmation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val remembered = DrivingModeRecord(DrivingMode.SPORT, identity, "vcu_feedback", "previous", 500)
        val telemetry = MutableStateFlow(car(VehicleGear.PARK, 100))
        val port = FakePort()
        val model = VehicleAutomationViewModel(FakeStore(config), FakeHistory(remembered), telemetry,
            MutableStateFlow(HeadlightStatus()), port, { 100 }, scope)
        try {
            model.startMonitoring()
            withTimeout(5_000) { model.state.first { it.restoreCandidate != null } }
            model.prepareRestore()
            val prompt = checkNotNull(model.state.value.prompt)
            telemetry.value = telemetry.value.copy(drivingMode = null, unavailable = setOf(VehicleSignalKind.DRIVING_MODE))
            withTimeout(5_000) { model.state.first { it.prompt == null && it.actualMode == null } }
            model.confirmRestore(prompt)
            assertEquals(0, port.modeRequests)
        } finally { model.stopMonitoring(); scope.cancel() }
    }

    @Test fun stoppingForegroundCancelsRestoreBeforeQueuedDispatch() {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val remembered = DrivingModeRecord(DrivingMode.SPORT, identity, "vcu_feedback", "previous", 500)
        val telemetry = MutableStateFlow(car(VehicleGear.PARK, 100))
        val port = FakePort()
        val model = VehicleAutomationViewModel(FakeStore(config), FakeHistory(remembered), telemetry,
            MutableStateFlow(HeadlightStatus()), port, { 100 }, scope)
        try {
            model.startMonitoring()
            dispatcher.runAll()
            model.prepareRestore()
            model.confirmRestore(checkNotNull(model.state.value.prompt))
            model.stopMonitoring()
            dispatcher.runAll()
            assertEquals(0, port.modeRequests)
            assertNull(model.state.value.telemetry)
        } finally { model.stopMonitoring(); scope.cancel(); dispatcher.runAll() }
    }

    @Test fun rememberCurrentPersistsASingleVerifiedObservation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val history = FakeHistory()
        val telemetry = MutableStateFlow(car(VehicleGear.PARK, 100))
        val model = VehicleAutomationViewModel(FakeStore(config), history, telemetry,
            MutableStateFlow(HeadlightStatus()), FakePort(), { 100 }, scope)
        try {
            model.startMonitoring()
            withTimeout(5_000) { model.state.first { it.canRememberCurrent } }
            model.rememberCurrent()
            withTimeout(5_000) { model.state.first { it.rememberedMode?.mode == DrivingMode.COMFORT && !it.saving } }
            assertEquals(1, history.saves)
            assertEquals(DrivingMode.COMFORT, history.values.value.getOrNull()?.mode)
        } finally { model.stopMonitoring(); scope.cancel() }
    }

    @Test fun manualSaveWorksWithTheSwitchOffAndAnUnknownGearAndSaysWhatWasSaved() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val history = FakeHistory()
        // After a wake the gear is unknown until it changes; the seeded actual mode is enough to save.
        val telemetry = MutableStateFlow(car(VehicleGear.PARK, 100).copy(gear = null))
        val off = config.copy(drivingMode = DrivingModeMemorySettings(false))
        val model = VehicleAutomationViewModel(FakeStore(off), history, telemetry,
            MutableStateFlow(HeadlightStatus()), FakePort(), { 100 }, scope)
        try {
            model.startMonitoring()
            withTimeout(5_000) { model.state.first { it.canRememberCurrent } }
            model.rememberCurrent()
            val saved = withTimeout(5_000) { model.state.first { it.memoryMessage != null && !it.saving } }
            assertEquals(DrivingModeMemoryMessage(DrivingModeMemoryNotice.SAVED, DrivingMode.COMFORT), saved.memoryMessage)
            assertEquals(DrivingMode.COMFORT, history.values.value.getOrNull()?.mode)
            assertNull(saved.restoreCandidate) // The saved mode is the one already on.
        } finally { model.stopMonitoring(); scope.cancel() }
    }

    @Test fun restoreIsReportedRestoredOnlyWhenTheCarReportsTheSavedMode() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val remembered = DrivingModeRecord(DrivingMode.SPORT, identity, "vcu_feedback", "previous", 500)
        val telemetry = MutableStateFlow(car(VehicleGear.PARK, 100).copy(gear = null))
        val port = FakePort()
        var now = 100L
        val off = config.copy(drivingMode = DrivingModeMemorySettings(false))
        val model = VehicleAutomationViewModel(FakeStore(off), FakeHistory(remembered), telemetry,
            MutableStateFlow(HeadlightStatus()), port, { now }, scope)
        try {
            model.startMonitoring()
            withTimeout(5_000) { model.state.first { it.restoreCandidate != null } }
            model.prepareRestore()
            model.confirmRestore(checkNotNull(model.state.value.prompt))
            assertEquals(1, port.modeRequests)
            assertEquals(DrivingModeMemoryMessage(DrivingModeMemoryNotice.RESTORE_SENT, DrivingMode.SPORT), model.state.value.memoryMessage)
            now = 200
            telemetry.value = car(VehicleGear.PARK, 200, DrivingMode.SPORT).copy(gear = null)
            val restored = withTimeout(5_000) { model.state.first { it.memoryMessage?.notice == DrivingModeMemoryNotice.RESTORED } }
            assertEquals(DrivingMode.SPORT, restored.memoryMessage?.mode)
            assertNull(restored.outcome)
        } finally { model.stopMonitoring(); scope.cancel() }
    }

    @Test fun restoreWaitsInDriveAndSaysSo() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val remembered = DrivingModeRecord(DrivingMode.SPORT, identity, "vcu_feedback", "previous", 500)
        val telemetry = MutableStateFlow(car(VehicleGear.PARK, 100))
        val port = FakePort()
        var now = 100L
        val model = VehicleAutomationViewModel(FakeStore(config), FakeHistory(remembered), telemetry,
            MutableStateFlow(HeadlightStatus()), port, { now }, scope)
        try {
            model.startMonitoring()
            withTimeout(5_000) { model.state.first { it.restoreCandidate != null } }
            now = 200
            telemetry.value = car(VehicleGear.DRIVE, 200)
            val driving = withTimeout(5_000) { model.state.first { it.restoreNeedsPark } }
            assertNull(driving.restoreCandidate)
            model.prepareRestore()
            assertNull(model.state.value.prompt)
            assertEquals(0, port.modeRequests)
        } finally { model.stopMonitoring(); scope.cancel() }
    }

    @Test fun confirmedRestoreDispatchesOnceForAnUnchangedPrompt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val remembered = DrivingModeRecord(DrivingMode.SPORT, identity, "vcu_feedback", "previous", 500)
        val telemetry = MutableStateFlow(car(VehicleGear.PARK, 100))
        val port = FakePort()
        val model = VehicleAutomationViewModel(FakeStore(config), FakeHistory(remembered), telemetry,
            MutableStateFlow(HeadlightStatus()), port, { 100 }, scope)
        try {
            model.startMonitoring()
            withTimeout(5_000) { model.state.first { it.restoreCandidate != null } }
            model.prepareRestore()
            val prompt = checkNotNull(model.state.value.prompt)
            model.confirmRestore(prompt)
            assertEquals(1, port.modeRequests)
            assertEquals(VehicleAutomationOutcome.REQUESTED, model.state.value.outcome)
            assertNull(model.state.value.prompt)
            assertFalse(model.state.value.working)
            model.confirmRestore(prompt)
            assertEquals(1, port.modeRequests)
        } finally { model.stopMonitoring(); scope.cancel() }
    }

    @Test fun blockedRestoreReportsThePortOutcomeWithoutReplay() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val remembered = DrivingModeRecord(DrivingMode.SPORT, identity, "vcu_feedback", "previous", 500)
        val telemetry = MutableStateFlow(car(VehicleGear.PARK, 100))
        val port = FakePort(modeOutcome = VehicleAutomationOutcome.BLOCKED)
        val model = VehicleAutomationViewModel(FakeStore(config), FakeHistory(remembered), telemetry,
            MutableStateFlow(HeadlightStatus()), port, { 100 }, scope)
        try {
            model.startMonitoring()
            withTimeout(5_000) { model.state.first { it.restoreCandidate != null } }
            model.prepareRestore()
            val prompt = checkNotNull(model.state.value.prompt)
            model.confirmRestore(prompt)
            assertEquals(1, port.modeRequests)
            assertEquals(VehicleAutomationOutcome.BLOCKED, model.state.value.outcome)
            assertNull(model.state.value.prompt)
        } finally { model.stopMonitoring(); scope.cancel() }
    }

    @Test fun unqualifiedHeadlightActionIsReportedBlockedAndConsumed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val telemetry = MutableStateFlow(car(VehicleGear.PARK, 100))
        val lights = MutableStateFlow(HeadlightStatus(HeadlightFeedback.Confirmed(
            HeadlightObservation(HeadlightMode.AUTOMATIC, 1_000), 1)))
        val port = FakePort(capabilities = VehicleAutomationCapabilities())
        var now = 100L
        val model = VehicleAutomationViewModel(FakeStore(config), FakeHistory(), telemetry, lights, port, { now }, scope)
        try {
            model.startMonitoring()
            withTimeout(5_000) { model.state.first { it.settings != null } }
            now = 200
            telemetry.value = car(VehicleGear.DRIVE, 200)
            withTimeout(5_000) { model.state.first { it.outcome == VehicleAutomationOutcome.BLOCKED } }
            assertEquals(0, port.lightRequests)
            telemetry.value = car(VehicleGear.DRIVE, 250)
            withTimeout(5_000) { model.state.first { it.telemetry?.gear?.observedAtElapsedNanos == 250L } }
            assertEquals(0, port.lightRequests)
        } finally { model.stopMonitoring(); scope.cancel() }
    }

    @Test fun newerActualModeCancelsConsentInsteadOfOverridingDriverChoice() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val remembered = DrivingModeRecord(DrivingMode.SPORT, identity, "vcu_feedback", "previous", 500)
        val telemetry = MutableStateFlow(car(VehicleGear.PARK, 100))
        val port = FakePort()
        var now = 100L
        val model = VehicleAutomationViewModel(FakeStore(config), FakeHistory(remembered), telemetry,
            MutableStateFlow(HeadlightStatus()), port, { now }, scope)
        try {
            model.startMonitoring()
            withTimeout(5_000) { model.state.first { it.restoreCandidate != null } }
            model.prepareRestore()
            val prompt = checkNotNull(model.state.value.prompt)
            now = 200
            telemetry.value = car(VehicleGear.PARK, 200, DrivingMode.ECO)
            withTimeout(5_000) { model.state.first { it.prompt == null && it.actualMode == DrivingMode.ECO } }
            model.confirmRestore(prompt)
            assertEquals(0, port.modeRequests)
        } finally { model.stopMonitoring(); scope.cancel() }
    }

    @Test fun closingConfirmationOwnerRevokesConsentWhileSharedAutomaticOwnerKeepsObserving() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val remembered = DrivingModeRecord(DrivingMode.SPORT, identity, "vcu_feedback", "previous", 500)
        val telemetry = MutableStateFlow(car(VehicleGear.PARK, 100))
        val lights = MutableStateFlow(HeadlightStatus(HeadlightFeedback.Confirmed(
            HeadlightObservation(HeadlightMode.AUTOMATIC, 1_000), 1)))
        val port = FakePort()
        var now = 100L
        val controller = VehicleAutomationController(FakeStore(config), FakeHistory(remembered), telemetry,
            lights, port, { now }, scope)
        try {
            controller.startMonitoring()
            withTimeout(5_000) { controller.state.first { it.restoreCandidate != null } }
            controller.prepareRestore()
            val oldConsent = checkNotNull(controller.state.value.prompt)
            controller.cancelManualActions()
            assertNull(controller.state.value.prompt)
            assertNotNull(controller.state.value.telemetry)
            controller.confirmRestore(oldConsent)
            assertEquals(0, port.modeRequests)
            now = 200
            telemetry.value = car(VehicleGear.DRIVE, 200)
            withTimeout(5_000) { controller.state.first { it.outcome == VehicleAutomationOutcome.REQUESTED } }
            assertEquals(1, port.lightRequests)
            controller.startMonitoring()
            assertEquals(1, port.lightRequests)
        } finally { controller.stopMonitoring(); scope.cancel() }
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        fun runAll() {
            var count = 0
            while (work.isNotEmpty()) {
                check(count++ < 1_000) { "Unexpected busy coroutine loop" }
                work.removeFirst().run()
            }
        }
    }

    private class FakeStore(value: VehicleAutomationSettings) : VehicleAutomationStore {
        private val values = MutableStateFlow(Result.success(value))
        override fun observe() = values
        override suspend fun update(expected: VehicleAutomationSettings, replacement: VehicleAutomationSettings) {
            check(values.value.getOrThrow() == expected)
            values.value = Result.success(replacement)
        }
    }
    private class FakeHistory(value: DrivingModeRecord? = null) : DrivingModeHistory {
        val values = MutableStateFlow(Result.success(value))
        var saves = 0
        override fun observe() = values
        override suspend fun save(record: DrivingModeRecord) { saves++; values.value = Result.success(record) }
    }
    private class FakePort(
        private val capabilities: VehicleAutomationCapabilities =
            VehicleAutomationCapabilities(HeadlightGearAction.entries.toSet(), DrivingMode.entries.toSet()),
        private val modeOutcome: VehicleAutomationOutcome = VehicleAutomationOutcome.REQUESTED,
    ) : VehicleAutomationPort {
        var lightRequests = 0
        var modeRequests = 0
        override fun capabilities(identity: VehicleIdentity?) = capabilities
        override suspend fun setHeadlight(decision: HeadlightGearDecision): VehicleAutomationOutcome {
            lightRequests++
            return VehicleAutomationOutcome.REQUESTED
        }
        override suspend fun restoreDrivingMode(record: DrivingModeRecord, telemetry: VehicleTelemetry): VehicleAutomationOutcome {
            modeRequests++
            return modeOutcome
        }
    }
}
