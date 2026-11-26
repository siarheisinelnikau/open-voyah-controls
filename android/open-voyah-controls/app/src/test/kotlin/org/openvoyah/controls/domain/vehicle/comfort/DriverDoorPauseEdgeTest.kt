package org.openvoyah.controls.domain.vehicle.comfort

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoorState
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.domain.vehicle.automation.VehicleIdentity
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignal
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignalKind
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetryWindow
import org.openvoyah.controls.domain.vehicle.automation.expireVehicleSignals

class DriverDoorPauseEdgeTest {
    private val identity = VehicleIdentity("fixture", "fixture/fingerprint", 30, "arm64-v8a")
    private val window = VehicleTelemetryWindow()

    @Test fun onlyAFreshDriverDoorOpenInParkTriggersOncePerOpening() {
        val edge = DriverDoorPauseEdge()
        val closed = car(VehicleDoorState.CLOSED, 1)
        val open = car(VehicleDoorState.OPEN, 2)
        assertFalse(edge.opened(closed, time(1)))
        assertFalse(edge.opened(closed.copy(doors = closed.doors + (VehicleDoor.PASSENGER to
            sample(VehicleDoorState.OPEN, 2))), time(2)))
        assertTrue(edge.opened(open, time(2)))
        assertFalse(edge.opened(open, time(3)))
        assertFalse(edge.opened(car(VehicleDoorState.CLOSED, 4), time(4)))
        assertFalse(edge.opened(car(VehicleDoorState.OPEN, 5, gear = VehicleGear.DRIVE), time(5)))
        assertFalse(edge.opened(car(VehicleDoorState.OPEN, 5), time(6)))
    }

    @Test fun staleEventsAndSessionsThatAlreadyShowOpenDoNotTrigger() {
        val edge = DriverDoorPauseEdge()
        assertFalse(edge.opened(car(VehicleDoorState.OPEN, 1), time(1)))
        assertFalse(edge.opened(car(VehicleDoorState.CLOSED, 2), time(2)))
        assertFalse(edge.opened(car(VehicleDoorState.OPEN, 3), time(5)))
        assertFalse(edge.opened(car(VehicleDoorState.CLOSED, 6), time(6)))
        assertFalse(edge.opened(car(VehicleDoorState.OPEN, 7, session = "next"), time(7)))
        assertFalse(edge.opened(car(VehicleDoorState.CLOSED, 8, session = "next"), time(8)))
        window.close()
        assertFalse(edge.opened(car(VehicleDoorState.OPEN, 9, session = "next"), time(9)))
    }

    @Test fun aReplacedSourceOrOutOfOrderEventCannotCreateAnOpening() {
        val edge = DriverDoorPauseEdge()
        assertFalse(edge.opened(car(VehicleDoorState.CLOSED, 2), time(2)))
        assertFalse(edge.opened(car(VehicleDoorState.OPEN, 1), time(2)))
        val open = car(VehicleDoorState.OPEN, 3)
        assertFalse(edge.opened(open.copy(doors = mapOf(VehicleDoor.DRIVER to
            open.doors.getValue(VehicleDoor.DRIVER).copy(sourceId = "replacement"))), time(3)))
    }

    @Test fun volumeStaysLowerUntilTheLastReportedDoorCloses() {
        val driver = car(VehicleDoorState.OPEN, 1).copy(gear = null)
        val twoOpen = driver.copy(doors = driver.doors + (VehicleDoor.REAR_RIGHT to sample(VehicleDoorState.OPEN, 2)))
        val rear = twoOpen.copy(doors = twoOpen.doors + (VehicleDoor.DRIVER to sample(VehicleDoorState.CLOSED, 3)))
        val closed = rear.copy(doors = rear.doors + (VehicleDoor.REAR_RIGHT to sample(VehicleDoorState.CLOSED, 4)))

        assertTrue(driver.hasOpenDoor(time(1)))
        assertTrue(twoOpen.hasOpenDoor(time(2)))
        assertTrue(rear.hasOpenDoor(time(10)))
        assertFalse(closed.hasOpenDoor(time(10)))
    }

    @Test fun anOlderDoorSampleDoesNotHideAnOpeningOrCutItShort() {
        val driverOpen = car(VehicleDoorState.OPEN, 8)
        val twoDoors = driverOpen.copy(doors = driverOpen.doors + (VehicleDoor.PASSENGER to sample(VehicleDoorState.CLOSED, 1)))
        val expiredForDiagnostics = twoDoors.expireVehicleSignals(time(8))
        assertTrue(VehicleSignalKind.DOORS in expiredForDiagnostics.unavailable)
        assertTrue(expiredForDiagnostics.hasOpenDoor(time(8)))
        assertTrue(twoDoors.expireVehicleSignals(time(20)).hasOpenDoor(time(20)))
    }

    @Test fun aClearedOrPreviousSessionDoorCannotKeepVolumeLowered() {
        val open = car(VehicleDoorState.OPEN, 1)
        assertTrue(open.hasOpenDoor(time(1)))
        assertFalse(open.copy(doors = emptyMap(), unavailable = setOf(VehicleSignalKind.DOORS)).hasOpenDoor(time(1)))
        assertFalse(open.copy(sessionId = "next").hasOpenDoor(time(2)))
        window.close()
        assertFalse(open.hasOpenDoor(time(2)))
    }

    private fun car(door: VehicleDoorState, seconds: Long, gear: VehicleGear = VehicleGear.PARK, session: String = "first") =
        VehicleTelemetry(identity, session, time(1), gear = sample(gear, 1, session),
            doors = mapOf(VehicleDoor.DRIVER to sample(door, seconds, session)), unavailable = emptySet(), confirmationWindow = window)

    private fun <T> sample(value: T, seconds: Long, session: String = "first") =
        VehicleSignal(value, time(seconds), seconds * 1_000, "fixture", session)

    private fun time(seconds: Long) = seconds * 1_000_000_000L
}
