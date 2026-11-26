package org.openvoyah.controls.platform.vehicle.comfort

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.openvoyah.controls.data.vehicle.comfort.PlaybackComfortPreferences
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoorState
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetrySession
import org.openvoyah.controls.domain.vehicle.comfort.DriverDoorPauseEdge
import org.openvoyah.controls.domain.vehicle.comfort.PlaybackComfortSettings
import org.openvoyah.controls.platform.media.ComfortMediaTransport
import org.openvoyah.controls.platform.vehicle.watchDeviceInteractivity

internal suspend fun observePlaybackComfort(
    app: Context,
    preferences: PlaybackComfortPreferences,
    settings: PlaybackComfortSettings,
    telemetry: StateFlow<VehicleTelemetry>,
    hasOwner: () -> Boolean,
) = coroutineScope {
    val power = app.getSystemService(PowerManager::class.java) ?: return@coroutineScope
    val interactive = MutableStateFlow<Boolean?>(null)
    val media = ComfortMediaTransport(app)
    val doorEdge = DriverDoorPauseEdge()
    var lastInteractive: Boolean? = null
    var pause = ComfortMediaTransport.PauseAttempt()
    fun currentSettings(): Boolean = hasOwner() && runCatching(preferences::read).getOrNull() == settings

    launch { watchDeviceInteractivity(app) { on, _ -> interactive.value = on } }
    val observedDoor = if (settings.pauseOnDriverDoor) telemetry else flowOf(null)
    combine(interactive, observedDoor) { screenOn, vehicle -> screenOn to vehicle }.collect { (screenOn, vehicle) ->
        val previous = lastInteractive
        lastInteractive = screenOn
        when {
            screenOn == false -> {
                doorEdge.reset()
                if (previous == true && settings.restoreAfterSleep) {
                    val receipt = pause.receipt
                    if (!pause.requested || (receipt != null && !media.remainsPaused(receipt))) {
                        pause = media.pause { !power.isInteractive && currentSettings() }
                    }
                }
            }
            screenOn == true && previous == false -> {
                val receipt = pause.receipt
                pause = ComfortMediaTransport.PauseAttempt()
                if (settings.restoreAfterSleep && receipt != null) {
                    media.resume(receipt) { power.isInteractive && currentSettings() }
                }
            }
            screenOn == true && vehicle != null && doorEdge.opened(vehicle, SystemClock.elapsedRealtimeNanos()) -> {
                if (pause.requested && pause.receipt == null) return@collect
                val session = VehicleTelemetrySession(vehicle)
                val door = vehicle.doors[VehicleDoor.DRIVER]
                val attempt = media.pause {
                    val latest = telemetry.value
                    val now = SystemClock.elapsedRealtimeNanos()
                    power.isInteractive && currentSettings() && session.matches(latest) &&
                        door?.value == VehicleDoorState.OPEN && door == latest.doors[VehicleDoor.DRIVER] &&
                        door.isFreshFor(latest, now, maxAgeMillis = 1_500) && latest.isStationary(now)
                }
                if (attempt.requested) pause = attempt
            }
        }
    }
}
