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
import org.openvoyah.controls.domain.display.AppWindowConfiguration
import org.openvoyah.controls.domain.display.AppWindowProfile
import org.openvoyah.controls.domain.display.WindowConnection
import org.openvoyah.controls.domain.display.WindowProfileChange
import org.openvoyah.controls.domain.display.WindowProfileDelivery
import org.openvoyah.controls.platform.apps.AndroidAppAccess
import org.openvoyah.controls.platform.apps.AppPermissionControl
import org.openvoyah.controls.platform.apps.AppPermissions
import org.openvoyah.controls.platform.apps.AppStop
import org.openvoyah.controls.platform.apps.AppStopControl
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.platform.apps.PermissionChange

/** The key under which [AppsDestination] keeps this model, so a test can install a fixture before the grid opens. */
internal const val APP_SETTINGS_MODEL_KEY = "app-settings"

internal class AppSettingsViewModel(
    private val windows: AppWindowConfiguration,
    private val permissions: AppPermissionControl,
    private val stopper: AppStopControl,
) : ViewModel() {
    data class Window(
        val component: String,
        val profile: AppWindowProfile? = null,
        val connection: WindowConnection? = null,
        val busy: Boolean = false,
        val result: WindowProfileDelivery? = null,
        val message: Int? = null,
    )

    /** [writing] marks a change sent through the root shell, as opposed to a plain read from Android. */
    data class Permissions(
        val details: AppPermissions? = null,
        val busy: Boolean = false,
        val writing: Boolean = false,
        val message: Int? = null,
    )

    /** [available] is true while Controls may close the app and Android reports it as not stopped. */
    data class Stop(
        val available: Boolean = false,
        val busy: Boolean = false,
        val result: AppStop? = null,
    )

    data class State(
        val app: LocalApp,
        val window: Window?,
        val permissions: Permissions = Permissions(),
        val stop: Stop = Stop(),
        val notice: Int? = null,
    ) {
        val busy: Boolean get() = window?.busy == true || permissions.busy || stop.busy

        /** Closing needs the identity Android reported with the permissions, so a replaced package is not closed. */
        val canStop: Boolean get() = stop.available && permissions.details?.identity != null
    }

    private val mutable = MutableStateFlow<State?>(null)
    val state = mutable.asStateFlow()
    private var gridEntry: Long? = null
    /** The package a force stop is running for; it outlives a page that was closed by a new grid entry. */
    private var stopping: String? = null

    fun enterGrid(entry: Long) {
        if (gridEntry == entry) return
        gridEntry = entry
        // An explicit menu request returns immediately; pending adapter writes still finish once.
        mutable.value = null
    }

    fun open(app: LocalApp) {
        if (mutable.value != null) return
        val window = if (windows.supportsProfile(app.id)) Window(app.id) else null
        mutable.value = State(app, window, stop = Stop(busy = stopping == app.packageName))
        loadWindow()
        refresh()
    }

    fun loadWindow() {
        val window = mutable.value?.window ?: return
        if (window.profile != null) return
        updateWindow(window, R.string.app_window_read_failed) {
            val selection = windows.readProfile(window.component)
            window.copy(profile = selection.profile, connection = selection.connection)
        }
    }

    fun close() {
        mutable.update { if (it?.busy == true) it else null }
    }

    fun notice(message: Int?) = mutable.update { it?.copy(notice = message) }

    fun changeWindow(change: (AppWindowProfile) -> AppWindowProfile) {
        mutable.update { state ->
            val window = state?.window ?: return@update state
            val profile = window.profile ?: return@update state
            if (window.busy) return@update state
            val draft = change(profile)
            if (draft == profile) state
            else state.copy(window = window.copy(profile = draft, message = null, result = null))
        }
    }

    fun applyWindow() = saveWindow(WindowProfileChange.APPLY)

    fun resetWindow() = saveWindow(WindowProfileChange.RESET)

    private fun saveWindow(change: WindowProfileChange) {
        val window = mutable.value?.window ?: return
        val profile = window.profile ?: return
        updateWindow(window, R.string.app_window_save_failed) {
            val saved = windows.changeProfile(profile, change)
            window.copy(profile = saved.selection.profile, connection = saved.selection.connection,
                result = saved.delivery, message = null)
        }
    }

    fun enableWindows() {
        val window = mutable.value?.window ?: return
        if (window.connection != WindowConnection.DISABLED) return
        updateWindow(window, R.string.window_hook_requirement) {
            window.copy(connection = windows.enableService(), result = null, message = R.string.app_window_enabled)
        }
    }

    /** Reads the app again after Controls returns to the front, where Android may have changed it. */
    fun refresh() {
        refreshPermissions()
        refreshStop()
    }

    fun refreshPermissions() = updatePermissions()

    fun permission(name: String, granted: Boolean) {
        val details = mutable.value?.permissions?.details ?: return
        val identity = details.identity ?: return
        if (details.permissions.none { it.name == name && it.changeable }) return
        updatePermissions { permissions.set(identity, name, granted) }
    }

    fun specialAccess(access: AndroidAppAccess, allowed: Boolean) {
        val details = mutable.value?.permissions?.details ?: return
        val identity = details.identity ?: return
        if (details.specialAccess.none { it.access == access && it.changeable }) return
        updatePermissions { permissions.setSpecialAccess(identity, access, allowed) }
    }

    /** Called only after the page asked for confirmation; the close is one force stop, never repeated. */
    fun stopApp() {
        val current = mutable.value ?: return
        val identity = current.permissions.details?.identity ?: return
        if (current.busy || !current.canStop || stopping != null) return
        val packageName = current.app.packageName
        stopping = packageName
        mutable.update { it?.copy(stop = it.stop.copy(busy = true, result = null)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val outcome = try {
                    stopper.stop(identity)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    AppStop.Failed(null)
                }
                Stop(available = stoppable(packageName), result = outcome)
            }
            stopping = null
            // The result reaches the page of the same app, also one reopened while the stop was running.
            mutable.update { state -> if (state?.app?.packageName == packageName) state.copy(stop = result) else state }
        }
    }

    private fun refreshStop() {
        val current = mutable.value ?: return
        if (current.stop.busy) return
        val reading = current.stop
        viewModelScope.launch {
            val available = withContext(Dispatchers.IO) { stoppable(current.app.packageName) }
            mutable.update { state ->
                if (state != null && state.app.id == current.app.id && state.stop === reading) {
                    state.copy(stop = reading.copy(available = available))
                } else state
            }
        }
    }

    private fun stoppable(packageName: String): Boolean = try {
        stopper.canStop(packageName)
    } catch (_: RuntimeException) {
        false
    }

    private fun updateWindow(current: Window, failure: Int, operation: suspend () -> Window) {
        if (current.busy) return
        val working = current.copy(busy = true, message = null, result = null)
        mutable.update { it?.copy(window = working) }
        viewModelScope.launch {
            val result = try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                current.copy(busy = false, message = failure, result = null)
            }
            mutable.update { state -> if (state != null && state.window === working) state.copy(window = result) else state }
        }
    }

    private fun updatePermissions(operation: (() -> PermissionChange)? = null) {
        val current = mutable.value ?: return
        if (current.permissions.busy) return
        val working = current.permissions.copy(busy = true, writing = operation != null, message = null)
        mutable.update { it?.copy(permissions = working) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val outcome = try {
                    operation?.invoke()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    PermissionChange.UNCONFIRMED
                }
                val details = try {
                    permissions.read(current.app.packageName)
                } catch (_: RuntimeException) {
                    null
                }
                val message = when (outcome) {
                    PermissionChange.PACKAGE_CHANGED -> R.string.apps_permission_package_changed
                    PermissionChange.ANDROID_ONLY -> R.string.apps_permission_android_only
                    PermissionChange.UNCONFIRMED -> R.string.apps_permission_unavailable
                    else -> if (details == null) R.string.apps_permission_read_failed else null
                }
                Permissions(details = details, message = message)
            }
            mutable.update { state -> if (state != null && state.permissions === working) state.copy(permissions = result) else state }
        }
    }
}
