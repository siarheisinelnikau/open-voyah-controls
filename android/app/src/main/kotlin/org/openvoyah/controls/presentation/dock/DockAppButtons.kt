package org.openvoyah.controls.presentation.dock

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.platform.apps.AppsPlatform
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon

@Composable
internal fun DockAppButtons(model: QuickActionsViewModel, components: List<String>, route: ControlsRoute, showLabels: Boolean,
                            rail: Boolean, modifier: Modifier, afterUse: () -> Unit) {
    val activity = checkNotNull(LocalActivity.current)
    val platform = remember(activity) { AppsPlatform(activity) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resolved by model.appShortcuts.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf<Job?>(null) }
    val onOpened by rememberUpdatedState(afterUse)
    LifecycleResumeEffect(lifecycle) { onPauseOrDispose { opening?.cancel() } }
    // A launch that is still resolving belongs to the page it was started from.
    LaunchedEffect(route) { opening?.cancel() }

    for (component in components) {
        val app = resolved?.get(component) ?: continue
        val label = app.entry.label()
        val title = if (resolved.orEmpty().values.count { it.entry.label() == label } > 1) {
            "$label · ${app.entry.component().flattenToShortString()}"
        } else label
        key(component) {
            DockButton(title, label.takeIf { showLabels }, rail, modifier.testTag("quickActionsApp:$component"), glyph = {
                val icon = remember(app.icon) { app.icon?.asImageBitmap() }
                if (icon == null) CockpitGlyph(CockpitIcon.APPS) else Image(icon, null, Modifier.size(24.dp))
            }) {
                if (opening?.isActive != true && lifecycle.currentState == Lifecycle.State.RESUMED) {
                    opening = scope.launch {
                        // An update can remove an activity's launcher filter while leaving the activity installed.
                        val entry = model.launcherEntry(component)
                        val current = model.state.value.settings
                        if (lifecycle.currentState != Lifecycle.State.RESUMED || current?.enabled != true ||
                            component !in current.appShortcuts) return@launch
                        val opened = entry != null && try {
                            platform.launch(entry)
                            true
                        } catch (_: RuntimeException) { false }
                        if (opened) onOpened() else {
                            model.refreshAppShortcuts()
                            Toast.makeText(activity, UiText.get(R.string.quick_actions_app_open_failed).format(label), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}
