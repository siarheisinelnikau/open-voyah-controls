package org.openvoyah.controls.presentation.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.data.frida.FridaWindowControls
import org.openvoyah.controls.data.frida.FridaWindowControls.Connection
import org.openvoyah.controls.domain.display.AppWindowProfile
import org.openvoyah.controls.platform.apps.AppPermissions
import org.openvoyah.controls.platform.apps.InstalledAppPermissions
import org.openvoyah.controls.platform.apps.LocalApp

internal class AppSettingsViewModel(
    private val windows: FridaWindowControls,
    private val permissions: InstalledAppPermissions,
) : ViewModel() {
    data class Window(
        val component: String,
        val profile: AppWindowProfile? = null,
        val connection: Connection? = null,
        val busy: Boolean = false,
        val result: FridaWindowControls.Result? = null,
        val message: Int? = null,
    )

    data class Permissions(
        val details: AppPermissions? = null,
        val busy: Boolean = false,
        val message: Int? = null,
    )

    data class State(
        val app: LocalApp,
        val window: Window?,
        val permissions: Permissions = Permissions(),
        val notice: Int? = null,
    ) {
        val busy: Boolean get() = window?.busy == true || permissions.busy
    }

    private val mutable = MutableStateFlow<State?>(null)
    val state = mutable.asStateFlow()

    fun open(app: LocalApp) {
        if (mutable.value != null) return
        val window = if (windows.supports(app.id)) Window(app.id) else null
        mutable.value = State(app, window)
        if (window != null) updateWindow(window, R.string.app_window_failed) {
            window.copy(profile = windows.load(app.id), connection = windows.connection())
        }
        refreshPermissions()
    }

    fun close() {
        mutable.update { if (it?.busy == true) it else null }
    }

    fun notice(message: Int?) = mutable.update { it?.copy(notice = message) }

    fun changeWindow(change: (AppWindowProfile) -> AppWindowProfile) {
        mutable.update { state ->
            val window = state?.window ?: return@update state
            val profile = window.profile ?: return@update state
            if (window.busy) state
            else state.copy(window = window.copy(profile = change(profile), message = null, result = null))
        }
    }

    fun applyWindow(reset: Boolean) {
        val window = mutable.value?.window ?: return
        val profile = window.profile ?: return
        updateWindow(window, R.string.app_window_failed) {
            val result = windows.save(profile, reset)
            window.copy(profile = windows.load(window.component), connection = windows.connection(),
                result = result, message = null)
        }
    }

    fun enableWindows() {
        val window = mutable.value?.window ?: return
        if (window.connection != Connection.DISABLED) return
        updateWindow(window, R.string.window_hook_requirement) {
            windows.enable()
            window.copy(connection = windows.connection(), result = null, message = R.string.app_window_enabled)
        }
    }

    fun refreshPermissions() = updatePermissions()

    fun permission(name: String, granted: Boolean) {
        val identity = mutable.value?.permissions?.details?.identity ?: return
        updatePermissions { permissions.set(identity, name, granted) }
    }

    private fun updateWindow(current: Window, failure: Int, operation: () -> Window) {
        if (current.busy) return
        val working = current.copy(busy = true, message = null, result = null)
        mutable.update { it?.copy(window = working) }
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { operation() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                current.copy(busy = false, message = failure, result = null)
            }
            mutable.update { state -> if (state != null && state.window === working) state.copy(window = result) else state }
        }
    }

    private fun updatePermissions(operation: (() -> Boolean)? = null) {
        val current = mutable.value ?: return
        if (current.permissions.busy || !current.app.managed) return
        val working = current.permissions.copy(busy = true, message = null)
        mutable.update { it?.copy(permissions = working) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val changed = try {
                    operation?.invoke() ?: true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                val details = try {
                    permissions.read(current.app.packageName)
                } catch (_: RuntimeException) {
                    null
                }
                Permissions(details = details, message = if (!changed || details == null) R.string.apps_permission_unavailable else null)
            }
            mutable.update { state -> if (state != null && state.permissions === working) state.copy(permissions = result) else state }
        }
    }
}
