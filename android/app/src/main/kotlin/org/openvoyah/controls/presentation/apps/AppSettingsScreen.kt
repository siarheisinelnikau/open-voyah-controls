package org.openvoyah.controls.presentation.apps

import android.view.Display
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.frida.FridaWindowControls.Connection
import org.openvoyah.controls.platform.apps.AppsPlatform
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon

@Composable
internal fun AppSettingsScreen(
    state: AppSettingsViewModel.State,
    model: AppSettingsViewModel,
    displays: () -> List<AppsPlatform.DisplayChoice>,
    windowsSupported: () -> Boolean,
    open: (Int?, Boolean) -> Unit,
    appInfo: () -> Unit,
) {
    val app = state.app
    val colors = MaterialTheme.colorScheme
    var choices by remember(app.id) { mutableStateOf<List<AppsPlatform.DisplayChoice>?>(null) }
    val launchActions: @Composable () -> Unit = {
        if (app.launchEntry != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ choices = displays() }, Modifier.heightIn(min = 50.dp), enabled = !state.busy) {
                    Text(text(R.string.choose_display))
                }
                Button({ open(null, false) }, Modifier.heightIn(min = 50.dp), enabled = !state.busy) {
                    Text(text(R.string.open_app))
                }
            }
        }
    }
    Surface(color = colors.background) {
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 22.dp, vertical = 17.dp),
        ) {
            val wide = maxWidth >= 880.dp && state.window != null
            val inlineActions = maxWidth >= 680.dp
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(model::close, Modifier.heightIn(min = 50.dp), enabled = !state.busy) {
                        CockpitGlyph(CockpitIcon.BACK, Modifier.size(19.dp))
                        Text(text(R.string.cockpit_back), Modifier.padding(start = 7.dp))
                    }
                    AppIcon(app, Modifier.size(46.dp))
                    Column(Modifier.weight(1f)) {
                        Text(text(R.string.app_settings_title), style = MaterialTheme.typography.labelLarge,
                            color = colors.primary)
                        Text(app.label, style = MaterialTheme.typography.headlineSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    if (inlineActions) launchActions()
                }
                if (!inlineActions) launchActions()
                state.notice?.let { message ->
                    Text(text(message), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error)
                }
                if (wide) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SettingsPane(R.string.app_window_title, CockpitIcon.DISPLAY,
                            Modifier.weight(1.15f).fillMaxHeight(), scroll = true) {
                            AppWindowSettings(requireNotNull(state.window), model)
                        }
                        SettingsPane(R.string.apps_permissions, CockpitIcon.LOCK,
                            Modifier.weight(1f).fillMaxHeight(), scroll = true) {
                            PermissionSettings(state, model::permission, appInfo)
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        state.window?.let {
                            SettingsPane(R.string.app_window_title, CockpitIcon.DISPLAY, Modifier.fillMaxWidth()) {
                                AppWindowSettings(it, model)
                            }
                        }
                        SettingsPane(R.string.apps_permissions, CockpitIcon.LOCK, Modifier.fillMaxWidth()) {
                            PermissionSettings(state, model::permission, appInfo)
                        }
                    }
                }
            }
        }
    }
    choices?.let {
        DisplayPicker(it, windowsSupported(), state.window?.connection == Connection.ENABLED, { choices = null }) { id, window ->
            choices = null
            open(id, window)
        }
    }
}

@Composable
private fun SettingsPane(
    title: Int,
    icon: CockpitIcon,
    modifier: Modifier,
    scroll: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = MaterialTheme.shapes.large, color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant)) {
        val body = if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(body.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CockpitGlyph(icon, Modifier.size(23.dp))
                Text(text(title), style = MaterialTheme.typography.titleLarge)
            }
            content()
        }
    }
}

@Composable
private fun PermissionSettings(
    state: AppSettingsViewModel.State,
    set: (String, Boolean) -> Unit,
    appInfo: () -> Unit,
) {
    val panel = state.permissions
    val details = panel.details
    val colors = MaterialTheme.colorScheme
    val description = when {
        details == null && panel.busy -> R.string.ui_80
        details == null -> R.string.apps_permission_stock
        details.permissions.isEmpty() -> R.string.apps_permission_empty
        else -> R.string.apps_permission_persisted
    }
    Text(text(description), color = colors.onSurfaceVariant)
    details?.permissions?.forEach { item ->
        HorizontalDivider(color = colors.outlineVariant)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(
                value = item.granted, enabled = !panel.busy, role = Role.Switch,
                onValueChange = { set(item.name, it) },
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(item.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(item.granted, onCheckedChange = null, enabled = !panel.busy)
        }
    }
    panel.message?.let {
        Text(text(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error)
    }
    OutlinedButton(appInfo, Modifier.heightIn(min = 48.dp), enabled = !state.busy) {
        Text(text(R.string.apps_app_info))
    }
}

@Composable
private fun DisplayPicker(
    choices: List<AppsPlatform.DisplayChoice>,
    windowsSupported: Boolean,
    centralPolicy: Boolean,
    close: () -> Unit,
    open: (Int, Boolean) -> Unit,
) {
    var selected by remember { mutableStateOf<AppsPlatform.DisplayChoice?>(null) }
    val display = selected
    AlertDialog(
        onDismissRequest = close,
        title = { Text(display?.label ?: text(R.string.choose_display)) },
        text = {
            if (display == null) {
                Column(Modifier.heightIn(max = 225.dp).verticalScroll(rememberScrollState())) {
                    choices.forEach { choice ->
                        TextButton(
                            onClick = {
                                if (windowsSupported && (!centralPolicy || choice.id != Display.DEFAULT_DISPLAY)) selected = choice
                                else open(choice.id, false)
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        ) { Text(choice.label) }
                    }
                }
            } else Text(text(R.string.app_window_options))
        },
        confirmButton = {
            if (display != null) Button({ open(display.id, false) }) { Text(text(R.string.open_app)) }
        },
        dismissButton = {
            if (display != null) TextButton({ open(display.id, true) }) { Text(text(R.string.open_window)) }
            else TextButton(close) { Text(text(R.string.ui_89)) }
        },
    )
}

private fun text(id: Int) = UiText.get(id)
