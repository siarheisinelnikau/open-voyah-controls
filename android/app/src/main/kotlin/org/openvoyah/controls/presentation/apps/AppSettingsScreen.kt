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
import org.openvoyah.controls.platform.apps.AndroidAppAccess
import org.openvoyah.controls.platform.apps.AppsPlatform
import org.openvoyah.controls.platform.apps.InstallOrigin
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon

@Composable
internal fun AppSettingsScreen(
    state: AppSettingsViewModel.State,
    model: AppSettingsViewModel,
    displays: () -> List<AppsPlatform.DisplayChoice>,
    windowsSupported: () -> Boolean,
    open: (Int?, Boolean) -> Unit,
    openAdjacent: (Int) -> Unit,
    appInfo: () -> Unit,
    specialAccess: (AndroidAppAccess) -> Unit,
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
                            PermissionSettings(state, model::permission, model::refreshPermissions, appInfo, specialAccess)
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
                            PermissionSettings(state, model::permission, model::refreshPermissions, appInfo, specialAccess)
                        }
                    }
                }
            }
        }
    }
    choices?.let {
        DisplayPicker(it, windowsSupported(), state.window?.connection == Connection.ENABLED,
            close = { choices = null },
            open = { id, window ->
                choices = null
                open(id, window)
            },
            openAdjacent = { id ->
                choices = null
                openAdjacent(id)
            },
        )
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
    refresh: () -> Unit,
    appInfo: () -> Unit,
    specialAccess: (AndroidAppAccess) -> Unit,
) {
    val panel = state.permissions
    val details = panel.details
    val colors = MaterialTheme.colorScheme
    val description = when {
        details == null && panel.busy -> R.string.apps_permission_checking
        else -> when (details?.origin ?: state.app.origin) {
            InstallOrigin.CONTROLS -> R.string.apps_permission_persisted
            InstallOrigin.ADB -> R.string.apps_source_adb
            InstallOrigin.OTHER -> R.string.apps_source_other
            InstallOrigin.UNKNOWN -> R.string.apps_source_unknown
            InstallOrigin.UNVERIFIED -> R.string.apps_source_unverified
            InstallOrigin.SYSTEM -> R.string.apps_permission_stock
        }
    }
    Text(text(description), color = colors.onSurfaceVariant)
    OutlinedButton(appInfo, Modifier.heightIn(min = 48.dp), enabled = !state.busy) {
        Text(text(R.string.apps_app_info))
    }
    panel.message?.let {
        Text(text(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error)
        TextButton(refresh, Modifier.heightIn(min = 48.dp), enabled = !panel.busy) {
            Text(text(R.string.apps_permission_recheck))
        }
    }
    if (details == null) return
    if (details.permissions.isEmpty()) {
        Text(text(R.string.apps_permission_empty), color = colors.onSurfaceVariant)
    } else if (details.origin == InstallOrigin.CONTROLS && details.permissions.any { !it.changeable }) {
        Text(text(R.string.apps_permission_android_only), color = colors.onSurfaceVariant)
    }
    if (details.hasUnavailableDeclarations) {
        Text(text(R.string.apps_permission_not_on_android), color = colors.onSurfaceVariant)
    }
    details.permissions.forEach { item ->
        HorizontalDivider(color = colors.outlineVariant)
        val toggle = if (item.changeable) {
            Modifier.toggleable(value = item.granted, enabled = !panel.busy, role = Role.Switch,
                onValueChange = { set(item.name, it) })
        } else Modifier
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).then(toggle),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(item.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (item.changeable) Switch(item.granted, onCheckedChange = null, enabled = !panel.busy)
            else Text(text(if (item.granted) R.string.apps_permission_granted else R.string.apps_permission_not_granted),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
    if (details.specialAccess.isNotEmpty()) {
        HorizontalDivider(color = colors.outlineVariant)
        Text(text(R.string.apps_special_access), style = MaterialTheme.typography.titleMedium)
        Text(text(R.string.apps_special_handoff), color = colors.onSurfaceVariant)
        details.specialAccess.forEach { access ->
            OutlinedButton({ specialAccess(access) }, Modifier.heightIn(min = 48.dp), enabled = !state.busy) {
                Text(text(access.label()))
            }
        }
    }
}

private fun AndroidAppAccess.label(): Int = when (this) {
    AndroidAppAccess.OVERLAY -> R.string.apps_special_overlay
    AndroidAppAccess.WRITE_SETTINGS -> R.string.apps_special_write_settings
    AndroidAppAccess.UNKNOWN_APPS -> R.string.apps_special_unknown_apps
    AndroidAppAccess.ALL_FILES -> R.string.apps_special_all_files
    AndroidAppAccess.USAGE -> R.string.apps_special_usage
    AndroidAppAccess.NOTIFICATION_LISTENER -> R.string.apps_special_notifications
    AndroidAppAccess.ACCESSIBILITY -> R.string.apps_special_accessibility
}

@Composable
private fun DisplayPicker(
    choices: List<AppsPlatform.DisplayChoice>,
    windowsSupported: Boolean,
    centralPolicy: Boolean,
    close: () -> Unit,
    open: (Int, Boolean) -> Unit,
    openAdjacent: (Int) -> Unit,
) {
    var selected by remember { mutableStateOf<AppsPlatform.DisplayChoice?>(null) }
    val display = selected
    fun allowsWindow(choice: AppsPlatform.DisplayChoice) =
        windowsSupported && (!centralPolicy || choice.id != Display.DEFAULT_DISPLAY)
    AlertDialog(
        onDismissRequest = close,
        title = { Text(display?.label ?: text(R.string.choose_display)) },
        text = {
            if (choices.isEmpty()) Text(text(R.string.app_display_no_choices))
            else if (display == null) {
                Column(Modifier.heightIn(max = 225.dp).verticalScroll(rememberScrollState())) {
                    choices.forEach { choice ->
                        TextButton(
                            onClick = {
                                if (allowsWindow(choice) || choice.canOpenAlongside) selected = choice
                                else open(choice.id, false)
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        ) { Text(choice.label) }
                    }
                }
            } else if (display.canOpenAlongside) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text(R.string.app_display_alongside_hint))
                    OutlinedButton({ openAdjacent(display.id) }, Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                        Text(text(R.string.app_display_alongside))
                    }
                }
            } else Text(text(R.string.app_window_options))
        },
        confirmButton = {
            if (display != null) Button({ open(display.id, false) }) { Text(text(R.string.open_app)) }
        },
        dismissButton = {
            if (display != null && allowsWindow(display)) TextButton({ open(display.id, true) }) { Text(text(R.string.open_window)) }
            else TextButton(close) { Text(text(R.string.ui_89)) }
        },
    )
}

private fun text(id: Int) = UiText.get(id)
