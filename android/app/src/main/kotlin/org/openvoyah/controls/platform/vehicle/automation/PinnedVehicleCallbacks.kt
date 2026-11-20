package org.openvoyah.controls.platform.vehicle.automation

import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.domain.vehicle.automation.DrivingMode
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoorState

/** Wire identifiers apply only after QingganHeadlightReader admits its exact pinned service APK. */
internal object PinnedVehicleCallbacks {
    const val DOORS = 1
    const val AIR_CONDITION = 4
    const val GEAR = 12
    const val AMBIENT_TEMPERATURE = 14
    const val VEHICLE_SPEED = 16
    const val VEHICLE_STATE = 36

    // AirCondition.writeToParcel carries 81 four-byte fields (five are floats). Keep the
    // whole exact-size check, but read only the outside-temperature integer at field 35.
    const val AIR_CONDITION_WORDS = 82
    const val OUTSIDE_TEMPERATURE_INDEX = 36 // includes the Parcelable presence marker

    fun drivingMode(values: List<Int>): DrivingMode? {
        if (diagnosticKey(VEHICLE_STATE, values) != "driving_mode_feedback") return null
        return when (values[3]) {
            1 -> DrivingMode.ECO
            2 -> DrivingMode.COMFORT
            3 -> DrivingMode.SPORT
            4 -> DrivingMode.OFFROAD
            5 -> DrivingMode.INDIVIDUAL
            6 -> DrivingMode.SNOW
            else -> null
        }
    }

    fun doorPositions(values: List<Int>): Map<VehicleDoor, VehicleDoorState?>? {
        if (diagnosticKey(DOORS, values) == null) return null
        fun state(index: Int) = when (values[index]) {
            0 -> VehicleDoorState.CLOSED
            1 -> VehicleDoorState.OPEN
            else -> null
        }
        // bonnet, front-left, front-right, load-space, rear-left, rear-right, four locks.
        return mapOf(VehicleDoor.DRIVER to state(2), VehicleDoor.PASSENGER to state(3),
            VehicleDoor.REAR_LEFT to state(5), VehicleDoor.REAR_RIGHT to state(6))
    }

    fun outsideTemperature(values: List<Int>): Float? {
        if (diagnosticKey(AIR_CONDITION, values) == null) return null
        // Exact producer divides the unsigned byte by two, subtracts 40 and publishes
        // -9999 when its validity bit clears. The shared object is still a cache.
        return values[OUTSIDE_TEMPERATURE_INDEX].takeIf { it in -40..87 }?.toFloat()
    }

    fun gear(ordinal: Int, value: Int): VehicleGear? = when {
        ordinal == 0 && value == 0 -> VehicleGear.PARK
        ordinal == 1 && value == 1 -> VehicleGear.REVERSE
        ordinal == 2 && value == 2 -> VehicleGear.NEUTRAL
        ordinal == 3 && value == 3 -> VehicleGear.DRIVE
        else -> null
    }

    fun diagnosticKey(code: Int, values: List<Int>): String? = when (code) {
        DOORS -> "doors_cache".takeIf { values.size == 11 && values.first() == 1 }
        AIR_CONDITION -> "air_condition_cache".takeIf { values.size == AIR_CONDITION_WORDS && values.first() == 1 }
        GEAR -> "gear_callback".takeIf { values.size == 3 && values.first() == 1 }
        AMBIENT_TEMPERATURE -> "ambient_temperature_callback".takeIf { values.size == 1 }
        VEHICLE_SPEED -> "speed_callback".takeIf { values.size == 1 }
        VEHICLE_STATE -> when {
            values.size != 4 || values.first() != 1 -> null
            values[1] == 0x238 && values[2] == 0x313 -> "driving_mode_feedback"
            values[1] == 0x148 && values[2] == 0x221 -> "driving_mode_request_echo"
            else -> null
        }
        else -> null
    }

    fun expectedIntegers(code: Int): Int? = when (code) {
        DOORS -> 11
        AIR_CONDITION -> AIR_CONDITION_WORDS
        GEAR -> 3
        AMBIENT_TEMPERATURE, VEHICLE_SPEED -> 1
        VEHICLE_STATE -> 4
        else -> null
    }
}
