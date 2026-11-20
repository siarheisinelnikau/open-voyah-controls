package org.openvoyah.controls.presentation.climate

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openvoyah.controls.domain.climate.ClimateApplyResult
import org.openvoyah.controls.domain.climate.ClimateApplyStatus
import org.openvoyah.controls.domain.climate.ClimateConditionState
import org.openvoyah.controls.domain.climate.ClimateSeason
import org.openvoyah.controls.domain.climate.SeasonalClimateChanged
import org.openvoyah.controls.domain.climate.SeasonalClimatePlan
import org.openvoyah.controls.domain.climate.SeasonalClimatePort
import org.openvoyah.controls.domain.climate.SeasonalClimateSettings
import org.openvoyah.controls.domain.climate.SeasonalClimateStore
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoorState
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.domain.vehicle.automation.VehicleIdentity
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignal
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetrySession

class SeasonalClimateViewModelTest {
    @Test fun aConfirmationExpiresBeforeDispatchAndDoesNotReplayWhenTelemetryReturns() = runBlocking {
        val fixture = Fixture()
        val ui = launch { fixture.model.state.collect() }
        try {
            fixture.ready()
            fixture.model.requestApply(ClimateSeason.SUMMER)
            withTimeout(3_000) { fixture.model.state.first { it.confirmation != null } }
            fixture.clock.addAndGet(5_000_000_001)
            fixture.model.confirmApply()
            withTimeout(3_000) { fixture.model.state.first { it.form.pending == null } }
            assertEquals(0, fixture.port.calls.value)
            fixture.telemetry.value = telemetry(fixture.clock.get())
            fixture.ready()
            assertEquals(0, fixture.port.calls.value)
            assertNull(fixture.model.state.value.confirmation)
        } finally {
            ui.cancel()
            fixture.tasks.cancel()
        }
    }

    @Test fun changedSettingsInvalidateAConfirmationAndAnAcknowledgmentIsNotTelemetry() = runBlocking {
        val fixture = Fixture()
        val ui = launch { fixture.model.state.collect() }
        try {
            fixture.ready()
            fixture.model.requestApply(ClimateSeason.SUMMER)
            withTimeout(3_000) { fixture.model.state.first { it.confirmation != null } }
            val previous = fixture.store.value.value.getOrThrow()
            val changed = previous.copy(summer = previous.summer.copy(fanLevel = 4))
            fixture.store.value.value = Result.success(changed)
            withTimeout(3_000) { fixture.model.state.first { it.settings == changed } }
            fixture.model.confirmApply()
            assertEquals(0, fixture.port.calls.value)
            fixture.model.requestApply(ClimateSeason.SUMMER)
            withTimeout(3_000) { fixture.model.state.first { it.confirmation != null } }
            fixture.model.confirmApply()
            withTimeout(3_000) { fixture.model.state.first { it.form.lastResult?.status == ClimateApplyStatus.ACCEPTED } }
            assertEquals(1, fixture.port.calls.value)
            fixture.telemetry.value = telemetry(fixture.clock.incrementAndGet())
            fixture.ready()
            assertEquals(1, fixture.port.calls.value)
        } finally {
            ui.cancel()
            fixture.tasks.cancel()
        }
    }

    @Test fun reusingASessionIdAfterReconnectInvalidatesThePendingConfirmation() = runBlocking {
        val fixture = Fixture()
        val ui = launch { fixture.model.state.collect() }
        try {
            fixture.ready()
            fixture.model.requestApply(ClimateSeason.SUMMER)
            withTimeout(3_000) { fixture.model.state.first { it.confirmation != null } }
            val initial = fixture.telemetry.value
            fixture.telemetry.value = initial.copy(sessionStartedAtElapsedNanos = initial.sessionStartedAtElapsedNanos + 1)
            withTimeout(3_000) { fixture.model.state.first { it.telemetry != initial && it.confirmation == null } }
            fixture.model.confirmApply()
            assertEquals(0, fixture.port.calls.value)
            assertNull(fixture.model.state.value.confirmation)
        } finally {
            ui.cancel()
            fixture.tasks.cancel()
        }
    }

