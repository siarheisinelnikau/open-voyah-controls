package org.openvoyah.controls.presentation.display

import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.apps.DesktopInfoSources
import org.openvoyah.controls.domain.apps.DesktopClockFormat
import org.openvoyah.controls.domain.display.PassengerWorkspace
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAppPicker
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitDialogWidth
import org.openvoyah.controls.presentation.theme.CockpitDialogWindowMotion
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitModal
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import java.util.Date

private data class SlotChoice(val dock: Boolean, val index: Int) {
    fun title(): String = UiText.get(if (dock) R.string.passenger_workspace_dock_slot else R.string.passenger_workspace_app_slot)
        .format(index + 1)
}

@Composable
internal fun PassengerWorkspaceScreen(model: PassengerWorkspaceViewModel, topInsetPx: Int, open: (String) -> Unit, exit: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val layout = state.workspace
    var editing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<SlotChoice?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets(top = topInsetPx)))
                .fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(UiText.get(R.string.passenger_workspace_title), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.align(Alignment.CenterVertically))
                CockpitAction(UiText.get(R.string.passenger_workspace_settings), { editing = true }, enabled = layout != null)
                CockpitAction(UiText.get(R.string.cockpit_back), exit)
            }
            if (state.message != null) CockpitFeedback(state.message?.let(UiText::get), error = true)
            if (layout == null) {
                if (state.busy) CockpitFeedback(UiText.get(R.string.ui_80))
                else CockpitAction(UiText.get(R.string.app_window_retry), model::refresh)
                return@Column
            }
            if (layout.clock || layout.date) WorkspaceTime(layout)
            LazyVerticalGrid(GridCells.Adaptive(if (layout.compact) 135.dp else 210.dp),
                modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(layout.slots, key = { index, _ -> index }) { index, component ->
                    val label = component?.let { workspaceLabel(state, it) } ?: SlotChoice(false, index).title()
                    val bitmap = component?.let { state.apps[it]?.icon }
                    Surface(onClick = {
                        if (component == null) {
                            editing = true
                            picking = SlotChoice(false, index)
                        } else open(component)
                    }, enabled = !state.busy, shape = MaterialTheme.shapes.large,
                        tonalElevation = 2.dp, modifier = Modifier.heightIn(min = if (layout.compact) 82.dp else 124.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            if (bitmap == null) CockpitGlyph(CockpitIcon.APPS)
                            else Image(bitmap.asImageBitmap(), null, Modifier.size(if (layout.compact) 28.dp else 44.dp))
                            Text(label, style = MaterialTheme.typography.titleMedium, overflow = TextOverflow.Ellipsis, maxLines = 2)
                        }
                    }
                }
            }
            if (layout.dock.any { it != null }) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (component in layout.dock.filterNotNull()) {
                        CockpitAction(workspaceLabel(state, component), { open(component) }, enabled = !state.busy)
                    }
                }
            }
        }
    }
    if (editing && layout != null) {
        WorkspaceEditor(state, layout, picking, choose = { picking = it }, settings = model::update,
            close = { editing = false })
    }
}

@Composable
private fun WorkspaceTime(layout: PassengerWorkspace) {
    val context = LocalContext.current
    val flow = remember(context) { DesktopInfoSources(context).clock(DesktopClockFormat.SYSTEM) }
    val time by flow.collectAsStateWithLifecycle(initialValue = "")
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (layout.clock) Text(time, style = MaterialTheme.typography.titleLarge)
        if (layout.date) {
            val date = remember(time, context) { DateFormat.getMediumDateFormat(context).format(Date()) }
            Text(date, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkspaceEditor(
    state: PassengerWorkspaceViewModel.State,
    layout: PassengerWorkspace,
    picking: SlotChoice?,
    choose: (SlotChoice?) -> Unit,
    settings: ((PassengerWorkspace) -> PassengerWorkspace) -> Unit,
    close: () -> Unit,
) {
    fun back() { if (picking != null) choose(null) else close() }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CockpitDialogWindowMotion()
        Box(Modifier.padding(horizontal = 16.dp).widthIn(max = CockpitDialogWidth).fillMaxWidth()) {
            if (picking != null) {
                val assigned = if (picking.dock) layout.dock else layout.slots
                val occupied = assigned.filterIndexed { index, _ -> index != picking.index }.toSet()
                CockpitAppPicker(picking.title(), ::back, select = { entry ->
                    settings { it.setApp(picking.dock, picking.index, entry?.component()?.flattenToString()) }
                    choose(null)
                }, defaultChoice = UiText.get(R.string.passenger_workspace_empty_slot),
                    eligible = { PassengerWorkspace.eligible(it) && it !in occupied })
            } else CockpitModal(UiText.get(R.string.passenger_workspace_settings),
                headerAction = { CockpitTextAction(UiText.get(R.string.apps_desktop_done), close) }) {
                val enabled = !state.busy
                if (state.message != null) CockpitFeedback(state.message?.let(UiText::get), error = true)
                CockpitSwitch(R.string.passenger_workspace_compact, layout.compact, enabled = enabled) { compact ->
                    settings { it.copy(compact = compact) }
                }
                CockpitSwitch(R.string.passenger_workspace_clock, layout.clock, enabled = enabled) { clock ->
                    settings { it.copy(clock = clock) }
                }
                CockpitSwitch(R.string.passenger_workspace_date, layout.date, enabled = enabled) { date ->
                    settings { it.copy(date = date) }
                }
                CockpitSwitch(R.string.passenger_workspace_startup, layout.openOnStartup, enabled = enabled) { autostart ->
                    settings { it.copy(openOnStartup = autostart) }
                }
                listOf(false to layout.slots, true to layout.dock).forEach { (dock, slots) ->
                    slots.forEachIndexed { index, component ->
                        val slot = SlotChoice(dock, index)
                        CockpitChoice(slot.title(), component?.let { workspaceLabel(state, it) }, enabled = enabled) { choose(slot) }
                    }
                }
            }
        }
    }
}

private fun workspaceLabel(state: PassengerWorkspaceViewModel.State, component: String): String =
    state.apps[component]?.entry?.label() ?: UiText.get(R.string.passenger_workspace_missing_target).format(component.substringBefore('/'))
