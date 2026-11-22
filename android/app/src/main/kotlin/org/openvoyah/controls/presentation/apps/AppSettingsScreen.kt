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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import org.openvoyah.controls.presentation.theme.CockpitBackdrop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
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
import org.openvoyah.controls.domain.apps.AppControlRefusal
import org.openvoyah.controls.domain.display.WindowConnection
import org.openvoyah.controls.platform.apps.AndroidAppAccess
import org.openvoyah.controls.platform.apps.AppRemoval
import org.openvoyah.controls.platform.apps.AppStop
import org.openvoyah.controls.platform.apps.AppsPlatform
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitHeading
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitReveal
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.CockpitTouch
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout

internal data class AppShortcutGridState(
    val pinned: Boolean?,
    val hidden: Boolean?,
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
    setHidden: (Boolean) -> Unit,
    /** Starts moving the app on the grid; null where the current desktop does not show it. */
    move: (() -> Unit)?,
    open: (Int?, Boolean) -> Unit,
    openAdjacent: (Int) -> Unit,
    appInfo: (() -> Unit)?,
    specialAccess: (AndroidAppAccess) -> (() -> Unit)?,
) {
    val app = state.app
    val window = state.window
    val permissions = state.permissions
    val busy = state.busy
    val colors = MaterialTheme.colorScheme
    val layout = LocalCockpitLayout.current
    var choices by remember(app.id) { mutableStateOf<List<AppsPlatform.DisplayChoice>?>(null) }
    // Not saved across recreation: a confirmation must follow a tap on Close app in this page's lifetime.
    var confirmStop by remember(app.id) { mutableStateOf(false) }
    val showStop = state.canStop || state.stop.busy
    LaunchedEffect(state.canStop) { if (!state.canStop) confirmStop = false }
    var confirmRemove by remember(app.id) { mutableStateOf(false) }
    val showRemove = state.canRemove || state.remove.busy
    LaunchedEffect(state.canRemove) { if (!state.canRemove) confirmRemove = false }
    val launchActions: @Composable (Modifier) -> Unit = { modifier ->
        if (app.launchEntry != null || showStop || showRemove) {
            FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Closing stays apart from opening: it never takes the primary place at the end of the row.
                if (showStop) CockpitAction(text(R.string.apps_stop), { confirmStop = true }, enabled = !busy)
                if (showRemove) CockpitAction(text(R.string.apps_remove), { confirmRemove = true }, enabled = !busy, destructive = true)
                if (app.launchEntry == null) return@FlowRow
                if (shortcut?.hidden != null) {
                    val hidden = shortcut.hidden
                    // The shared switch row fills its parent; among these actions it keeps the width of its own content.
                    Box(Modifier.width(IntrinsicSize.Max)) {
                        CockpitSwitch(text(R.string.apps_hide), hidden, !busy && !shortcut.working, setHidden)
                    }
                }
                if (shortcut?.pinned != null) {
                    val pinned = shortcut.pinned
                    val label = if (shortcut.isFavorites) {
                        if (pinned) R.string.apps_desktop_unpin else R.string.apps_desktop_pin
                    } else if (pinned) R.string.apps_unpin else R.string.apps_pin
                    CockpitAction(text(label), { setPinned(!pinned) }, enabled = !busy && !shortcut.working)
                }
                move?.let { CockpitAction(text(R.string.apps_move), it, enabled = !busy && shortcut?.working != true) }
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
                // Root work on this page takes a moment; the bar shows it for as long as it runs.
                if (state.stop.busy || permissions.writing) LinearProgressIndicator(Modifier.fillMaxWidth())
                StopResult(state.stop.result, app.label)
                (state.remove.result as? AppRemoval.Failed)?.let { failed ->
                    Text(listOfNotNull(text(R.string.apps_remove_failed), failed.reason).joinToString(" "),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error)
                }
                when (state.remove.result) {
                    AppRemoval.Refused -> Text(text(R.string.apps_remove_refused), color = colors.error)
                    AppRemoval.PackageChanged -> Text(text(R.string.apps_stop_package_changed), color = colors.error)
                    else -> Unit
                }
                if (wide) {
                    Row(Modifier.weight(1f), horizontalArrangement = spacing) {
                        SettingsPane(R.string.app_window_title, CockpitIcon.DISPLAY,
                            Modifier.weight(1.15f).fillMaxHeight(), scroll = true) {
                            AppWindowSettings(requireNotNull(window), model)
                        }
                        // The column scrolls as a whole and each pane keeps its own height, so a tall pane never
                        // squeezes another one to nothing; Permissions comes first because it matters most here.
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = spacing) {
                            SettingsPane(R.string.apps_permissions, CockpitIcon.LOCK, Modifier.fillMaxWidth()) {
                                PermissionSettings(permissions, busy, model::permission, model::specialAccess,
                                    model::refreshPermissions, appInfo, specialAccess)
                            }
                            SettingsPane(R.string.app_icon_title, CockpitIcon.APPS, Modifier.fillMaxWidth()) {
                                AppIconSettings(app)
                            }
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
                            PermissionSettings(permissions, busy, model::permission, model::specialAccess,
                                model::refreshPermissions, appInfo, specialAccess)
                        }
                        SettingsPane(R.string.app_icon_title, CockpitIcon.APPS, Modifier.fillMaxWidth()) {
                            AppIconSettings(app)
                        }
                    }
                }
            }
        }
    }
    if (confirmStop && state.canStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(text(R.string.apps_stop_confirm_title).format(app.label)) },
            text = { Text(text(R.string.apps_stop_confirm_text)) },
            confirmButton = {
                CockpitAction(text(R.string.apps_stop_confirm), { confirmStop = false; model.stopApp() }, enabled = !busy,
                    primary = true)
            },
            dismissButton = { CockpitTextAction(text(R.string.ui_89)) { confirmStop = false } },
        )
    }
    if (confirmRemove && state.canRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(text(R.string.apps_remove_confirm_title).format(app.label)) },
            text = { Text(text(R.string.apps_remove_confirm_text)) },
            confirmButton = {
                CockpitAction(text(R.string.apps_remove_confirm), { confirmRemove = false; model.removeApp() }, enabled = !busy,
                    destructive = true)
            },
            dismissButton = { CockpitTextAction(text(R.string.ui_89)) { confirmRemove = false } },
        )
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

