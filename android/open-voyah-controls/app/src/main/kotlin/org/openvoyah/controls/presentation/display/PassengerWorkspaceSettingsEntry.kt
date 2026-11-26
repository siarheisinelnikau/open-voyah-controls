package org.openvoyah.controls.presentation.display

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.platform.display.PassengerWorkspaceLauncher
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitFeedback

@Composable
internal fun PassengerWorkspaceSettingsEntry() {
    if (!PassengerWorkspaceLauncher.supported()) return
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    CockpitChoice(R.string.passenger_workspace_title, enabled = !opening) {
        if (!opening && owner.lifecycle.currentState == Lifecycle.State.RESUMED) {
            opening = true
            unavailable = false
            scope.launch {
                try {
                    unavailable = !PassengerWorkspaceLauncher.open(activity)
                } finally {
                    opening = false
                }
            }
        }
    }
    if (opening) CockpitFeedback(UiText.get(R.string.ui_80))
    if (unavailable) CockpitFeedback(UiText.get(R.string.passenger_workspace_unavailable), error = true)
}
