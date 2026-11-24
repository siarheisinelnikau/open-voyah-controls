package org.openvoyah.controls.presentation.dock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.dock.QuickAction
import org.openvoyah.controls.domain.dock.QuickActionAppsDestination
import org.openvoyah.controls.domain.dock.QuickActionArea
import org.openvoyah.controls.domain.dock.QuickActionCompactEdge
import org.openvoyah.controls.domain.dock.QuickActionCompactHandleSide
import org.openvoyah.controls.domain.dock.QuickActionCompactLabels
import org.openvoyah.controls.domain.dock.QuickActionHandleAccent
import org.openvoyah.controls.domain.dock.QuickActionHandleSize
import org.openvoyah.controls.domain.dock.QuickActionHeadlightContent
import org.openvoyah.controls.domain.dock.QuickActionHeadlightTap
import org.openvoyah.controls.domain.dock.QuickActionIdleCollapse
import org.openvoyah.controls.domain.dock.QuickActionRailAlignment
import org.openvoyah.controls.domain.dock.QuickActionRailWidth
import org.openvoyah.controls.domain.dock.QuickActionSettings
import org.openvoyah.controls.domain.dock.QuickActionSide
import org.openvoyah.controls.domain.dock.QuickActionStartState
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitOptionDialog
import org.openvoyah.controls.presentation.theme.CockpitReveal
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout

