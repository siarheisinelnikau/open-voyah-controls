package org.openvoyah.controls.presentation.display

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.openvoyah.controls.InstalledApps
import org.openvoyah.controls.R
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.display.AppWindowProfile

internal class WorkspaceTasksViewModel(private val app: Context) : ViewModel() {
    private val shortcutSettings = AppGridPreferences(app)
    private val current = MutableStateFlow(State())
    val state = current.asStateFlow()

    data class Task(
        val id: Int,
        val component: String,
        val label: String,
        val launcherComponent: String?,
        val pinned: Boolean,
    )

    data class State(val items: List<Task>? = null, val busy: Boolean = false, val notice: Int? = null)

    fun refresh() = change(R.string.workspace_tasks_unavailable) { null }

    fun pin(task: Task) {
        val component = task.launcherComponent ?: return
        change(R.string.workspace_tasks_pin_failed) {
            shortcutSettings.changePin(component, !task.pinned, AppGridDesktop.MAIN)
            null
        }
    }

    fun close(task: Task) = change(R.string.workspace_tasks_close_unconfirmed) {
        when (FridaBridge(app).closeWorkspaceTask(task.id, task.component)) {
            "applied" -> null
            "no_task" -> R.string.workspace_tasks_gone
            "unsupported_task" -> R.string.workspace_tasks_close_unavailable
            else -> R.string.workspace_tasks_close_unconfirmed
        }
    }

    private fun change(failure: Int, operation: suspend () -> Int?) {
        val previous = current.value
        if (previous.busy) return
        current.value = previous.copy(busy = true, notice = null)
        viewModelScope.launch {
            val notice = try {
                withContext(Dispatchers.IO) { operation() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure
            }
            current.value = try {
                State(withContext(Dispatchers.IO) { readTasks() }, notice = notice)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                State(notice = notice ?: R.string.workspace_tasks_unavailable)
            }
        }
    }

    private suspend fun readTasks(): List<Task> {
        val status = FridaBridge(app).workspaceTasks()
        val raw = JSONArray(status)
        require(raw.length() <= MAX_ROWS)
        val pins = shortcutSettings.observeSettings().first().getOrThrow().pins(AppGridDesktop.MAIN)
        val records = (0 until raw.length()).map { index ->
            val entry = raw.getJSONObject(index)
            val id = entry.get("id") as Int
            val component = entry.get("component") as String
            require(id > 0 && AppWindowProfile.key(component) == component)
            id to component
        }
        require(records.distinctBy { it.first }.size == records.size)
        val packages = records.map { it.second.substringBefore('/') }.distinct()
        val candidates = packages.associateWith { pkg -> InstalledApps.list(app, pkg) }
        return records.map { (id, component) ->
            val pkg = component.substringBefore('/')
            val launcherApps = candidates.getValue(pkg)
            val launcher = launcherApps.firstOrNull { it.component().flattenToString() == component } ?: launcherApps.firstOrNull()
            val shortcut = launcher?.component()?.flattenToString()
            Task(id, component, launcher?.label() ?: pkg, shortcut, shortcut != null && shortcut in pins)
        }
    }

    companion object {
        private const val MAX_ROWS = 20
        fun supported() = Build.VERSION.SDK_INT == 30 && FridaBridge.windowHookSupported()
    }
}
