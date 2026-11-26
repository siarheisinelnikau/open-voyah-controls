package org.openvoyah.controls.presentation.display

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.data.display.PassengerWorkspacePreferences
import org.openvoyah.controls.domain.display.PassengerWorkspace
import org.openvoyah.controls.platform.apps.AppsPlatform
import org.openvoyah.controls.platform.apps.LauncherShortcut
import org.openvoyah.controls.platform.apps.LauncherShortcuts
import org.openvoyah.controls.platform.display.PassengerWorkspaceLauncher

internal class PassengerWorkspaceViewModel(app: Context) : ViewModel() {
    private val preferences = PassengerWorkspacePreferences(app)
    private val launcher = LauncherShortcuts(app)
    private val current = MutableStateFlow(State())
    val state = current.asStateFlow()

    data class State(
        val workspace: PassengerWorkspace? = null,
        val apps: Map<String, LauncherShortcut> = emptyMap(),
        val busy: Boolean = false,
        val message: Int? = null,
    )

    fun refresh() = load(R.string.passenger_workspace_read_failed, preferences::load)

    fun update(change: (PassengerWorkspace) -> PassengerWorkspace) =
        load(R.string.passenger_workspace_save_failed) { preferences.update(change) }

    private fun load(failure: Int, operation: () -> PassengerWorkspace) {
        val previous = current.value
        if (previous.busy) return
        current.value = previous.copy(busy = true, message = null)
        viewModelScope.launch {
            current.value = try {
                withContext(Dispatchers.IO) {
                    val workspace = operation()
                    State(workspace, launcher.resolve((workspace.slots + workspace.dock).filterNotNull().toSet()))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                previous.copy(message = failure)
            }
        }
    }

    fun open(activity: ComponentActivity, component: String) {
        fun active() = activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
            PassengerWorkspaceLauncher.onPassengerDisplay(activity) && !activity.isFinishing
        val previous = current.value
        if (!active() || previous.busy) return
        current.value = previous.copy(busy = true, message = null)
        viewModelScope.launch {
            val entry = withContext(Dispatchers.IO) { launcher.find(component) }
            val available = active() && PassengerWorkspaceLauncher.topInset(activity) >= 0
            current.value = previous.copy(message = when {
                !active() -> null
                entry == null -> R.string.passenger_workspace_app_missing
                !available -> R.string.passenger_workspace_unavailable
                else -> runCatching {
                    AppsPlatform(activity).launch(entry, displayId = PassengerWorkspaceLauncher.DISPLAY_ID)
                }.exceptionOrNull()?.let { R.string.passenger_workspace_launch_failed }
            })
        }
    }
}
