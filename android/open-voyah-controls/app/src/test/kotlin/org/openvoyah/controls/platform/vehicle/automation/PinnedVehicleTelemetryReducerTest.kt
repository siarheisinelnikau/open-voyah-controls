package org.openvoyah.controls.platform.vehicle.automation

import org.junit.Assert.*
import org.junit.Test
import org.openvoyah.controls.domain.vehicle.automation.*

class PinnedVehicleTelemetryReducerTest {
    private fun reducer() = PinnedVehicleTelemetryReducer(VehicleTelemetry(
        VehicleIdentity("fixture", "fixture-build", 30, "x86_64"), "session", 1,
        confirmationWindow = VehicleTelemetryWindow()))
    private fun doors(driver: Int = 0, passenger: Int = 0, rearLeft: Int = 0, rearRight: Int = 0, lock: Int = 0) =
        listOf(1, 0, driver, passenger, 0, rearLeft, rearRight, lock, lock, lock, lock)
    private fun climate(outside: Int, fan: Int = 0) = MutableList(82) { 0 }.also { it[0] = 1; it[11] = fan; it[36] = outside }
    private fun PinnedVehicleTelemetryReducer.read(code: Int, values: List<Int>, at: Long = 100) =
        accept(code, values, at, 1_000)

    @Test fun firstDoorCallbackGivesStartingPositionsAndLockOnlyCallbacksDoNotRefreshThem() {
        val reader = reducer()
        val first = reader.read(1, doors())
        assertEquals(VehicleDoor.entries.toSet(), first.doors.keys)
        assertTrue(first.doors.values.all { it.value == VehicleDoorState.CLOSED && it.observedAtElapsedNanos == 100L })
        val locked = reader.read(1, doors(lock = 1), 200)
        assertTrue(locked.doors.values.all { it.observedAtElapsedNanos == 100L })
        assertFalse(VehicleSignalKind.DOORS in locked.unavailable)
    }

    @Test fun doorDeltaRefreshesOnlyChangedPositionAndNeverTheOtherDoors() {
        val reader = reducer()
        reader.read(1, doors())
        val driver = reader.read(1, doors(driver = 1), 200).doors[VehicleDoor.DRIVER]
        assertEquals(VehicleDoorState.OPEN, driver?.value)
        val current = reader.read(1, doors(driver = 1, passenger = 1, lock = 1), 300)
        assertSame(driver, current.doors[VehicleDoor.DRIVER])
        assertEquals(300L, current.doors[VehicleDoor.PASSENGER]?.observedAtElapsedNanos)
        assertEquals(100L, current.doors[VehicleDoor.REAR_LEFT]?.observedAtElapsedNanos)
        assertFalse(VehicleSignalKind.DOORS in current.unavailable)
    }

    @Test fun olderDoorPositionsStayKnownForTheSession() {
        // The producer reports doors only when they change, so an old position is still the current one.
        val reader = reducer()
        reader.read(1, doors())
        reader.read(1, doors(driver = 1), 200)
        val current = reader.read(1, doors(driver = 1, passenger = 1), 6_000_000_000)
        assertEquals(200L, current.doors[VehicleDoor.DRIVER]?.observedAtElapsedNanos)
        assertEquals(VehicleDoorState.OPEN, current.doors[VehicleDoor.DRIVER]?.value)
        assertEquals(6_000_000_000L, current.doors[VehicleDoor.PASSENGER]?.observedAtElapsedNanos)
    }

    @Test fun unknownDoorPositionRevokesOnlyItsOwnSample() {
        val reader = reducer()
        reader.read(1, doors())
        reader.read(1, doors(driver = 1, passenger = 1), 200)
        val current = reader.read(1, doors(driver = -1, passenger = 1), 300)
        assertNull(current.doors[VehicleDoor.DRIVER])
        assertEquals(200L, current.doors[VehicleDoor.PASSENGER]?.observedAtElapsedNanos)
    }

