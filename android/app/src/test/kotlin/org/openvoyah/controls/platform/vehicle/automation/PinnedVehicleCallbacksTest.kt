package org.openvoyah.controls.platform.vehicle.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.domain.vehicle.automation.DrivingMode
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoorState

class PinnedVehicleCallbacksTest {
    @Test fun gearRequiresBothReviewedParcelFieldsAndRejectsUnknownOrBattery() {
        assertEquals(VehicleGear.PARK, PinnedVehicleCallbacks.gear(0, 0))
        assertEquals(VehicleGear.DRIVE, PinnedVehicleCallbacks.gear(3, 3))
        assertNull(PinnedVehicleCallbacks.gear(0, 3))
        assertNull(PinnedVehicleCallbacks.gear(3, 0))
        assertNull(PinnedVehicleCallbacks.gear(4, 4))
        assertNull(PinnedVehicleCallbacks.gear(5, -1))
    }

    @Test fun physicalModeFeedbackIsDistinctFromTheSetterEchoAndItsAcknowledgments() {
        assertEquals("driving_mode_feedback", PinnedVehicleCallbacks.diagnosticKey(36, listOf(1, 0x238, 0x313, 2)))
        assertEquals("driving_mode_request_echo", PinnedVehicleCallbacks.diagnosticKey(36, listOf(1, 0x148, 0x221, 2)))
        assertNull(PinnedVehicleCallbacks.diagnosticKey(40, listOf(1, 0x238, 0x313, 2)))
        assertNull(PinnedVehicleCallbacks.diagnosticKey(41, listOf(1, 0x238, 0x313, 2)))
        assertNull(PinnedVehicleCallbacks.diagnosticKey(36, listOf(1, 0x238, 0x221, 2)))
    }

    @Test fun malformedAndAbsentPayloadsCannotQualifyAsSamples() {
        assertNull(PinnedVehicleCallbacks.diagnosticKey(12, listOf(0, 0, 0)))
        assertNull(PinnedVehicleCallbacks.diagnosticKey(12, listOf(1, 0)))
        assertNull(PinnedVehicleCallbacks.diagnosticKey(1, List(10) { 1 }))
        assertEquals("doors_cache", PinnedVehicleCallbacks.diagnosticKey(1, List(11) { 1 }))
        assertNull(PinnedVehicleCallbacks.expectedIntegers(41))
    }

    @Test fun modeFeedbackUsesOnlyTheReviewedEnumPairAndSupportedValues() {
        assertEquals(DrivingMode.ECO, PinnedVehicleCallbacks.drivingMode(listOf(1, 0x238, 0x313, 1)))
        assertEquals(DrivingMode.SNOW, PinnedVehicleCallbacks.drivingMode(listOf(1, 0x238, 0x313, 6)))
        assertNull(PinnedVehicleCallbacks.drivingMode(listOf(1, 0x148, 0x221, 2)))
        assertNull(PinnedVehicleCallbacks.drivingMode(listOf(1, 0x238, 0x313, 7)))
    }

    @Test fun doorPositionsSkipBonnetLoadSpaceAndTheFourLocks() {
        val raw = listOf(1, 1, 0, 1, 1, 1, 0, 9, 9, 9, 9)
        assertEquals(mapOf(VehicleDoor.DRIVER to VehicleDoorState.CLOSED,
            VehicleDoor.PASSENGER to VehicleDoorState.OPEN, VehicleDoor.REAR_LEFT to VehicleDoorState.OPEN,
            VehicleDoor.REAR_RIGHT to VehicleDoorState.CLOSED), PinnedVehicleCallbacks.doorPositions(raw))
        assertNull(PinnedVehicleCallbacks.doorPositions(raw.dropLast(1)))
    }

    @Test fun outsideTemperatureRequiresExactAirConditionParcelAndRejectsInvalidSentinel() {
        fun raw(value: Int) = MutableList(82) { 0 }.also { it[0] = 1; it[36] = value }
        assertEquals(-40f, PinnedVehicleCallbacks.outsideTemperature(raw(-40)))
        assertEquals(87f, PinnedVehicleCallbacks.outsideTemperature(raw(87)))
        assertNull(PinnedVehicleCallbacks.outsideTemperature(raw(-9999)))
        assertNull(PinnedVehicleCallbacks.outsideTemperature(raw(88)))
        assertNull(PinnedVehicleCallbacks.outsideTemperature(raw(20).dropLast(1)))
        assertEquals(82, PinnedVehicleCallbacks.expectedIntegers(PinnedVehicleCallbacks.AIR_CONDITION))
    }
}