@Composable
internal fun QuickActionsDestination(model: QuickActionsViewModel, state: QuickActionsUiState, back: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current = state.settings ?: QuickActionSettings()
    val editable = state.settings != null && state.save != QuickActionSave.SAVING
    fun edit(block: () -> Unit) { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) block() }

    CockpitDetail(UiText.get(R.string.quick_actions_title), UiText.get(R.string.quick_actions_summary), back) {
        val options: @Composable () -> Unit = {
            CockpitSection(UiText.get(R.string.quick_actions_bar), CockpitIcon.SETTINGS) {
                CockpitSwitch(R.string.quick_actions_enabled, current.enabled, editable) { show ->
                    edit { model.setEnabled(show) }
                }
                if (current.enabled) {
                    DockChoice(R.string.quick_actions_area, current.area, QuickActionArea.entries, editable,
                        { it.title }) { area -> edit { model.setArea(area) } }
                    CockpitReveal(current.area.includes(apps = false)) {
                        CockpitSwitch(R.string.quick_actions_hide_on_virtual_display, current.hideOnVirtualDisplay, editable) { hide ->
                            edit { model.setHideOnVirtualDisplay(hide) }
                        }
                    }
                    CockpitSwitch(R.string.quick_actions_headlight_indicator, current.showHeadlightMode, editable) { show ->
                        edit { model.setShowHeadlightMode(show) }
                    }
                    RevealedRows(current.showHeadlightMode) {
                        DockChoice(R.string.quick_actions_headlight_content, current.headlightContent, QuickActionHeadlightContent.entries,
                            editable, { it.title }) { content -> edit { model.setHeadlightContent(content) } }
                        DockChoice(R.string.quick_actions_headlight_tap, current.headlightTap, QuickActionHeadlightTap.entries,
                            editable, { it.title }) { target -> edit { model.setHeadlightTap(target) } }
                    }
                    CockpitReveal(QuickAction.APPS in current.actions) {
                        DockChoice(R.string.quick_actions_apps_destination, current.appsDestination, QuickActionAppsDestination.entries, editable,
                            { it.title }) { destination -> edit { model.setAppsDestination(destination) } }
                    }
                    DockChoice(R.string.quick_actions_side, current.side, QuickActionSide.entries, editable,
                        { it.title }) { side -> edit { model.setSide(side) } }
                    DockChoice(R.string.quick_actions_wide_width, current.railWidth, QuickActionRailWidth.entries, editable,
                        { it.title }) { width -> edit { model.setRailWidth(width) } }
                    DockChoice(R.string.quick_actions_rail_alignment, current.sideRailAlignment, QuickActionRailAlignment.entries, editable,
                        { it.title }) { alignment -> edit { model.setRailAlignment(alignment) } }
                    DockChoice(R.string.quick_actions_compact_edge, current.compactEdge, QuickActionCompactEdge.entries, editable,
                        { it.title }) { edge -> edit { model.setCompactEdge(edge) } }
                    CockpitSwitch(R.string.quick_actions_labels, current.showLabels, editable) { labels ->
                        edit { model.setLabels(labels) }
                    }
                    DockChoice(R.string.quick_actions_compact_labels, current.compactLabels, QuickActionCompactLabels.entries, editable,
                        { it.title }) { labels -> edit { model.setCompactLabels(labels) } }
                    DockChoice(R.string.quick_actions_start_state, current.startState, QuickActionStartState.entries, editable,
                        { it.title }) { start -> edit { model.setStartState(start) } }
                    CockpitSwitch(R.string.quick_actions_collapse_after_use, current.collapseAfterUse, editable) { collapse ->
                        edit { model.setCollapseAfterUse(collapse) }
                    }
                    DockChoice(R.string.quick_actions_idle_collapse, current.idleCollapse, QuickActionIdleCollapse.entries, editable,
                        { it.title }) { timeout -> edit { model.setIdleCollapse(timeout) } }
                    RevealedRows(current.canCollapse) {
                        CockpitSwitch(R.string.quick_actions_keep_side_inset, current.keepSideInset, editable) { keep ->
                            edit { model.setKeepSideInset(keep) }
                        }
                        DockChoice(R.string.quick_actions_handle_size, current.handleSize, QuickActionHandleSize.entries, editable,
                            { it.title }) { size -> edit { model.setHandleSize(size) } }
                        DockChoice(R.string.quick_actions_side_handle_position, current.collapsedHandleAlignment,
                            QuickActionRailAlignment.entries, editable, { it.title }) { alignment ->
                            edit { model.setCollapsedHandleAlignment(alignment) }
                        }
                        DockChoice(R.string.quick_actions_compact_handle_side, current.compactHandleSide, QuickActionCompactHandleSide.entries, editable,
                            { it.title }) { side -> edit { model.setCompactHandleSide(side) } }
                        DockChoice(R.string.quick_actions_handle_accent, current.handleAccent, QuickActionHandleAccent.entries, editable,
                            { it.title }) { accent -> edit { model.setHandleAccent(accent) } }
                        CockpitSwitch(R.string.quick_actions_dim_idle_handle, current.dimIdleHandle, editable) { dim ->
                            edit { model.setDimIdleHandle(dim) }
                        }
                        CockpitSwitch(R.string.quick_actions_contrast_handle, current.highContrastHandle, editable) { contrast ->
                            edit { model.setHighContrastHandle(contrast) }
                        }
                        CockpitSwitch(R.string.quick_actions_compact_handle_label, current.showCompactHandleLabel, editable) { show ->
                            edit { model.setCompactHandleLabel(show) }
                        }
                    }
                    val hint = if (current.canCollapse) R.string.quick_actions_handle_hint else R.string.quick_actions_compact_hint
                    Text(UiText.get(hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.save == QuickActionSave.FAILED) CockpitFeedback(UiText.get(R.string.quick_actions_save_failed), error = true)
            }
        }
        if (current.enabled) CockpitColumns(first = { options() }, second = {
            DockShortcutEditor(model, current.appShortcuts, editable)
            ShortcutOrder(current.actions, editable,
                { action, show -> edit { model.select(action, show) } },
                { action, earlier -> edit { model.move(action, earlier) } }) {
                RoutineShortcutChoice(state, editable) { id -> edit { model.setRoutineShortcut(id) } }
                LargerShortcutsChoice(current, editable) { actions -> edit { model.setLargeShortcuts(actions) } }
            }
        }) else options()
    }
}

@Composable
private fun ShortcutOrder(actions: List<QuickAction>, editable: Boolean, select: (QuickAction, Boolean) -> Unit,
                          move: (QuickAction, Boolean) -> Unit, options: @Composable () -> Unit) {
    val minimumInline = LocalCockpitLayout.current.columnsMinimum(146.dp, LocalDensity.current.fontScale) + 96.dp
    CockpitSection(UiText.get(R.string.quick_actions_shortcuts), CockpitIcon.APPS, UiText.get(R.string.quick_actions_order_hint),
        guideId = R.string.quick_actions_shortcuts) {
        options()
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < minimumInline
            Column {
                (actions + QuickAction.entries.filterNot { it in actions }).forEach { action ->
                    key(action) {
                        val index = actions.indexOf(action)
                        val selected = index >= 0
                        val canToggle = editable && (!selected || actions.size > 1)
                        val title = UiText.get(action.target.title)
                        val toggle: @Composable (Modifier) -> Unit = { modifier ->
                            Row(modifier.heightIn(min = 48.dp).toggleable(selected, enabled = canToggle,
                                role = Role.Checkbox, onValueChange = { select(action, it) }),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(selected, onCheckedChange = null, enabled = canToggle)
                                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        val order: @Composable () -> Unit = {
                            if (selected && actions.size > 1) Row {
                                OrderButton(title, true, editable && index > 0) { move(action, true) }
                                OrderButton(title, false, editable && index < actions.lastIndex) { move(action, false) }
                            }
                        }
                        if (stacked) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                            toggle(Modifier.fillMaxWidth())
                            order()
                        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            toggle(Modifier.weight(1f))
                            order()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LargerShortcutsChoice(settings: QuickActionSettings, editable: Boolean, save: (Set<QuickAction>) -> Unit) {
    var draft by rememberSaveable { mutableStateOf<ArrayList<QuickAction>?>(null) }
    val current = settings.largeActions
    val title = UiText.get(R.string.quick_actions_larger_shortcuts)
    val value = if (current.isEmpty()) UiText.get(R.string.quick_actions_larger_shortcuts_none)
        else UiText.get(R.string.quick_actions_larger_shortcuts_selected).format(current.size)
    CockpitChoice(R.string.quick_actions_larger_shortcuts, value, editable) { draft = ArrayList(current) }
    val choices = draft ?: return
    val close = { draft = null }
    CockpitAlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                for (action in (settings.actions + QuickAction.entries).distinct()) {
                    val selected = action in choices
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(selected, enabled = editable,
                        role = Role.Checkbox) { enlarge ->
                        val pending = draft ?: return@toggleable
                        draft = ArrayList(if (enlarge) pending + action else pending - action)
                    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Checkbox(selected, onCheckedChange = null, enabled = editable)
                        Text(UiText.get(action.target.title))
                    }
                }
            }
        },
        confirmButton = {
            CockpitAction(UiText.get(R.string.ui_104), {
                draft?.let { save(it.toSet()) }
                close()
            }, enabled = editable, primary = true)
        },
        dismissButton = { CockpitTextAction(UiText.get(R.string.ui_89), close) },
    )
}

@Composable
private fun RoutineShortcutChoice(state: QuickActionsUiState, editable: Boolean, choose: (String?) -> Unit) {
    val current = state.settings?.routineShortcutId
    val routines = state.routines?.getOrNull().orEmpty()
    val choices = listOf(null) + routines.map { it.id }
    DockTextChoice(R.string.quick_actions_routine_shortcut, current, choices,
        editable && (current != null || routines.isNotEmpty()), { id ->
            when (id) {
                null -> UiText.get(R.string.quick_actions_routine_none)
                else -> routines.firstOrNull { it.id == id }?.name ?: UiText.get(R.string.quick_actions_routine_unavailable)
            }
        }, choose)
    if (state.routines?.isFailure == true) {
        CockpitFeedback(UiText.get(R.string.routines_load_failed), error = true)
    } else if (state.routines?.getOrNull()?.isEmpty() == true && current == null) {
        Text(UiText.get(R.string.routines_empty), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun OrderButton(title: String, earlier: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val description = UiText.get(if (earlier) R.string.quick_actions_earlier else R.string.quick_actions_later).format(title)
    ReorderButton(description, earlier, enabled, modifier, onClick)
}

/** The one reorder control of the app: a chevron pointing the way the row moves, named for the row and direction. */
@Composable
internal fun ReorderButton(description: String, earlier: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp).semantics { contentDescription = description }) {
        CockpitGlyph(CockpitIcon.ARROW, Modifier.rotate(if (earlier) -90f else 90f),
            tint = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
    }
}

/** Rows that depend on another setting grow into place instead of appearing at once and pushing the rest down. */
@Composable
private fun RevealedRows(visible: Boolean, rows: @Composable ColumnScope.() -> Unit) {
    CockpitReveal(visible) {
        // The gap between the rows of a CockpitSection.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalCockpitLayout.current.spacing(11.dp)),
            content = rows)
    }
}

@Composable
private fun <T> DockChoice(title: Int, current: T, choices: List<T>, enabled: Boolean, label: (T) -> Int, choose: (T) -> Unit) {
    DockTextChoice(title, current, choices, enabled, { UiText.get(label(it)) }, choose)
}

@Composable
private fun <T> DockTextChoice(title: Int, current: T, choices: List<T>, enabled: Boolean, label: (T) -> String, choose: (T) -> Unit) {
    var opened by rememberSaveable { mutableStateOf(false) }
    CockpitChoice(title, label(current), enabled) { opened = true }
    if (opened) CockpitOptionDialog(UiText.get(title), choices, current, label, onSelect = choose, onDismiss = { opened = false }, enabled = enabled)
}

private val QuickActionSide.title: Int get() = when (this) {
    QuickActionSide.LEFT -> R.string.quick_actions_left
    QuickActionSide.RIGHT -> R.string.quick_actions_right
}

private val QuickActionCompactEdge.title: Int get() = when (this) {
    QuickActionCompactEdge.TOP -> R.string.quick_actions_top
    QuickActionCompactEdge.BOTTOM -> R.string.quick_actions_bottom
}

private val QuickActionAppsDestination.title: Int get() = when (this) {
    QuickActionAppsDestination.DEFAULT_DESKTOP -> R.string.apps_desktop_default
    QuickActionAppsDestination.ALL_APPS -> R.string.apps_desktop_all
}

private val QuickActionHeadlightContent.title: Int get() = when (this) {
    QuickActionHeadlightContent.SWITCH -> R.string.headlight_mode_title
    QuickActionHeadlightContent.HEADLIGHTS -> R.string.headlight_activity_main
    QuickActionHeadlightContent.HIGH_BEAM -> R.string.headlight_activity_high
}

private val QuickActionHeadlightTap.title: Int get() = when (this) {
    QuickActionHeadlightTap.VEHICLE -> R.string.vehicle_settings_title
    QuickActionHeadlightTap.SOURCES -> R.string.headlight_sources_title
}

private val QuickActionRailWidth.title: Int get() = when (this) {
    QuickActionRailWidth.STANDARD -> R.string.quick_actions_width_standard
    QuickActionRailWidth.COMPACT -> R.string.quick_actions_width_compact
}

private val QuickActionRailAlignment.title: Int get() = when (this) {
    QuickActionRailAlignment.TOP -> R.string.quick_actions_top
    QuickActionRailAlignment.CENTER -> R.string.quick_actions_center
    QuickActionRailAlignment.BOTTOM -> R.string.quick_actions_bottom
}

private val QuickActionHandleSize.title: Int get() = when (this) {
    QuickActionHandleSize.STANDARD -> R.string.quick_actions_handle_size_standard
    QuickActionHandleSize.LARGE -> R.string.quick_actions_handle_size_large
}

private val QuickActionHandleAccent.title: Int get() = when (this) {
    QuickActionHandleAccent.THEME -> R.string.quick_actions_handle_accent_theme
    QuickActionHandleAccent.AMBER -> R.string.appearance_accent_amber
    QuickActionHandleAccent.TEAL -> R.string.appearance_accent_teal
}

private val QuickActionCompactHandleSide.title: Int get() = when (this) {
    QuickActionCompactHandleSide.DEFAULT -> R.string.quick_actions_compact_handle_side_default
    QuickActionCompactHandleSide.LEFT -> R.string.quick_actions_left
    QuickActionCompactHandleSide.RIGHT -> R.string.quick_actions_right
}

private val QuickActionIdleCollapse.title: Int get() = when (this) {
    QuickActionIdleCollapse.OFF -> R.string.quick_actions_idle_off
    QuickActionIdleCollapse.TEN_SECONDS -> R.string.quick_actions_idle_ten
    QuickActionIdleCollapse.THIRTY_SECONDS -> R.string.quick_actions_idle_thirty
}

private val QuickActionStartState.title: Int get() = when (this) {
    QuickActionStartState.DEFAULT -> R.string.quick_actions_start_default
    QuickActionStartState.EXPANDED -> R.string.quick_actions_start_expanded
    QuickActionStartState.COLLAPSED -> R.string.quick_actions_start_collapsed
}

private val QuickActionCompactLabels.title: Int get() = when (this) {
    QuickActionCompactLabels.DEFAULT -> R.string.quick_actions_compact_labels_default
    QuickActionCompactLabels.ICONS -> R.string.quick_actions_compact_labels_icons
    QuickActionCompactLabels.LABELS -> R.string.quick_actions_compact_labels_text
}

private val QuickActionArea.title: Int get() = when (this) {
    QuickActionArea.ALL -> R.string.quick_actions_all_screens
    QuickActionArea.APPS -> R.string.quick_actions_apps_area
    QuickActionArea.OTHER_SCREENS -> R.string.quick_actions_other_screens
}
