package org.openvoyah.controls.presentation.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.apps.AppGridConfiguration
import org.openvoyah.controls.domain.apps.AppGridManualOrder
import org.openvoyah.controls.domain.apps.AppGridOrderApp
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitTextAction

@Composable
internal fun ManualAppsOrderDialog(
    model: AppGridSettingsViewModel,
    grid: AppGridConfiguration,
    enabled: Boolean,
    saveError: Int?,
    canEdit: () -> Boolean,
    close: () -> Unit,
) {
    DisposableEffect(model) {
        onDispose(model::closeManualOrder)
    }
    val catalog by model.launcherApps.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val entries = catalog?.getOrNull().orEmpty()
    val sorted = remember(entries, grid.pinnedApps, grid.hideByDefault, grid.hiddenApps, grid.shownApps, grid.manualAppOrder) {
        grid.editableManualApps(entries, LocalApp::id, LocalApp::hiddenByDefault)
    }
    var confirmReset by remember { mutableStateOf(false) }

    if (confirmReset) {
        CockpitAlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(UiText.get(R.string.apps_grid_manual_order_reset)) },
            text = { Text(UiText.get(R.string.apps_grid_manual_order_reset_question)) },
            dismissButton = { CockpitTextAction(UiText.get(R.string.ui_89)) { confirmReset = false } },
            confirmButton = { CockpitAction(UiText.get(R.string.apps_grid_manual_order_reset), {
                if (enabled && canEdit()) {
                    confirmReset = false
                    model.resetManualAppOrder()
                }
            }, enabled = enabled, primary = true, destructive = true) },
        )
    } else {
        CockpitAlertDialog(
            onDismissRequest = close,
            title = { Text(UiText.get(R.string.apps_grid_edit_manual_order)) },
            confirmButton = { CockpitAction(UiText.get(R.string.apps_desktop_done), close, primary = true) },
            dismissButton = if (grid.manualAppOrder.isNotEmpty()) {
                { CockpitAction(UiText.get(R.string.apps_grid_manual_order_reset), { confirmReset = true }, enabled = enabled, destructive = true) }
            } else null,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (saveError != null) {
                        val message = UiText.get(saveError)
                        CockpitFeedback(if (saveError == R.string.apps_grid_manual_order_full) {
                            message.format(AppGridManualOrder.MAX_APPS)
                        } else message, error = true)
                    }
                    when {
                        catalog == null -> Text(UiText.get(R.string.apps_loading))
                        catalog?.isFailure == true -> CockpitFeedback(UiText.get(R.string.apps_load_failed), error = true)
                        sorted.size < 2 -> Text(UiText.get(R.string.apps_grid_manual_order_empty))
                        else -> {
                            Text(UiText.get(R.string.apps_grid_manual_order_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AppOrderRows(sorted, grid.iconStyle, enabled, animateMoves = true) { app, neighbor, earlier ->
                                if (canEdit()) {
                                    val catalogIds = entries.map { AppGridOrderApp(it.id, it.hiddenByDefault) }
                                    model.moveManualApp(catalogIds, app.id, neighbor.id, earlier)
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}
