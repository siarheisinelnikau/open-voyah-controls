package org.openvoyah.controls.presentation.display

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitDialogWidth
import org.openvoyah.controls.presentation.theme.CockpitDialogWindowMotion
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitModal
import org.openvoyah.controls.presentation.theme.CockpitTextAction

@Composable
internal fun WorkspaceTasksDialog(close: () -> Unit) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val model = remember(activity) {
        ViewModelProvider(activity, viewModelFactory {
            initializer { WorkspaceTasksViewModel(activity.applicationContext) }
        })[WorkspaceTasksViewModel::class.java]
    }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.refresh() }
    var closing by remember { mutableStateOf<WorkspaceTasksViewModel.Task?>(null) }
    fun active() = lifecycle.currentState == Lifecycle.State.RESUMED && !state.busy
    Dialog(onDismissRequest = { if (closing != null) closing = null else close() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CockpitDialogWindowMotion()
        Box(Modifier.padding(horizontal = 16.dp).widthIn(max = CockpitDialogWidth).fillMaxWidth()) {
            val pending = closing
            if (pending != null) {
                CockpitModal(UiText.get(R.string.workspace_tasks_close), footer = {
                    CockpitAction(UiText.get(R.string.ui_89), { closing = null })
                    CockpitAction(UiText.get(R.string.workspace_tasks_close), {
                        if (active()) {
                            closing = null
                            model.close(pending)
                        }
                    }, enabled = !state.busy, primary = true, destructive = true)
                }) {
                    Text(UiText.get(R.string.workspace_tasks_confirm_close).format(pending.label))
                }
            } else CockpitModal(UiText.get(R.string.workspace_tasks_title),
                headerAction = { CockpitTextAction(UiText.get(R.string.apps_desktop_done), close) },
                footer = { CockpitAction(UiText.get(R.string.workspace_tasks_refresh), { if (active()) model.refresh() },
                    enabled = !state.busy) }) {
                if (state.busy) CockpitFeedback(UiText.get(R.string.ui_80))
                state.notice?.let { CockpitFeedback(UiText.get(it), error = true) }
                if (state.items?.isEmpty() == true) {
                    Text(UiText.get(R.string.workspace_tasks_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.items.orEmpty().forEach { task ->
                    Text(task.label, style = MaterialTheme.typography.titleMedium)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (task.launcherComponent != null) {
                            val label = if (task.pinned) R.string.workspace_tasks_unpin else R.string.workspace_tasks_pin
                            CockpitAction(UiText.get(label), { if (active()) model.pin(task) }, enabled = !state.busy)
                        }
                        CockpitAction(UiText.get(R.string.workspace_tasks_close), { if (active()) closing = task },
                            enabled = !state.busy, destructive = true)
                    }
                }
            }
        }
    }
}
