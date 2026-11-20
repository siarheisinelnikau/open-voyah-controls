package org.openvoyah.controls.presentation.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.apps.AppGridConfiguration
import org.openvoyah.controls.platform.apps.InstallOrigin
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitTextAction

@Composable
internal fun HiddenAppsDialog(
    model: AppGridSettingsViewModel,
    grid: AppGridConfiguration,
    enabled: Boolean,
    saveFailed: Boolean,
    close: () -> Unit,
    showApp: (String) -> Unit,
) {
    CockpitAlertDialog(
        onDismissRequest = close,
        title = { Text(UiText.get(R.string.apps_grid_hidden_apps)) },
        confirmButton = { CockpitTextAction(UiText.get(R.string.apps_desktop_done), close) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (saveFailed) CockpitFeedback(UiText.get(R.string.apps_grid_settings_save_failed), error = true)
                if (grid.hiddenApps.isEmpty()) {
                    Text(UiText.get(R.string.apps_grid_hidden_empty))
                } else {
                    val catalog by model.launcherApps.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
                    val loaded = catalog
                    if (loaded == null) {
                        Text(UiText.get(R.string.apps_loading))
                    } else {
                        if (loaded.isFailure) CockpitFeedback(UiText.get(R.string.apps_load_failed), error = true)
                        HiddenAppRows(grid, loaded.getOrNull().orEmpty(), enabled, showApp)
                    }
                }
            }
        },
    )
}

@Composable
private fun HiddenAppRows(grid: AppGridConfiguration, apps: List<LocalApp>, enabled: Boolean, showApp: (String) -> Unit) {
    val installed = remember(apps) { apps.associateBy(LocalApp::id) }
    val components = remember(grid.hiddenApps, installed) {
        grid.hiddenApps.sortedWith(compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) {
            installed[it]?.label ?: it
        }.thenBy { it })
    }
    val labelCounts = remember(components, installed) {
        components.mapNotNull { installed[it]?.label }.groupingBy { it }.eachCount()
    }
    if (!grid.showSystemApps && components.any { installed[it]?.origin == InstallOrigin.SYSTEM && it !in grid.pinnedApps }) {
        Text(UiText.get(R.string.apps_grid_hidden_system_filter), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LazyColumn(Modifier.heightIn(max = 244.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(components, key = { it }) { component ->
            val app = installed[component]
            val appLabel = app?.label?.takeIf { it.isNotBlank() }
            val label = appLabel ?: component.substringBefore('/')
            val detail = when {
                appLabel == null -> component.substringAfter('/')
                labelCounts[label] != 1 -> component
                else -> null
            }
            val accessibleLabel = UiText.get(R.string.apps_grid_show_app).format(
                if (detail == null) label else "$label, $detail",
            )
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (app != null) AppIcon(app, Modifier.size(36.dp), grid.iconStyle)
                Column(Modifier.weight(1f)) {
                    Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    detail?.let {
                        Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                CockpitAction(UiText.get(R.string.apps_grid_show), { showApp(component) },
                    Modifier.semantics { contentDescription = accessibleLabel }, enabled = enabled)
            }
        }
    }
}
