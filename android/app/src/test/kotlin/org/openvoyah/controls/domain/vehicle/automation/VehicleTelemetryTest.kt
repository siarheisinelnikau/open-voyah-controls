package org.openvoyah.controls.domain.vehicle.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleTelemetryTest {
    private val identity = VehicleIdentity("fixture", "fixture/build", 30, "x86_64")
    private val telemetry = VehicleTelemetry(identity, "current", 1_000_000_000)
    private fun gear(at: Long = 2_000_000_000, session: String = "current") =
        VehicleSignal(VehicleGear.PARK, at, 1_700_000_000_000, "fixture_controller", session)

    @Test fun reconnectAndOldControllerSamplesCannotBecomeCurrent() {
        assertTrue(gear().isFreshFor(telemetry, 2_500_000_000))
        assertFalse(gear(session = "previous").isFreshFor(telemetry, 2_500_000_000))
        assertFalse(gear(at = 999_999_999).isFreshFor(telemetry, 2_500_000_000))
        assertFalse(gear().isFreshFor(telemetry, 1_999_999_999))
        assertFalse(gear().isFreshFor(telemetry, 7_000_000_001))
    }

    @Test fun stationaryRequiresTwoIndependentFreshSignalsAndNoUnavailableFlag() {
        val speed = VehicleSignal(0f, 2_000_000_000, 1_700_000_000_000, "fixture_speed", "current")
        val stationary = telemetry.copy(gear = gear(), speedKph = speed,
            unavailable = telemetry.unavailable - VehicleSignalKind.GEAR - VehicleSignalKind.SPEED)
        assertTrue(stationary.isStationary(2_500_000_000))
        assertFalse(stationary.copy(gear = null).isStationary(2_500_000_000))
        assertFalse(stationary.copy(speedKph = null).isStationary(2_500_000_000))
        assertFalse(stationary.copy(unavailable = stationary.unavailable + VehicleSignalKind.SPEED).isStationary(2_500_000_000))
        assertFalse(stationary.copy(speedKph = speed.copy(value = .1f)).isStationary(2_500_000_000))
        assertFalse(stationary.copy(speedKph = speed.copy(value = Float.NaN)).isStationary(2_500_000_000))
        assertFalse(stationary.copy(gear = gear().copy(value = VehicleGear.DRIVE)).isStationary(2_500_000_000))
        assertFalse(stationary.isStationary(7_000_000_001))
    }

    @Test fun disconnectRevokesAnAlreadyQueuedSnapshotBeforeTheLossIsCollected() {
        val window = VehicleTelemetryWindow()
        val snapshot = telemetry.copy(confirmationWindow = window)
        assertTrue(gear().isFreshFor(snapshot, 2_500_000_000))
        window.close()
        assertFalse(gear().isFreshFor(snapshot, 2_500_000_000))
    }
}
