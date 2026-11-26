package org.openvoyah.controls.domain.vehicle.comfort

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoorState
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.domain.vehicle.automation.VehicleIdentity
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignal
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetryWindow

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

    private fun car(door: VehicleDoorState, seconds: Long, gear: VehicleGear = VehicleGear.PARK, session: String = "first") =
        VehicleTelemetry(identity, session, time(1), gear = sample(gear, 1, session),
            doors = mapOf(VehicleDoor.DRIVER to sample(door, seconds, session)), unavailable = emptySet(), confirmationWindow = window)

    private fun <T> sample(value: T, seconds: Long, session: String = "first") =
        VehicleSignal(value, time(seconds), seconds * 1_000, "fixture", session)

    private fun time(seconds: Long) = seconds * 1_000_000_000L
}
