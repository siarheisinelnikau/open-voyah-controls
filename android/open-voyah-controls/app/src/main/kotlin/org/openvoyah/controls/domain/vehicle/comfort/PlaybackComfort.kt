package org.openvoyah.controls.domain.vehicle.comfort

import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoorState
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignal
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetrySession

internal data class PlaybackComfortSettings(val pauseOnDriverDoor: Boolean = false, val restoreAfterSleep: Boolean = false) {
    val configured: Boolean get() = pauseOnDriverDoor || restoreAfterSleep
}

/** A callback after a reset seeds the new state, even when the driver's door is already open. */
internal class DriverDoorPauseEdge {
    private var session: VehicleTelemetrySession? = null
    private var lastDoor: VehicleSignal<VehicleDoorState>? = null

    fun reset() {
        session = null
        lastDoor = null
    }

    fun opened(telemetry: VehicleTelemetry, nowNanos: Long): Boolean {
        if (session?.matches(telemetry) != true) {
            reset()
            session = VehicleTelemetrySession(telemetry)
        }
        val door = telemetry.doors[VehicleDoor.DRIVER]?.takeIf { it.isCurrentIn(telemetry, nowNanos) }
        val previous = lastDoor
        if (door != null && previous?.sourceId == door.sourceId &&
            door.observedAtElapsedNanos <= previous.observedAtElapsedNanos) return false
        val entered = previous?.value == VehicleDoorState.CLOSED && door?.value == VehicleDoorState.OPEN &&
            previous.sourceId == door.sourceId
        lastDoor = door
        return entered && door?.isFreshFor(telemetry, nowNanos, maxAgeMillis = 1_500) == true &&
            telemetry.isStationary(nowNanos)
    }
}
