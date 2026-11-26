package org.openvoyah.controls.domain.vehicle.comfort

import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoorState
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignal
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetrySession

internal data class PlaybackComfortSettings(
    val pauseOnDriverDoor: Boolean = false,
    val restoreAfterSleep: Boolean = false,
    val duckOnDoor: Boolean = false,
) {
    init { require(!pauseOnDriverDoor || !duckOnDoor) }

    val pauses: Boolean get() = pauseOnDriverDoor || restoreAfterSleep
    val configured: Boolean get() = pauses || duckOnDoor
}

// The source clears invalid door samples and revokes the session on disconnect. The diagnostics can
// age out the whole door group even while an unchanged position is known in the current session.
internal fun VehicleTelemetry.hasOpenDoor(nowNanos: Long): Boolean =
    doors.values.any { it.value == VehicleDoorState.OPEN && it.isCurrentIn(this, nowNanos) }

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
