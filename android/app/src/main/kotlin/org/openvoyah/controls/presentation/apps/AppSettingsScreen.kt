package org.openvoyah.controls.presentation.apps

import android.view.Display
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog as AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import org.openvoyah.controls.presentation.theme.CockpitBackdrop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.display.WindowConnection
import org.openvoyah.controls.platform.apps.AndroidAppAccess
import org.openvoyah.controls.platform.apps.AppsPlatform
import org.openvoyah.controls.platform.apps.InstallOrigin
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitHeading
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.CockpitTouch
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout

internal data class AppShortcutGridState(
    val pinned: Boolean?,
    val showInAll: Boolean?,
    val isFavorites: Boolean,
    val working: Boolean,
    val notice: Int?,
)

@Composable
internal fun AppSettingsScreen(
    state: AppSettingsViewModel.State,
    model: AppSettingsViewModel,
    displays: () -> List<AppsPlatform.DisplayChoice>,
    windowsSupported: () -> Boolean,
    shortcut: AppShortcutGridState?,
    setPinned: (Boolean) -> Unit,
    setShowInAll: (Boolean) -> Unit,
    open: (Int?, Boolean) -> Unit,
    openAdjacent: (Int) -> Unit,
    appInfo: () -> Unit,
    specialAccess: (AndroidAppAccess) -> Unit,
) {
    val app = state.app
    val window = state.window
    val permissions = state.permissions
    val busy = state.busy
    val colors = MaterialTheme.colorScheme
    val layout = LocalCockpitLayout.current
    var choices by remember(app.id) { mutableStateOf<List<AppsPlatform.DisplayChoice>?>(null) }
    val launchActions: @Composable (Modifier) -> Unit = { modifier ->
        if (app.launchEntry != null) {
            FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (shortcut?.showInAll != null) {
                    val shown = shortcut.showInAll
                    // The shared switch row fills its parent; among these actions it keeps the width of its own content.
                    Box(Modifier.width(IntrinsicSize.Max)) {
                        CockpitSwitch(text(R.string.apps_grid_show_in_all), shown, !busy && !shortcut.working, setShowInAll)
                    }
                }
                if (shortcut?.pinned != null) {
                    val pinned = shortcut.pinned
                    val label = if (shortcut.isFavorites) {
                        if (pinned) R.string.apps_desktop_unpin else R.string.apps_desktop_pin
                    } else if (pinned) R.string.apps_unpin else R.string.apps_pin
                    CockpitAction(text(label), { setPinned(!pinned) }, enabled = !busy && !shortcut.working)
                }
                CockpitAction(text(R.string.choose_display), { choices = displays() }, enabled = !busy)
                CockpitAction(text(R.string.open_app), { open(null, false) }, enabled = !busy, primary = true)
            }
        }
    }
    CockpitBackdrop {
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = layout.spacing(22.dp), vertical = layout.spacing(17.dp)),
        ) {
            val wide = maxWidth >= 880.dp && window != null
            val inlineActions = maxWidth >= (if (shortcut == null) 680.dp else 1040.dp)
            val inlineActionWidth = maxWidth - 310.dp
            val spacing = Arrangement.spacedBy(layout.spacing(14.dp))
            Column(verticalArrangement = spacing) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = spacing, verticalAlignment = Alignment.CenterVertically) {
                    // Back needs no disabled state: the view model keeps the page open while a change is still in flight.
                    Box(Modifier.weight(1f)) { CockpitHeading(app.label, text(R.string.app_settings_title), model::close) }
                    if (inlineActions) launchActions(Modifier.widthIn(max = inlineActionWidth))
                }
                if (!inlineActions) launchActions(Modifier)
                (state.notice ?: shortcut?.notice)?.let { message ->
                    Text(text(message), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error)
                }
                if (wide) {
                    Row(Modifier.weight(1f), horizontalArrangement = spacing) {
                        SettingsPane(R.string.app_window_title, CockpitIcon.DISPLAY,
                            Modifier.weight(1.15f).fillMaxHeight(), scroll = true) {
                            AppWindowSettings(requireNotNull(window), model)
                        }
                        SettingsPane(R.string.apps_permissions, CockpitIcon.LOCK,
                            Modifier.weight(1f).fillMaxHeight(), scroll = true) {
                            PermissionSettings(permissions, app.origin, busy, model::permission,
                                model::refreshPermissions, appInfo, specialAccess)
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = spacing) {
                        window?.let {
                            SettingsPane(R.string.app_window_title, CockpitIcon.DISPLAY, Modifier.fillMaxWidth()) {
                                AppWindowSettings(it, model)
                            }
                        }
                        SettingsPane(R.string.apps_permissions, CockpitIcon.LOCK, Modifier.fillMaxWidth()) {
                            PermissionSettings(permissions, app.origin, busy, model::permission,
                                model::refreshPermissions, appInfo, specialAccess)
                        }
                    }
                }
            }
        }
    }
    choices?.let {
        DisplayPicker(it, windowsSupported(), window?.connection == WindowConnection.ENABLED,
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

/**
 * The card of CockpitSection with the same padding and gaps. CockpitSection itself cannot take a height from its
 * column or scroll on its own, which the side-by-side panes need.
 */
@Composable
private fun SettingsPane(
    title: Int,
    icon: CockpitIcon,
    modifier: Modifier,
    scroll: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val layout = LocalCockpitLayout.current
    Surface(modifier, shape = MaterialTheme.shapes.large, color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant)) {
        val body = if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(body.padding(layout.spacing(16.dp)), verticalArrangement = Arrangement.spacedBy(layout.spacing(11.dp))) {
            Row(horizontalArrangement = Arrangement.spacedBy(layout.spacing(12.dp)), verticalAlignment = Alignment.CenterVertically) {
                CockpitGlyph(icon, Modifier.size(23.dp))
                Text(text(title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            }
            content()
        }
    }
}

@Composable
private fun PermissionSettings(
    panel: AppSettingsViewModel.Permissions,
    appOrigin: InstallOrigin,
    busy: Boolean,
    set: (String, Boolean) -> Unit,
    refresh: () -> Unit,
    appInfo: () -> Unit,
    specialAccess: (AndroidAppAccess) -> Unit,
) {
    val details = panel.details
    val colors = MaterialTheme.colorScheme
    val layout = LocalCockpitLayout.current
    val description = when {
        details == null && panel.busy -> R.string.apps_permission_checking
        else -> when (details?.origin ?: appOrigin) {
            InstallOrigin.CONTROLS -> R.string.apps_permission_persisted
            InstallOrigin.ADB -> R.string.apps_source_adb
            InstallOrigin.OTHER -> R.string.apps_source_other
            InstallOrigin.UNKNOWN -> R.string.apps_source_unknown
            InstallOrigin.UNVERIFIED -> R.string.apps_source_unverified
            InstallOrigin.SYSTEM -> R.string.apps_permission_stock
        }
    }
    Text(text(description), color = colors.onSurfaceVariant)
    CockpitAction(text(R.string.apps_app_info), appInfo, enabled = !busy)
    panel.message?.let {
        Text(text(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error)
        CockpitAction(text(R.string.apps_permission_recheck), refresh, enabled = !panel.busy)
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
        if (item.changeable) {
            CockpitSwitch(item.label, item.granted, !panel.busy) { set(item.name, it) }
        } else Row(
            // The metrics of CockpitSwitch, so read-only rows line up with the switches between them.
            Modifier.fillMaxWidth().heightIn(min = CockpitTouch),
            horizontalArrangement = Arrangement.spacedBy(layout.spacing(10.dp)), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(item.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(text(if (item.granted) R.string.apps_permission_granted else R.string.apps_permission_not_granted),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
    if (details.specialAccess.isNotEmpty()) {
        HorizontalDivider(color = colors.outlineVariant)
        Text(text(R.string.apps_special_access), style = MaterialTheme.typography.titleMedium)
        Text(text(R.string.apps_special_handoff), color = colors.onSurfaceVariant)
        details.specialAccess.forEach { access ->
            CockpitAction(text(access.label()), { specialAccess(access) }, enabled = !busy)
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
                Column(Modifier.heightIn(max = 225.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { choice ->
                        CockpitChoice(choice.label) {
                            if (allowsWindow(choice) || choice.canOpenAlongside) selected = choice
                            else open(choice.id, false)
                        }
                    }
                }
            } else if (display.canOpenAlongside) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text(R.string.app_display_alongside_hint))
                    CockpitAction(text(R.string.app_display_alongside), { openAdjacent(display.id) }, Modifier.fillMaxWidth())
                }
            } else Text(text(R.string.app_window_options))
        },
        confirmButton = {
            if (display != null) CockpitAction(text(R.string.open_app), { open(display.id, false) }, primary = true)
        },
        dismissButton = {
            if (display != null && allowsWindow(display)) CockpitTextAction(text(R.string.open_window)) { open(display.id, true) }
            else CockpitTextAction(text(R.string.ui_89), close)
        },
    )
}

private fun text(id: Int) = UiText.get(id)
