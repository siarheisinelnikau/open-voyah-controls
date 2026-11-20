package org.openvoyah.controls.domain.vehicle.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.openvoyah.controls.domain.vehicle.HeadlightFeedback
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.HeadlightObservation
import org.openvoyah.controls.domain.vehicle.HeadlightStatus

class HeadlightGearPolicyTest {
    private val identity = VehicleIdentity("fixture", "fixture/build:1", 30, "x86_64")
    private val settings = HeadlightGearSettings(true, HeadlightGearAction.LOW_BEAM, HeadlightGearAction.OFF)
    private val lights = HeadlightStatus(HeadlightFeedback.Confirmed(HeadlightObservation(HeadlightMode.AUTOMATIC, 1_000), 1))
    private fun telemetry(gear: VehicleGear, at: Long, session: String = "trip", speed: Float? = 0f) = VehicleTelemetry(
        identity, session, 1,
        gear = VehicleSignal(gear, at, 1_000, "factory_feedback", session),
        speedKph = speed?.let { VehicleSignal(it, at, 1_000, "factory_feedback", session) },
        unavailable = emptySet(),
    )

    @Test fun initialDriveAndRepeatedFeedbackNeverAct() {
        val engine = HeadlightGearEngine()
        val car = telemetry(VehicleGear.DRIVE, 1_000)
        assertNull(engine.evaluate(settings, car, lights, 1_000))
        assertNull(engine.evaluate(settings, car, lights, 2_000))
        assertNull(engine.evaluate(settings, telemetry(VehicleGear.DRIVE, 3_000), lights, 3_000))
    }

    @Test fun freshTransitionReservesOneRequestBeforeReply() {
        val engine = HeadlightGearEngine()
        engine.evaluate(settings, telemetry(VehicleGear.PARK, 1_000), lights, 1_000)
        val drive = telemetry(VehicleGear.DRIVE, 2_000)
        assertEquals(HeadlightGearAction.LOW_BEAM, engine.evaluate(settings, drive, lights, 2_000)?.action)
        assertNull(engine.evaluate(settings, drive, lights, 3_000))
        assertNull(engine.evaluate(settings, telemetry(VehicleGear.DRIVE, 4_000), lights, 4_000))
    }

    @Test fun offRequiresFreshZeroSpeedAndNeverReplaysAfterLateSpeed() {
        val engine = HeadlightGearEngine()
        engine.evaluate(settings, telemetry(VehicleGear.DRIVE, 1_000), lights, 1_000)
        val parkWithoutSpeed = telemetry(VehicleGear.PARK, 2_000, speed = null)
        assertNull(engine.evaluate(settings, parkWithoutSpeed, lights, 2_000))
        assertNull(engine.evaluate(settings, telemetry(VehicleGear.PARK, 2_000), lights, 3_000))
        assertNull(engine.evaluate(settings, telemetry(VehicleGear.PARK, 4_000), lights, 4_000))
    }

    @Test fun historyCanBeDisplayedButNeverAuthorizesARequest() {
        val engine = HeadlightGearEngine()
        engine.evaluate(settings, telemetry(VehicleGear.PARK, 1_000), lights, 1_000)
        val car = telemetry(VehicleGear.DRIVE, 2_000)
        val history = HeadlightStatus(lastConfirmed = HeadlightObservation(HeadlightMode.AUTOMATIC, 1_000))
        assertNull(engine.evaluate(settings, car, history, 2_000))
        assertNull(engine.evaluate(settings, car, lights, 3_000))
    }

    @Test fun positionLampRequiresFreshZeroSpeedAndConsumesTheEdgeWhenSpeedIsMissing() {
        val engine = HeadlightGearEngine()
        val position = settings.copy(onPark = HeadlightGearAction.POSITION)
        engine.evaluate(position, telemetry(VehicleGear.DRIVE, 1_000), lights, 1_000)
        assertNull(engine.evaluate(position, telemetry(VehicleGear.PARK, 2_000, speed = null), lights, 2_000))
        assertNull(engine.evaluate(position, telemetry(VehicleGear.PARK, 3_000), lights, 3_000))

        val next = HeadlightGearEngine()
        next.evaluate(position, telemetry(VehicleGear.DRIVE, 1_000), lights, 1_000)
        val decision = next.evaluate(position, telemetry(VehicleGear.PARK, 2_000), lights, 2_000)!!
        val moving = telemetry(VehicleGear.PARK, 2_000, speed = 1f)
        assertFalse(decision.isCurrent(position, moving, lights, 2_000))
    }

