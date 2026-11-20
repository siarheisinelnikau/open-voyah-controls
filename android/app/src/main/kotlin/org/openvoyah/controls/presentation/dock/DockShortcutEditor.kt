package org.openvoyah.controls.presentation.dock

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.apps.AppGridPins
import org.openvoyah.controls.domain.dock.MAX_DOCK_APP_SHORTCUTS
import org.openvoyah.controls.presentation.theme.CockpitAppPicker
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout

@Composable
internal fun DockShortcutEditor(model: QuickActionsViewModel, components: List<String>, editable: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun edit(action: () -> Unit) { if (editable && lifecycle.currentState == Lifecycle.State.RESUMED) action() }
    val hasRoom = components.size < MAX_DOCK_APP_SHORTCUTS
    var picking by rememberSaveable(hasRoom) { mutableStateOf(false) }
    CockpitSection(UiText.get(R.string.quick_actions_app_shortcuts), CockpitIcon.APPS,
        UiText.get(R.string.quick_actions_app_shortcuts_hint).format(MAX_DOCK_APP_SHORTCUTS)) {
        if (components.isNotEmpty()) {
            val resolved by model.appShortcuts.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
            components.forEachIndexed { index, component -> key(component) {
                val app = resolved?.get(component)
                val label = app?.entry?.label() ?: component.substringAfter('/').substringAfterLast('.')
                val detail = when {
                    resolved != null && app == null -> UiText.get(R.string.quick_actions_app_missing).format(component)
                    app == null || resolved.orEmpty().values.count { it.entry.label() == label } > 1 -> component
                    else -> null
                }
                DockShortcutRow(label, detail, components.size > 1) {
                    if (components.size > 1) {
                        OrderButton(label, true, editable && index > 0, Modifier.testTag("quickActionsEarlierApp:$component")) {
                            edit { model.moveApp(component, true) }
                        }
                        OrderButton(label, false, editable && index < components.lastIndex, Modifier.testTag("quickActionsLaterApp:$component")) {
                            edit { model.moveApp(component, false) }
                        }
                    }
                    IconButton({ edit { model.removeApp(component) } }, enabled = editable,
                        modifier = Modifier.size(48.dp).testTag("quickActionsRemoveApp:$component")) {
                        Icon(Icons.Filled.Close, contentDescription = UiText.get(R.string.quick_actions_remove_app).format(label))
                    }
                }
            } }
        }
        if (hasRoom) {
            val title = UiText.get(R.string.quick_actions_add_app)
            CockpitChoice(title, enabled = editable, modifier = Modifier.testTag("quickActionsAddApp")) { edit { picking = true } }
            if (picking) Dialog(onDismissRequest = { picking = false }) {
                CockpitAppPicker(title, { picking = false }, select = { entry ->
                    picking = false
                    entry?.let { edit { model.addApp(it.component().flattenToString()) } }
                }, eligible = { AppGridPins.supports(it) && it !in components }, noChoices = UiText.get(R.string.quick_actions_no_more_apps))
            }
        }
    }
}

@Composable
private fun DockShortcutRow(label: String, detail: String?, showOrder: Boolean, buttons: @Composable () -> Unit) {
    val textWidth = LocalCockpitLayout.current.columnsMinimum(132.dp, LocalDensity.current.fontScale)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val name: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                detail?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (maxWidth < textWidth + if (showOrder) 144.dp else 48.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                name(Modifier.fillMaxWidth())
                Row { buttons() }
            }
        } else Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            name(Modifier.weight(1f))
            buttons()
        }
    }
}
