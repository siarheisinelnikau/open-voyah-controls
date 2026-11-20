package org.openvoyah.controls.platform.vehicle.automation

import java.util.UUID
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoorState
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignal
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignalKind
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetryWindow

/** Reduction of the admitted service ABI only. It never queries or writes the factory service. */
internal class PinnedVehicleTelemetryReducer(initial: VehicleTelemetry) {
    var telemetry = initial
        private set
    private var lastElapsedNanos = initial.sessionStartedAtElapsedNanos
    private var doorBaseline: Map<VehicleDoor, VehicleDoorState?>? = null
    private var outsideBaseline: Int? = null

    fun accept(code: Int, values: List<Int>, elapsed: Long, received: Long): VehicleTelemetry {
        if (PinnedVehicleCallbacks.diagnosticKey(code, values) == null || received <= 0 || elapsed <= 0) return telemetry
        if (elapsed < lastElapsedNanos) {
            restart(lastElapsedNanos)
            return telemetry
        }
        lastElapsedNanos = elapsed
        fun <T> sample(value: T) = VehicleSignal(value, elapsed, received, SOURCE_ID, telemetry.sessionId)
        when (code) {
            PinnedVehicleCallbacks.GEAR -> {
                val gear = PinnedVehicleCallbacks.gear(values[1], values[2])
                if (gear == null) restart(elapsed)
                telemetry = telemetry.copy(gear = gear?.let(::sample),
                    unavailable = availability(VehicleSignalKind.GEAR, gear != null))
            }
            PinnedVehicleCallbacks.VEHICLE_STATE -> {
                // DRIVING_MODE_SET is emitted before the setter sends CAN. It cannot
                // overwrite, refresh, or clear actual VCU feedback.
                if (PinnedVehicleCallbacks.diagnosticKey(code, values) != "driving_mode_feedback") return telemetry
                val mode = PinnedVehicleCallbacks.drivingMode(values)
                telemetry = telemetry.copy(drivingMode = mode?.let(::sample),
                    unavailable = availability(VehicleSignalKind.DRIVING_MODE, mode != null))
            }
            PinnedVehicleCallbacks.DOORS -> {
                val positions = requireNotNull(PinnedVehicleCallbacks.doorPositions(values))
                val previous = doorBaseline
                doorBaseline = positions
                // A first/shared-object callback does not identify the position producer.
                // Only a changed position gets a new timestamp. Lock-only callbacks do not.
                if (previous == null) return telemetry
                val doors = telemetry.doors.filterValues { it.isFreshFor(telemetry, elapsed) }.toMutableMap()
                for ((door, position) in positions) {
                    if (position == null) doors.remove(door)
                    else if (position != previous[door]) doors[door] = sample(position)
                }
                telemetry = telemetry.copy(doors = doors.toMap(),
                    unavailable = availability(VehicleSignalKind.DOORS, doors.isNotEmpty()))
            }
            PinnedVehicleCallbacks.AIR_CONDITION -> {
                val raw = values[PinnedVehicleCallbacks.OUTSIDE_TEMPERATURE_INDEX]
                val previous = outsideBaseline
                outsideBaseline = raw
                val temperature = PinnedVehicleCallbacks.outsideTemperature(values)
                if (temperature == null) telemetry = telemetry.copy(outsideCelsius = null,
                    unavailable = availability(VehicleSignalKind.OUTSIDE_TEMPERATURE, false))
                else if (previous != null && previous != raw) telemetry = telemetry.copy(outsideCelsius = sample(temperature),
                    unavailable = availability(VehicleSignalKind.OUTSIDE_TEMPERATURE, true))
            }
            // The factory speed callback collapses invalid input and a real standstill
            // to the same zero. It stays diagnostic; a zero must not open a Park gate.
            // The ambient callback is not this producer's outside-temperature signal.
        }
        return telemetry
    }

    private fun availability(kind: VehicleSignalKind, available: Boolean) =
        if (available) telemetry.unavailable - kind else telemetry.unavailable + kind

    private fun restart(elapsed: Long) {
        telemetry.confirmationWindow?.close()
        telemetry = VehicleTelemetry(telemetry.identity, UUID.randomUUID().toString(), elapsed,
            confirmationWindow = VehicleTelemetryWindow())
        doorBaseline = null
        outsideBaseline = null
    }

    companion object { const val SOURCE_ID = "qinggan_callback_arrival" }
}
