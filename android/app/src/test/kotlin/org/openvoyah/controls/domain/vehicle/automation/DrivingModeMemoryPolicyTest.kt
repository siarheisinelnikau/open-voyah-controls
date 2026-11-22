package org.openvoyah.controls.domain.vehicle.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrivingModeMemoryPolicyTest {
    private val identity = VehicleIdentity("fixture", "fixture/build:1", 30, "x86_64")
    private val enabled = DrivingModeMemorySettings(true)
    private val previous = DrivingModeRecord(DrivingMode.SPORT, identity, "vcu_feedback", "previous", 500)
    private fun car(mode: DrivingMode?, gear: VehicleGear = VehicleGear.PARK, at: Long = 1_000, session: String = "trip") = VehicleTelemetry(
        identity, session, 1,
        gear = VehicleSignal(gear, at, 1_000, "factory_feedback", session),
        speedKph = VehicleSignal(0f, at, 1_000, "factory_feedback", session),
        drivingMode = mode?.let { VehicleSignal(it, at, 1_000, "vcu_feedback", session) },
        unavailable = emptySet(),
    )

    @Test fun startupResetPreservesPreviousTripAndOffersOnlyParkedRestore() {
        val engine = DrivingModeMemoryEngine()
        val initial = engine.evaluate(enabled, previous, car(DrivingMode.COMFORT), 1_000)
        assertNull(initial.save)
        assertEquals(previous, initial.restore)
        val departure = engine.evaluate(enabled, previous, car(DrivingMode.COMFORT, VehicleGear.DRIVE, 2_000), 2_000)
        assertNull(departure.save)
        assertNull(departure.restore)
        assertNull(engine.evaluate(enabled, previous, car(DrivingMode.COMFORT, VehicleGear.DRIVE, 3_000), 3_000).save)
    }

    @Test fun actualModeChangeDuringTripReplacesPreviousRecord() {
        val engine = DrivingModeMemoryEngine()
        engine.evaluate(enabled, previous, car(DrivingMode.COMFORT), 1_000)
        engine.evaluate(enabled, previous, car(DrivingMode.COMFORT, VehicleGear.DRIVE, 2_000), 2_000)
        val next = engine.evaluate(enabled, previous, car(DrivingMode.ECO, VehicleGear.DRIVE, 3_000), 3_000).save
        assertEquals(DrivingMode.ECO, next?.mode)
        assertEquals("vcu_feedback", next?.sourceId)
        assertEquals(identity, next?.identity)
    }

    @Test fun completedTripSavesActualDrivenModeEvenIfPreviousRestoreWasSkipped() {
        val engine = DrivingModeMemoryEngine()
        engine.evaluate(enabled, previous, car(DrivingMode.COMFORT), 1_000)
        engine.evaluate(enabled, previous, car(DrivingMode.COMFORT, VehicleGear.DRIVE, 2_000), 2_000)
        val parked = engine.evaluate(enabled, previous, car(DrivingMode.COMFORT, VehicleGear.PARK, 3_000), 3_000)
        assertEquals(DrivingMode.COMFORT, parked.save?.mode)
        assertNull(parked.restore)
    }

    @Test fun openingMidTripWaitsUntilConfirmedArrivalInPark() {
        val engine = DrivingModeMemoryEngine()
        assertNull(engine.evaluate(enabled, previous, car(DrivingMode.COMFORT, VehicleGear.DRIVE), 1_000).save)
        val parked = engine.evaluate(enabled, previous, car(DrivingMode.COMFORT, VehicleGear.PARK, 2_000), 2_000)
        assertEquals(DrivingMode.COMFORT, parked.save?.mode)
    }

    @Test fun firstTripRecordsFreshModeAtDepartureWithoutRequiringAnotherModeCallback() {
        val engine = DrivingModeMemoryEngine()
        val initial = car(DrivingMode.ECO)
        engine.evaluate(enabled, null, initial, 1_000)
        val drive = car(DrivingMode.ECO, VehicleGear.DRIVE, 2_000).copy(drivingMode = initial.drivingMode)
        assertEquals(DrivingMode.ECO, engine.evaluate(enabled, null, drive, 2_000).save?.mode)
    }

    @Test fun requestReceiptWithoutActualFeedbackCannotBeRemembered() {
        val engine = DrivingModeMemoryEngine()
        engine.evaluate(enabled, previous, car(null), 1_000)
        assertNull(engine.evaluate(enabled, previous, car(null, VehicleGear.DRIVE, 2_000), 2_000).save)
        assertNull(engine.acceptCurrent(enabled, car(null), 3_000))
    }

    @Test fun aFreshGearTransitionCannotPromoteRegressedModeFeedback() {
        val engine = DrivingModeMemoryEngine()
        engine.evaluate(enabled, previous, car(DrivingMode.COMFORT, at = 100), 100)
        engine.evaluate(enabled, previous, car(DrivingMode.ECO, VehicleGear.DRIVE, 200), 200)
        val regressed = car(DrivingMode.COMFORT, VehicleGear.PARK, 300).copy(
            drivingMode = car(DrivingMode.COMFORT, at = 150).drivingMode)
        val rejected = engine.evaluate(enabled, previous, regressed, 300)
        assertNull(rejected.save)
        assertNull(rejected.restore)
        assertNull(engine.acceptCurrent(enabled, regressed, 300))
        assertEquals(DrivingMode.ECO, engine.evaluate(enabled, previous, car(DrivingMode.ECO, VehicleGear.PARK, 400), 400).save?.mode)
    }

    @Test fun aConflictingModeAtTheSameTimestampCannotReplaceConfirmedHistory() {
        val engine = DrivingModeMemoryEngine()
        engine.evaluate(enabled, previous, car(DrivingMode.COMFORT, at = 100), 100)
        engine.evaluate(enabled, previous, car(DrivingMode.ECO, VehicleGear.DRIVE, 200), 200)
        val conflicting = car(DrivingMode.SNOW, VehicleGear.PARK, 300).copy(
            drivingMode = car(DrivingMode.SNOW, at = 200).drivingMode)
        assertNull(engine.evaluate(enabled, previous, conflicting, 300).save)
        assertNull(engine.acceptCurrent(enabled, conflicting, 300))
    }

    @Test fun repeatedActualFeedbackDoesNotRewriteTheSameTripSelection() {
        val engine = DrivingModeMemoryEngine()
        engine.evaluate(enabled, null, car(DrivingMode.ECO, at = 100), 100)
        val saved = engine.evaluate(enabled, null, car(DrivingMode.ECO, VehicleGear.DRIVE, 200), 200).save!!
        val repeated = car(DrivingMode.ECO, VehicleGear.DRIVE, 300).let {
            it.copy(drivingMode = it.drivingMode!!.copy(receivedAtMillis = saved.confirmedAtMillis + 1))
        }
        assertNull(engine.evaluate(enabled, saved, repeated, 300).save)
        val parked = car(DrivingMode.ECO, VehicleGear.PARK, 400).let {
            it.copy(drivingMode = it.drivingMode!!.copy(receivedAtMillis = saved.confirmedAtMillis + 2))
        }
        assertNull(engine.evaluate(enabled, saved, parked, 400).save)
    }

    @Test fun explicitUseCurrentRequiresEnabledParkAndNoMotion() {
        val engine = DrivingModeMemoryEngine()
        val current = car(DrivingMode.COMFORT)
        assertEquals(DrivingMode.COMFORT, engine.acceptCurrent(enabled, current, 1_000)?.mode)
        assertNull(engine.acceptCurrent(DrivingModeMemorySettings(false), current, 1_000))
        // Mode and gear are reported only on change; their last values of the session still hold later.
        assertEquals(DrivingMode.COMFORT, engine.acceptCurrent(enabled, current, 60_000_002_000)?.mode)
        assertNull(engine.acceptCurrent(enabled, car(DrivingMode.COMFORT, VehicleGear.DRIVE), 1_000))
        assertNull(engine.acceptCurrent(enabled, current.copy(drivingMode = null), 1_000))
        assertNull(engine.acceptCurrent(enabled, current.copy(speedKph = current.speedKph!!.copy(value = 1f)), 1_000))
    }

    @Test fun identityMismatchAndSleepNeverOfferAnOldTarget() {
        val engine = DrivingModeMemoryEngine()
        val changed = car(DrivingMode.COMFORT).copy(identity = identity.copy(fingerprint = "different"))
        assertNull(engine.evaluate(enabled, previous, changed, 1_000).restore)
        assertNull(engine.evaluate(enabled, previous, car(DrivingMode.COMFORT, session = "wake").copy(drivingMode = null), 1_000).restore)
    }

    @Test fun reusedSessionIdWithNewStartCannotSaveAReconnectModeAsAnActiveTripChange() {
        val engine = DrivingModeMemoryEngine()
        engine.evaluate(enabled, previous, car(DrivingMode.COMFORT, at = 100), 100)
        val reconnect = car(DrivingMode.ECO, VehicleGear.DRIVE, 200).copy(sessionStartedAtElapsedNanos = 150)
        assertNull(engine.evaluate(enabled, previous, reconnect, 200).save)
    }
}
