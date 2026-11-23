package org.openvoyah.controls.data.climate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.climate.ClimateApplyGuard
import org.openvoyah.controls.domain.climate.ClimateApplyResult
import org.openvoyah.controls.domain.climate.ClimateApplyStatus
import org.openvoyah.controls.domain.climate.ClimateSeason
import org.openvoyah.controls.domain.climate.SeasonalClimatePlan
import org.openvoyah.controls.domain.climate.SeasonalClimatePreset
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.domain.vehicle.automation.VehicleIdentity
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignal
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetrySession
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetryWindow
import org.openvoyah.controls.domain.voice.VoiceOutcome

class SeasonalClimateDispatchTest {
    private val now = 20_000_000_000L
    private val plan = SeasonalClimatePlan(ClimateSeason.SUMMER,
        SeasonalClimatePreset(enabled = true, outsideThresholdCelsius = 24, requireDoorsClosed = false))

    @Test fun reconnectBeforeFirstSendDoesNotUseNewSessionsReadings() = runBlocking {
        val original = ready("original")
        var current = original
        val guard = ClimateApplyGuard()
        val blocked = sendSeasonalClimatePlan(plan, VehicleTelemetrySession(original), { current }, { now }, guard,
            readPreset = { current = ready("replacement"); plan.preset }) { _, _ ->
            error("An old decision must not send on a new session")
        }
        assertEquals(ClimateApplyResult(ClimateApplyStatus.BLOCKED), blocked)
        assertTrue(guard.tryReserve(plan, now))
    }

    @Test fun reconnectAfterOneAcceptedCommandStopsTheRestOfTheGroup() = runBlocking {
        val original = ready("original")
        var current = original
        val sent = mutableListOf<String>()
        val result = sendSeasonalClimatePlan(plan, VehicleTelemetrySession(original), { current }, { now }, ClimateApplyGuard(),
            readPreset = { plan.preset }) { command, stillAllowed ->
            assertTrue(stillAllowed())
            sent += command.id
            current = ready("replacement")
            VoiceOutcome.VEHICLE_ACCEPTED
        }
        assertEquals(listOf("SetDriverTemperature"), sent)
        assertEquals(ClimateApplyResult(ClimateApplyStatus.PARTIAL, 1), result)
    }

    @Test fun lossAfterPreflightIsSeenAtPublishEvenBeforeSnapshotReplacement() = runBlocking {
        val original = ready("original")
        val sent = mutableListOf<String>()
        val result = sendSeasonalClimatePlan(plan, VehicleTelemetrySession(original), { original }, { now }, ClimateApplyGuard(),
            readPreset = { plan.preset }) { command, stillAllowed ->
            if (sent.isNotEmpty()) original.confirmationWindow!!.close()
            if (stillAllowed()) {
                sent += command.id
                VoiceOutcome.VEHICLE_ACCEPTED
            } else VoiceOutcome.VEHICLE_BLOCKED
        }
        assertEquals(listOf("SetDriverTemperature"), sent)
        assertEquals(ClimateApplyResult(ClimateApplyStatus.PARTIAL, 1), result)
    }

    private fun ready(session: String): VehicleTelemetry {
        fun <T> sample(value: T) = VehicleSignal(value, now, 1_000, "fixture", session)
        return VehicleTelemetry(VehicleIdentity("fixture", "fixture/build", 30, "x86_64"), session, now - 1,
            gear = sample(VehicleGear.PARK), speedKph = sample(0f), outsideCelsius = sample(26f),
            unavailable = emptySet(), confirmationWindow = VehicleTelemetryWindow())
    }
}