    @Test fun missingCapabilityKeepsRequestsClosedWhileSettingsRemainEditable() = runBlocking {
        val fixture = Fixture(approved = false)
        val ui = launch { fixture.model.state.collect() }
        try {
            fixture.ready()
            fixture.model.requestApply(ClimateSeason.SUMMER)
            assertNull(fixture.model.state.value.confirmation)
            assertEquals(0, fixture.port.calls.value)
            val previous = fixture.store.value.value.getOrThrow().summer
            fixture.model.savePreset(ClimateSeason.SUMMER, previous.copy(driverCelsius = 21))
            withTimeout(3_000) { fixture.model.state.first { it.settings?.summer?.driverCelsius == 21 && !it.form.saving } }
            assertEquals(0, fixture.port.calls.value)
        } finally {
            ui.cancel()
            fixture.tasks.cancel()
        }
    }

    @Test fun applyFailureIsReportedAsUnconfirmedWithoutReplay() = runBlocking {
        val fixture = Fixture(failure = IllegalStateException("transport lost"))
        val ui = launch { fixture.model.state.collect() }
        try {
            fixture.ready()
            fixture.model.requestApply(ClimateSeason.SUMMER)
            withTimeout(3_000) { fixture.model.state.first { it.confirmation != null } }
            fixture.model.confirmApply()
            withTimeout(3_000) { fixture.model.state.first { it.form.lastResult?.status == ClimateApplyStatus.UNCONFIRMED } }
            assertEquals(1, fixture.port.calls.value)
            fixture.telemetry.value = telemetry(fixture.clock.incrementAndGet())
            fixture.ready()
            assertEquals(1, fixture.port.calls.value)
        } finally {
            ui.cancel()
            fixture.tasks.cancel()
        }
    }

    @Test fun aChangedStorePreservesThePreviousPresetAndReportsTheConflict() = runBlocking {
        val fixture = Fixture()
        val ui = launch { fixture.model.state.collect() }
        try {
            fixture.ready()
            val previous = fixture.store.value.value.getOrThrow()
            fixture.store.conflict = true
            fixture.model.savePreset(ClimateSeason.SUMMER, previous.summer.copy(driverCelsius = 21))
            withTimeout(3_000) { fixture.model.state.first { it.form.changed && !it.form.saving } }
            assertEquals(previous, fixture.store.value.value.getOrThrow())
            assertEquals(0, fixture.port.calls.value)
        } finally {
            ui.cancel()
            fixture.tasks.cancel()
        }
    }

    private class Fixture(approved: Boolean = true, private val failure: Exception? = null) {
        val clock = AtomicLong(20_000_000_000L)
        val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val telemetry = MutableStateFlow(telemetry(clock.get()))
        val store = FakeStore()
        val port = FakePort(approved, failure)
        val model = SeasonalClimateViewModel(store, telemetry, port, clock::get, tasks)
        suspend fun ready() = withTimeout(3_000) {
            model.state.first { it.conditions[ClimateSeason.SUMMER] == ClimateConditionState.READY }
        }
    }

    private class FakeStore : SeasonalClimateStore {
        val value = MutableStateFlow(Result.success(SeasonalClimateSettings().let { it.copy(summer = it.summer.copy(enabled = true)) }))
        var conflict = false
        override fun observe() = value
        override suspend fun update(expected: SeasonalClimateSettings, replacement: SeasonalClimateSettings) {
            if (conflict || value.value.getOrThrow() != expected) throw SeasonalClimateChanged()
            value.value = Result.success(replacement)
        }
    }

    private class FakePort(private val available: Boolean, private val failure: Exception? = null) : SeasonalClimatePort {
        val calls = MutableStateFlow(0)
        override fun approved(plan: SeasonalClimatePlan) = available
        override suspend fun apply(plan: SeasonalClimatePlan, session: VehicleTelemetrySession): ClimateApplyResult {
            calls.value++
            failure?.let { throw it }
            return ClimateApplyResult(ClimateApplyStatus.ACCEPTED, plan.commands.size)
        }
    }

    companion object {
        private fun telemetry(now: Long): VehicleTelemetry {
            fun <T> signal(value: T) = VehicleSignal(value, now, 1_000, "fixture", "current")
            return VehicleTelemetry(VehicleIdentity("fixture", "fixture/build", 30, "x86_64"), "current", now - 1,
                gear = signal(VehicleGear.PARK), speedKph = signal(0f), outsideCelsius = signal(25f),
                doors = VehicleDoor.entries.associateWith { signal(VehicleDoorState.CLOSED) }, unavailable = emptySet())
        }
    }
}
