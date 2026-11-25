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
                // The service invokes every listener twice per change, 5-10 ms apart (logcat of all its clients on
                // the owner car). The repeat keeps the time of the change: a new stamp would replace the sample a
                // pending headlight request was decided on.
                else if (gear == telemetry.gear?.value) return telemetry
                // The connect-time baseline describes only the gear the session's first callback changed from.
                telemetry = telemetry.copy(gear = gear?.let(::sample),
                    unavailable = availability(VehicleSignalKind.GEAR, gear != null),
                    gearAtConnect = telemetry.gearAtConnect.takeIf { telemetry.gear == null })
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
                // The first callback of a session gives the starting positions. After it, only a changed
                // position gets a new timestamp; lock-only callbacks do not. Positions stay known for the
                // session, because the producer reports doors only when they change.
                val doors = telemetry.doors.toMutableMap()
                for ((door, position) in positions) {
                    if (position == null) doors.remove(door)
                    else if (previous == null || position != previous[door]) doors[door] = sample(position)
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
                else if (previous == null || previous != raw || telemetry.outsideCelsius == null)
                    telemetry = telemetry.copy(outsideCelsius = sample(temperature),
                    unavailable = availability(VehicleSignalKind.OUTSIDE_TEMPERATURE, true))
            }
            // The factory speed callback collapses invalid input and a real standstill
            // to the same zero. It stays diagnostic; a zero must not open a Park gate.
            // The ambient callback is not this producer's outside-temperature signal.
        }
        return telemetry
    }

    /**
     * Records the service's cached getGearStatus value as the session's gear baseline. Only the session that was
     * connected when it was read, and only before its first gear callback: a callback that arrived first wins.
     */
    fun seedGearAtConnect(sessionId: String, ordinal: Int, value: Int): VehicleTelemetry {
        val gear = PinnedVehicleCallbacks.gear(ordinal, value) ?: return telemetry
        if (sessionId != telemetry.sessionId || telemetry.gear != null || telemetry.gearAtConnect != null ||
            VehicleSignalKind.GEAR !in telemetry.unavailable) return telemetry
        telemetry = telemetry.copy(gearAtConnect = gear)
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