/** The last close result. A failure reads like the other errors on the page; a closed app reads like a status. */
@Composable
private fun StopResult(result: AppStop?, label: String) {
    val colors = MaterialTheme.colorScheme
    // The last result stays while the message shrinks away, so it does not empty first.
    val previous = remember { mutableStateOf(result) }
    SideEffect { if (result != null) previous.value = result }
    val shown = result ?: previous.value
    CockpitReveal(result != null) {
        val last = shown ?: return@CockpitReveal
        val message = when (last) {
            AppStop.Stopped -> text(R.string.apps_stop_done).format(label)
            AppStop.Restarted -> text(R.string.apps_stop_restarted).format(label)
            AppStop.Refused -> text(R.string.apps_stop_refused)
            AppStop.PackageChanged -> text(R.string.apps_stop_package_changed)
            is AppStop.Failed -> listOfNotNull(text(R.string.apps_stop_failed), last.reason).joinToString(" ")
        }
        Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = if (last == AppStop.Stopped) colors.onSurfaceVariant else colors.error)
    }
}

@Composable
private fun PermissionSettings(
    panel: AppSettingsViewModel.Permissions,
    busy: Boolean,
    set: (String, Boolean) -> Unit,
    setAccess: (AndroidAppAccess, Boolean) -> Unit,
    refresh: () -> Unit,
    appInfo: (() -> Unit)?,
    specialAccess: (AndroidAppAccess) -> (() -> Unit)?,
) {
    val details = panel.details
    val colors = MaterialTheme.colorScheme
    val description = when {
        details == null -> if (panel.busy) R.string.apps_permission_checking else null
        details.identity == null -> R.string.apps_permission_read_failed
        else -> when (details.refusal) {
            null -> R.string.apps_permission_managed
            AppControlRefusal.CONTROLS -> R.string.apps_permission_controls
            AppControlRefusal.PREINSTALLED -> R.string.apps_permission_preinstalled
            AppControlRefusal.CAR_ROLE, AppControlRefusal.BOUND_SERVICE -> R.string.apps_permission_car_role
            AppControlRefusal.SYSTEM_IDENTITY -> R.string.apps_permission_system_identity
        }
    }
    description?.let { Text(text(it), color = colors.onSurfaceVariant) }
    panel.message?.let {
        Text(text(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error)
        CockpitAction(text(R.string.apps_permission_recheck), refresh, enabled = !panel.busy)
    }
    if (details == null) return
    val manageable = details.refusal == null && details.identity != null
    val someFixed = details.permissions.any { !it.changeable }
    // Android's own page only where the firmware has one, and only for what Controls cannot change here.
    if ((!manageable || someFixed) && appInfo != null) CockpitAction(text(R.string.apps_app_info), appInfo, enabled = !busy)
    if (details.permissions.isEmpty()) {
        Text(text(R.string.apps_permission_empty), color = colors.onSurfaceVariant)
    } else if (manageable && someFixed) {
        Text(text(R.string.apps_permission_android_only), color = colors.onSurfaceVariant)
    }
    if (details.hasUnavailableDeclarations) {
        Text(text(R.string.apps_permission_not_on_android), color = colors.onSurfaceVariant)
    }
    details.permissions.forEach { item ->
        HorizontalDivider(color = colors.outlineVariant)
        if (item.changeable) {
            CockpitSwitch(item.label, item.granted, !panel.busy) { set(item.name, it) }
        } else StatusRow(item.label, if (item.granted) R.string.apps_permission_granted else R.string.apps_permission_not_granted)
    }
    if (details.specialAccess.isNotEmpty()) {
        HorizontalDivider(color = colors.outlineVariant)
        Text(text(R.string.apps_special_access), style = MaterialTheme.typography.titleMedium)
        val handoffs = details.specialAccess.associate { it.access to if (it.changeable) null else specialAccess(it.access) }
        if (handoffs.values.any { it != null }) Text(text(R.string.apps_special_handoff), color = colors.onSurfaceVariant)
        details.specialAccess.forEach { item ->
            val label = text(item.access.label())
            val handoff = handoffs[item.access]
            when {
                item.changeable -> CockpitSwitch(label, item.allowed == true, !panel.busy) { setAccess(item.access, it) }
                handoff != null -> CockpitAction(label, handoff, enabled = !busy)
                else -> StatusRow(label, when (item.allowed) {
                    true -> R.string.apps_permission_granted
                    false -> R.string.apps_permission_not_granted
                    null -> R.string.apps_special_android_only
                })
            }
        }
    }
}

/** A read-only entry with the metrics of [CockpitSwitch], so it lines up with the switches between rows. */
@Composable
private fun StatusRow(label: String, status: Int) {
    val layout = LocalCockpitLayout.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = CockpitTouch),
        horizontalArrangement = Arrangement.spacedBy(layout.spacing(10.dp)), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(text(status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
