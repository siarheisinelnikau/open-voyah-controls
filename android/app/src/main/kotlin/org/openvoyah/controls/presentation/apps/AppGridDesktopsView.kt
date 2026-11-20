package org.openvoyah.controls.presentation.apps

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.apps.AppGridConfiguration
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.AppGridDesktopNames
import org.openvoyah.controls.domain.apps.AppGridIconStyle
import org.openvoyah.controls.domain.apps.AppGridPins
import org.openvoyah.controls.platform.apps.InstallOrigin
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitMotion
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import java.util.Locale

@Composable
internal fun DesktopSwitcher(desktop: AppGridDesktop, showDesktops: Boolean, names: AppGridDesktopNames, select: (AppGridDesktop) -> Unit,
                             findApps: () -> Unit, reorder: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val spacing = Arrangement.spacedBy(8.dp)
    FlowRow(horizontalArrangement = spacing, verticalArrangement = spacing) {
        if (showDesktops) {
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = spacing, verticalArrangement = spacing) {
                AppGridDesktop.entries.forEach { option ->
                    val selected = desktop == option
                    val name = option.desktopLabel(names)
                    val accessibility = if (name != option.desktopLabel()) Modifier.semantics {
                        contentDescription = option.desktopLabel(names, includeSlot = true)
                    } else Modifier
                    Surface(
                        Modifier.heightIn(min = 48.dp).selectable(selected, role = Role.Tab) { select(option) }.then(accessibility),
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) colors.primaryContainer else colors.surface,
                        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
                        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
                    ) {
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(name, Modifier.widthIn(max = 172.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        if (desktop != AppGridDesktop.MAIN) {
            OutlinedButton(findApps, Modifier.heightIn(min = 48.dp)) {
                Text(text(R.string.apps_desktop_find))
            }
        }
        reorder?.let {
            OutlinedButton(it, Modifier.heightIn(min = 48.dp)) { Text(text(R.string.apps_grid_reorder_pins)) }
        }
    }
}

@Composable
internal fun PinnedAppsOrderDialog(
    apps: List<LocalApp>,
    iconStyle: AppGridIconStyle,
    enabled: Boolean,
    failed: Boolean,
    close: () -> Unit,
    swap: (LocalApp, LocalApp) -> Unit,
) {
    CockpitAlertDialog(
        onDismissRequest = close,
        title = { Text(text(R.string.apps_grid_reorder_pins)) },
        confirmButton = { CockpitTextAction(text(R.string.apps_desktop_done), close) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed) CockpitFeedback(text(R.string.apps_pin_save_failed), error = true)
                AppOrderRows(apps, iconStyle, enabled) { app, neighbor, _ -> swap(app, neighbor) }
            }
        },
    )
}

@Composable
internal fun AppOrderRows(
    apps: List<LocalApp>,
    iconStyle: AppGridIconStyle,
    enabled: Boolean,
    animateMoves: Boolean = false,
    move: (LocalApp, LocalApp, Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    var reveal by remember { mutableStateOf<AppOrderReveal?>(null) }
    LaunchedEffect(apps) {
        val request = reveal ?: return@LaunchedEffect
        reveal = null
        if (apps.getOrNull(request.to)?.id == request.app.id && apps.getOrNull(request.from)?.id == request.neighbor.id) {
            listState.requestScrollToItem(request.anchor, request.offset)
        }
    }

    fun moveRow(from: Int, to: Int) {
        val app = apps[from]
        val neighbor = apps.getOrNull(to) ?: return
        reveal = listState.revealAfterMove(app, neighbor, from, to)
        move(app, neighbor, to < from)
    }

    LazyColumn(Modifier.heightIn(max = 260.dp), state = listState) {
        itemsIndexed(apps, key = { _, app -> app.id }) { index, app ->
            val placement = if (animateMoves) Modifier.animateItem(fadeInSpec = null,
                placementSpec = tween(CockpitMotion.CHANGE), fadeOutSpec = null) else Modifier
            Row(placement.fillMaxWidth().heightIn(min = 52.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, Modifier.size(28.dp), iconStyle)
                Text(app.label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                AppOrderButton(R.string.apps_grid_reorder_up, R.string.apps_grid_reorder_up_for,
                    app, enabled && index > 0) { moveRow(index, index - 1) }
                AppOrderButton(R.string.apps_grid_reorder_down, R.string.apps_grid_reorder_down_for,
                    app, enabled && index < apps.lastIndex) { moveRow(index, index + 1) }
            }
        }
    }
}

private data class AppOrderReveal(
    val app: LocalApp,
    val neighbor: LocalApp,
    val from: Int,
    val to: Int,
    val anchor: Int,
    val offset: Int,
)

private fun LazyListState.revealAfterMove(app: LocalApp, neighbor: LocalApp, from: Int, to: Int): AppOrderReveal? {
    val first = firstVisibleItemIndex
    val layout = layoutInfo
    val destination = layout.visibleItemsInfo.find { it.index == to }
    // The stable key can otherwise keep the old top row anchored and put the swapped row above it.
    if (to <= first || destination == null) return AppOrderReveal(app, neighbor, from, to, to, 0)
    val clipped = (destination.offset + destination.size - layout.viewportEndOffset).coerceAtLeast(0)
    if (from == first || (to > from && clipped > 0)) {
        return AppOrderReveal(app, neighbor, from, to, first, firstVisibleItemScrollOffset + clipped)
    }
    return null
}

@Composable
private fun AppOrderButton(label: Int, announcement: Int, app: LocalApp, enabled: Boolean, move: () -> Unit) {
    val description = text(announcement).format(app.label)
    TextButton(move,
        Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics { contentDescription = description },
        enabled = enabled) {
        Text(text(label))
    }
}

@Composable
internal fun FavoriteAppsPicker(
    desktop: AppGridDesktop,
    grid: AppGridConfiguration,
    state: AppsViewModel.State,
    close: () -> Unit,
    changePin: (LocalApp, Boolean) -> Unit,
) {
    var query by remember(desktop) { mutableStateOf("") }
    val pins = grid.pins(desktop)
    val pinned = remember(pins) { pins.toSet() }
    val available = remember(state.apps, grid.showSystemApps, pins) {
        state.apps.filter {
            it.launchEntry != null && AppGridPins.supports(it.id) &&
                (grid.showSystemApps || it.origin != InstallOrigin.SYSTEM || it.id in pinned)
        }
    }
    val apps = remember(available, query) {
        val filter = query.trim().lowercase(Locale.ROOT)
        available.filter { it.matchesAppFilter(filter) }
    }
    val edit = state.gridEdit?.takeIf { it.desktop == desktop }
    CockpitAlertDialog(
        onDismissRequest = close,
        title = { Text(desktop.desktopLabel(grid.desktopNames)) },
        confirmButton = { CockpitTextAction(text(R.string.apps_desktop_done), close) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(text(R.string.ui_113)) })
                edit?.error?.let { CockpitFeedback(text(it), error = true) }
                if (apps.isEmpty()) {
                    val message = when {
                        state.loading -> R.string.apps_loading
                        query.isBlank() -> R.string.apps_desktop_no_available
                        else -> R.string.apps_empty
                    }
                    Text(text(message))
                } else {
                    LazyColumn(Modifier.heightIn(max = 232.dp)) {
                        items(apps, key = LocalApp::id) { app ->
                            val checked = app.id in pinned
                            val enabled = state.gridEdit?.working != true
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .toggleable(checked, enabled = enabled, role = Role.Checkbox) { changePin(app, it) }
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(app, Modifier.size(32.dp), grid.iconStyle)
                                Text(app.label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge)
                                Checkbox(checked, onCheckedChange = null, enabled = enabled)
                            }
                        }
                    }
                }
            }
        },
    )
}

internal fun AppGridDesktop.desktopLabel(): String = text(when (this) {
    AppGridDesktop.MAIN -> R.string.apps_desktop_all
    AppGridDesktop.FAVORITES_ONE -> R.string.apps_desktop_favorites_one
    AppGridDesktop.FAVORITES_TWO -> R.string.apps_desktop_favorites_two
})

internal fun AppGridDesktop.desktopLabel(names: AppGridDesktopNames, includeSlot: Boolean = false): String {
    val slot = desktopLabel()
    val custom = names.nameFor(this) ?: return slot
    return if (includeSlot && custom != slot) text(R.string.apps_grid_names_with_slot).format(custom, slot) else custom
}

internal fun LocalApp.matchesAppFilter(filter: String): Boolean =
    filter.isEmpty() || label.lowercase(Locale.ROOT).contains(filter) || packageName.lowercase(Locale.ROOT).contains(filter)

private fun text(id: Int): String = UiText.get(id)