    @Test fun firstClimateCallbackGivesOutsideTemperatureAndCabinOrFanChangesNeverRefreshIt() {
        val reader = reducer()
        val first = reader.read(4, climate(20)).outsideCelsius
        assertEquals(20f, first?.value)
        assertSame(first, reader.read(4, climate(20, 2), 200).outsideCelsius)
        val outside = reader.read(4, climate(21, 2), 300).outsideCelsius
        assertEquals(21f, outside?.value)
        assertSame(outside, reader.read(4, climate(21, 3), 400).outsideCelsius)
    }

    @Test fun climateInvalidSentinelRevokesOutsideAndAmbientCallbackCannotReplaceIt() {
        val reader = reducer()
        reader.read(4, climate(20))
        reader.read(4, climate(21), 200)
        val invalid = reader.read(4, climate(-9999), 300)
        assertNull(invalid.outsideCelsius)
        assertTrue(VehicleSignalKind.OUTSIDE_TEMPERATURE in invalid.unavailable)
        assertNull(reader.read(14, listOf(30), 400).outsideCelsius)
    }

    @Test fun setterEchoCannotOverwriteOrRefreshActualModeFeedback() {
        val reader = reducer()
        val actual = reader.read(36, listOf(1, 0x238, 0x313, 3)).drivingMode
        assertEquals(DrivingMode.SPORT, actual?.value)
        assertSame(actual, reader.read(36, listOf(1, 0x148, 0x221, 2), 200).drivingMode)
        val unknown = reader.read(36, listOf(1, 0x238, 0x313, 8), 300)
        assertNull(unknown.drivingMode)
        assertTrue(VehicleSignalKind.DRIVING_MODE in unknown.unavailable)
    }

    @Test fun serviceCacheSnapshotSeedsActualModeInTheFeedbackLayout() {
        // getVehicleState(DRIVING_MODE_SET_FB) returned 2 on the owner car while Comfort was on.
        val reader = reducer()
        val seeded = reader.read(PinnedVehicleCallbacks.VEHICLE_STATE, PinnedVehicleCallbacks.drivingModeSnapshot(2))
        assertEquals(DrivingMode.COMFORT, seeded.drivingMode?.value)
        assertFalse(VehicleSignalKind.DRIVING_MODE in seeded.unavailable)
        // A later change callback replaces the seed as usual.
        assertEquals(DrivingMode.SPORT, reader.read(36, listOf(1, 0x238, 0x313, 3), 200).drivingMode?.value)
        assertNull(reducer().read(PinnedVehicleCallbacks.VEHICLE_STATE, PinnedVehicleCallbacks.drivingModeSnapshot(-1)).drivingMode)
    }

    @Test fun ambiguousFactorySpeedIsNeverAdmitted() {
        val reader = reducer()
        reader.read(12, listOf(1, 0, 0))
        val zero = reader.read(16, listOf(0), 200)
        assertNull(zero.speedKph)
        // Standstill then rests on Park alone; the agent reads live gear and speed before Park-only writes.
        assertEquals(zero.gear?.value == VehicleGear.PARK, zero.isStationary(200))
        assertNull(reader.read(16, listOf(40), 300).speedKph)
    }

    @Test fun invalidGearRevokesQueuedEvidenceAndResetsSharedObjectBaselines() {
        val reader = reducer()
        reader.read(1, doors())
        val before = reader.read(12, listOf(1, 0, 0), 200)
        val invalid = reader.read(12, listOf(1, 4, 4), 300)
        assertFalse(before.confirmationWindow!!.isOpen)
        assertNotEquals(before.sessionId, invalid.sessionId)
        assertTrue(invalid.unavailable.containsAll(VehicleSignalKind.entries))
        val seeded = reader.read(1, doors(driver = 1), 400)
        assertTrue(seeded.doors.values.all { it.sessionId == invalid.sessionId && it.observedAtElapsedNanos == 400L })
    }

    @Test fun regressingTimestampRevokesTheSessionWithoutPublishingTheRegressedEvent() {
        val reader = reducer()
        val before = reader.read(12, listOf(1, 0, 0), 300)
        val current = reader.read(12, listOf(1, 3, 3), 200)
        assertFalse(before.confirmationWindow!!.isOpen)
        assertNull(current.gear)
        assertEquals(300L, current.sessionStartedAtElapsedNanos)
    }
}