    @Test fun reconnectConfigurationChangeAndStaleGapOnlySeed() {
        val engine = HeadlightGearEngine()
        engine.evaluate(settings, telemetry(VehicleGear.PARK, 1_000), lights, 1_000)
        assertNull(engine.evaluate(settings, telemetry(VehicleGear.DRIVE, 2_000, "wake"), lights, 2_000))
        val changed = settings.copy(onPark = HeadlightGearAction.POSITION)
        assertNull(engine.evaluate(changed, telemetry(VehicleGear.PARK, 3_000, "wake"), lights, 3_000))
        assertNull(engine.evaluate(changed, telemetry(VehicleGear.PARK, 3_000, "wake"), lights, 6_000_004_000))
        assertNull(engine.evaluate(changed, telemetry(VehicleGear.DRIVE, 6_000_005_000, "wake"), lights, 6_000_005_000))
    }

    @Test fun disabledConfigurationNeverActs() {
        val engine = HeadlightGearEngine()
        val disabled = settings.copy(enabled = false)
        engine.evaluate(disabled, telemetry(VehicleGear.PARK, 1_000), lights, 1_000)
        assertNull(engine.evaluate(disabled, telemetry(VehicleGear.DRIVE, 2_000), lights, 2_000))
    }

    @Test fun matchingCurrentLightSuppressesRequest() {
        val engine = HeadlightGearEngine()
        val lowBeam = HeadlightStatus(HeadlightFeedback.Confirmed(HeadlightObservation(HeadlightMode.LOW_BEAM, 1_000), 1))
        engine.evaluate(settings, telemetry(VehicleGear.PARK, 1_000), lowBeam, 1_000)
        assertNull(engine.evaluate(settings, telemetry(VehicleGear.DRIVE, 2_000), lowBeam, 2_000))
    }

    @Test fun secondGearEdgeWithinMinimumIntervalIsConsumed() {
        val engine = HeadlightGearEngine()
        engine.evaluate(settings, telemetry(VehicleGear.PARK, 1_000_000_000), lights, 1_000_000_000)
        assertEquals(HeadlightGearAction.LOW_BEAM,
            engine.evaluate(settings, telemetry(VehicleGear.DRIVE, 2_000_000_000), lights, 2_000_000_000)?.action)
        assertNull(engine.evaluate(settings, telemetry(VehicleGear.PARK, 2_500_000_000), lights, 2_500_000_000))
        assertNull(engine.evaluate(settings, telemetry(VehicleGear.DRIVE, 3_000_000_000), lights, 3_000_000_000))
        assertEquals(HeadlightGearAction.OFF,
            engine.evaluate(settings, telemetry(VehicleGear.PARK, 5_000_000_000), lights, 5_000_000_000)?.action)
    }

    @Test fun resetReleasesTheThrottleForANewSession() {
        val engine = HeadlightGearEngine()
        engine.evaluate(settings, telemetry(VehicleGear.PARK, 1_000_000_000), lights, 1_000_000_000)
        assertNotNull(engine.evaluate(settings, telemetry(VehicleGear.DRIVE, 2_000_000_000), lights, 2_000_000_000))
        engine.reset()
        engine.evaluate(settings, telemetry(VehicleGear.PARK, 2_500_000_000), lights, 2_500_000_000)
        assertEquals(HeadlightGearAction.LOW_BEAM,
            engine.evaluate(settings, telemetry(VehicleGear.DRIVE, 2_600_000_000), lights, 2_600_000_000)?.action)
    }

    @Test fun decisionRechecksExactIdentityAndPark() {
        val engine = HeadlightGearEngine()
        engine.evaluate(settings, telemetry(VehicleGear.DRIVE, 1_000), lights, 1_000)
        val car = telemetry(VehicleGear.PARK, 2_000)
        val decision = engine.evaluate(settings, car, lights, 2_000)
        assertNotNull(decision)
        assertFalse(decision!!.isCurrent(settings, car.copy(identity = identity.copy(fingerprint = "other")), lights, 2_000))
        assertFalse(decision.isCurrent(settings, car.copy(sessionStartedAtElapsedNanos = 2), lights, 2_000))
        assertFalse(decision.isCurrent(settings, car.copy(speedKph = car.speedKph!!.copy(value = 1f)), lights, 2_000))
        assertFalse(decision.isCurrent(settings, car, lights, 6_000_003_000))
    }

    @Test fun reusedSessionIdWithNewStartOnlySeedsTheReconnectedGear() {
        val engine = HeadlightGearEngine()
        engine.evaluate(settings, telemetry(VehicleGear.PARK, 100), lights, 100)
        val reconnect = telemetry(VehicleGear.DRIVE, 200).copy(sessionStartedAtElapsedNanos = 150)
        assertNull(engine.evaluate(settings, reconnect, lights, 200))
    }

    @Test(expected = IllegalArgumentException::class) fun cannotConfigureLightsOffInDrive() {
        HeadlightGearSettings(onDrive = HeadlightGearAction.OFF)
    }

    @Test(expected = IllegalArgumentException::class) fun positionLampRequiresParkInTheObservedNativePlan() {
        HeadlightGearSettings(onDrive = HeadlightGearAction.POSITION)
    }
}
