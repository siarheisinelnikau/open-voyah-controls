package org.openvoyah.controls.presentation.vehicle.comfort

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.vehicle.comfort.IgnitionMode
import org.openvoyah.controls.domain.vehicle.comfort.IgnitionStatus
import org.openvoyah.controls.platform.vehicle.comfort.observeIgnitionStatus

@Composable
internal fun VehicleIgnitionStatus() {
    val app = LocalContext.current.applicationContext
    val status by remember(app) { observeIgnitionStatus(app) }
        .collectAsStateWithLifecycle(IgnitionStatus.Waiting, minActiveState = Lifecycle.State.RESUMED)
    val message = when (val reading = status) {
        IgnitionStatus.Unavailable -> UiText.get(R.string.comfort_ignition_unavailable)
        IgnitionStatus.PermissionDenied -> UiText.get(R.string.comfort_ignition_permission)
        IgnitionStatus.Waiting -> UiText.get(R.string.comfort_ignition_waiting)
        is IgnitionStatus.Reported -> {
            val mode = UiText.get(when (reading.mode) {
                IgnitionMode.LOCKED -> R.string.comfort_ignition_locked
                IgnitionMode.OFF -> R.string.comfort_ignition_off
                IgnitionMode.ACCESSORY -> R.string.comfort_ignition_accessory
                IgnitionMode.ON -> R.string.comfort_ignition_on
                IgnitionMode.STARTING -> R.string.comfort_ignition_starting
            })
            UiText.get(if (reading.currentConnection) R.string.comfort_ignition_current else R.string.comfort_ignition_earlier).format(mode)
        }
    }
    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
