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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.presentation.theme.SettingInfo
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
    LaunchedEffect(state.canStop) { if (!state.canStop) confirmStop = false }
    var confirmRemove by remember(app.id) { mutableStateOf(false) }
    LaunchedEffect(state.canRemove) { if (!state.canRemove) confirmRemove = false }
    val launchable = app.launchEntry != null
    CockpitBackdrop {
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = layout.spacing(22.dp), vertical = layout.spacing(17.dp)),
        ) {
            val wide = maxWidth >= 880.dp && window != null
            val gap = layout.spacing(14.dp)
            val spacing = Arrangement.spacedBy(gap)
            Column(verticalArrangement = spacing) {
                AppCardTop(gap,
                    // Back needs no disabled state: the view model keeps the page open while a change is still in flight.
                    heading = { CockpitHeading(app.label, text(R.string.app_settings_title), model::close, singleLine = true) },
                    appActions = {
                        AppActions(
                            stop = if (state.canStop) ({ confirmStop = true }) else null,
                            remove = if (state.canRemove) ({ confirmRemove = true }) else null,
                            shortcut, setHidden, busy,
                        )
                    },
                    launchActions = {
                        LaunchActions(shortcut, setPinned, move,
                            chooseDisplay = if (launchable) ({ choices = displays() }) else null,
                            open = if (launchable) ({ open(null, false) }) else null,
                            busy = busy,
                        )
                    },
                )
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
 * The heading and the card's actions in two lines, [appActions] then [launchActions], laid out by width alone:
 * both lines side by side next to the heading where they fit, else the two lines stacked next to the heading, else
 * the heading first with the actions under it. The actions keep their size in every state of the app, so the card
 * never switches between these while it is open, and the actions always end at the right edge.
 */
@Composable
private fun AppCardTop(
    gap: Dp,
    heading: @Composable () -> Unit,
    appActions: @Composable () -> Unit,
    launchActions: @Composable () -> Unit,
) {
    val groupGap = LocalCockpitLayout.current.spacing(ActionGroupGap)
    Layout(listOf(heading, appActions, launchActions), Modifier.fillMaxWidth()) { (headingPart, firstPart, secondPart), constraints ->
        val title = headingPart.single()
        val first = firstPart.single()
        val second = secondPart.single()
        val width = constraints.maxWidth
        val space = gap.roundToPx()
        val between = groupGap.roundToPx()
        val lines = ActionLineGap.roundToPx()
        val firstWidth = first.maxIntrinsicWidth(Constraints.Infinity)
        val secondWidth = second.maxIntrinsicWidth(Constraints.Infinity)
        val beside = width - space - MinHeadingWidth.roundToPx()
        val inline = maxOf(firstWidth, secondWidth) <= beside
        val available = if (inline) beside else width
        val sameLine = firstWidth + between + secondWidth <= available
        // A line wider than the page wraps between its groups; that too depends on the width only.
        val placedFirst = first.measure(Constraints(maxWidth = minOf(firstWidth, available)))
        val placedSecond = second.measure(Constraints(maxWidth = minOf(secondWidth, available)))
        val blockWidth = if (sameLine) placedFirst.width + between + placedSecond.width
            else maxOf(placedFirst.width, placedSecond.width)
        val blockHeight = if (sameLine) maxOf(placedFirst.height, placedSecond.height)
            else placedFirst.height + lines + placedSecond.height
        val placedTitle = title.measure(Constraints(maxWidth = if (inline) width - space - blockWidth else width))
        val height = if (inline) maxOf(blockHeight, placedTitle.height) else placedTitle.height + space + blockHeight
        val top = if (inline) (height - blockHeight) / 2 else placedTitle.height + space
        layout(width, height) {
            placedTitle.place(0, if (inline) (height - placedTitle.height) / 2 else 0)
            if (sameLine) {
                placedSecond.place(width - placedSecond.width, top + (blockHeight - placedSecond.height) / 2)
                placedFirst.place(width - placedSecond.width - between - placedFirst.width,
                    top + (blockHeight - placedFirst.height) / 2)
            } else {
                placedFirst.place(width - placedFirst.width, top)
                placedSecond.place(width - placedSecond.width, top + placedFirst.height + lines)
            }
        }
    }
}

/** Back and enough of the app's name to recognise it. */
private val MinHeadingWidth = 310.dp
private val ActionGroupGap = 28.dp
private val ActionLineGap = 8.dp

/*
 * The card's actions are always all there, in one order: Close app, Uninstall, Hide, then Pin, Move, Choose a
 * display and Open, the main action, at the very end. One that does not apply to the app right now stays in its
 * place dimmed, and Pin keeps the width of its longest title, so nothing moves when the app starts or stops, a pin
 * changes, the grid is still loading or another app's card opens. A null action is unavailable.
 */

/** What happens to the app itself: closing it, uninstalling it, hiding it from the list. */
@Composable
private fun AppActions(
    stop: (() -> Unit)?,
    remove: (() -> Unit)?,
    shortcut: AppShortcutGridState?,
    setHidden: (Boolean) -> Unit,
    busy: Boolean,
) {
    ActionLine {
        ActionGroup {
            // Closing stays apart from opening: it never takes the primary place at the end of the row.
            CockpitAction(text(R.string.apps_stop), { stop?.invoke() }, enabled = stop != null && !busy)
            CockpitAction(text(R.string.apps_remove), { remove?.invoke() }, enabled = remove != null && !busy,
                destructive = true)
        }
        val hidden = shortcut?.hidden
        // The shared switch row fills its parent; among these actions it keeps the width of its own content.
        Box(Modifier.width(IntrinsicSize.Max)) {
            CockpitSwitch(R.string.apps_hide, hidden == true, hidden != null && !busy && shortcut?.working != true, setHidden)
        }
    }
}

/** Where the app sits on the grid and where it opens. */
@Composable
private fun LaunchActions(
    shortcut: AppShortcutGridState?,
    setPinned: (Boolean) -> Unit,
    move: (() -> Unit)?,
    chooseDisplay: (() -> Unit)?,
    open: (() -> Unit)?,
    busy: Boolean,
) {
    val gridIdle = !busy && shortcut?.working != true
    ActionLine {
        ActionGroup {
            val pinned = shortcut?.pinned
            val title = when {
                shortcut?.isFavorites == true -> if (pinned == true) R.string.apps_desktop_unpin else R.string.apps_desktop_pin
                pinned == true -> R.string.apps_unpin
                else -> R.string.apps_pin
            }
            CockpitAction(text(title), { pinned?.let { setPinned(!it) } }, enabled = pinned != null && gridIdle,
                titles = PinTitles.map(::text))
            CockpitAction(text(R.string.apps_move), { move?.invoke() }, enabled = move != null && gridIdle)
        }
        ActionGroup {
            CockpitAction(text(R.string.choose_display), { chooseDisplay?.invoke() }, enabled = chooseDisplay != null && !busy)
            CockpitAction(text(R.string.open_app), { open?.invoke() }, enabled = open != null && !busy, primary = true)
        }
    }
}

private val PinTitles = listOf(R.string.apps_pin, R.string.apps_unpin, R.string.apps_desktop_pin, R.string.apps_desktop_unpin)

@Composable
private fun ActionLine(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LocalCockpitLayout.current.spacing(ActionGroupGap), Alignment.End),
        verticalArrangement = Arrangement.spacedBy(ActionLineGap)) { content() }
}

/** Actions that belong together; they wrap apart only on a page too narrow for the pair. */
@Composable
private fun ActionGroup(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(ActionLineGap)) { content() }
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
                Text(text(title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                SettingInfo(text(title), title)
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
            CockpitSwitch(item.label, item.granted, !panel.busy, guideId = R.string.apps_permissions) { set(item.name, it) }
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
                item.changeable -> CockpitSwitch(label, item.allowed == true, !panel.busy,
                    guideId = R.string.apps_permissions) { setAccess(item.access, it) }
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
