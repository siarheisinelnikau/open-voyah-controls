package org.openvoyah.controls.presentation.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.apps.AppGridConfiguration
import org.openvoyah.controls.domain.apps.AppGridPins
import org.openvoyah.controls.domain.apps.DefaultHideReason
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitTextAction

/**
 * Every app with its **Hide** choice. An app follows its default (firmware apps, keyboards, service apps and extra
 * launcher entries start hidden) until the user turns the switch; the row says which of the two applies.
 */
@Composable
internal fun HiddenAppsDialog(
    model: AppGridSettingsViewModel,
    grid: AppGridConfiguration,
    enabled: Boolean,
    saveFailed: Boolean,
    close: () -> Unit,
    setHidden: (LocalApp, Boolean) -> Unit,
    reset: () -> Unit,
) {
    CockpitAlertDialog(
        onDismissRequest = close,
        title = { Text(UiText.get(R.string.apps_grid_hidden_apps)) },
        confirmButton = { CockpitTextAction(UiText.get(R.string.apps_desktop_done), close) },
        dismissButton = if (grid.hiddenApps.isEmpty() && grid.shownApps.isEmpty()) null else {
            { if (enabled) CockpitTextAction(UiText.get(R.string.apps_grid_hidden_reset), reset) }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (saveFailed) CockpitFeedback(UiText.get(R.string.apps_grid_settings_save_failed), error = true)
                Text(UiText.get(R.string.apps_grid_hidden_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                val catalog by model.launcherApps.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
                val loaded = catalog
                if (loaded == null) {
                    Text(UiText.get(R.string.apps_loading))
                } else {
                    if (loaded.isFailure) CockpitFeedback(UiText.get(R.string.apps_load_failed), error = true)
                    HiddenAppRows(grid, loaded.getOrNull().orEmpty(), enabled, setHidden)
                }
            }
        },
    )
}

@Composable
private fun HiddenAppRows(grid: AppGridConfiguration, apps: List<LocalApp>, enabled: Boolean, setHidden: (LocalApp, Boolean) -> Unit) {
    val rows = remember(apps) { apps.filter { AppGridPins.supports(it.id) }.distinctBy(LocalApp::id) }
    val labelCounts = remember(rows) { rows.groupingBy(LocalApp::label).eachCount() }
    LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(rows, key = LocalApp::id) { app ->
            val hidden = grid.hides(app.id, app.hiddenByDefault)
            val own = app.id in grid.hiddenApps || app.id in grid.shownApps
            val reason = when {
                own -> UiText.get(R.string.apps_hide_choice)
                grid.hideByDefault -> app.defaultHide?.let { UiText.get(it.label()) }
                else -> null
            }
            val detail = listOfNotNull(reason, app.id.takeIf { labelCounts[app.label] != 1 }).joinToString(" · ").ifEmpty { null }
            Row(listItemMotion().fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(hidden, enabled = enabled, role = Role.Switch) { setHidden(app, it) },
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, Modifier.size(36.dp), grid.iconStyle)
                Column(Modifier.weight(1f)) {
                    Text(app.label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    detail?.let {
                        Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(hidden, onCheckedChange = null, enabled = enabled)
            }
        }
    }
}

private fun DefaultHideReason.label(): Int = when (this) {
    DefaultHideReason.FIRMWARE -> R.string.apps_hide_reason_firmware
    DefaultHideReason.MAKER -> R.string.apps_hide_reason_maker
    DefaultHideReason.KEYBOARD -> R.string.apps_hide_reason_keyboard
    DefaultHideReason.SERVICES -> R.string.apps_hide_reason_services
    DefaultHideReason.EXTRA_ENTRY -> R.string.apps_hide_reason_extra
}
