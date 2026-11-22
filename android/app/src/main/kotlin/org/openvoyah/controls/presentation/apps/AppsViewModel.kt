package org.openvoyah.controls.presentation.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.domain.apps.AppGridConfiguration
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.AppGridHiddenAppsFull
import org.openvoyah.controls.domain.apps.AppGridManualOrderFull
import org.openvoyah.controls.domain.apps.AppGridPins
import org.openvoyah.controls.platform.apps.AppsCatalog
import org.openvoyah.controls.platform.apps.LocalApp

internal class AppsViewModel(
    private val catalog: AppsCatalog,
    private val grid: AppGridPreferences,
) : ViewModel() {
    data class State(
        val apps: List<LocalApp> = emptyList(),
        val loading: Boolean = true,
        val status: Int? = null,
        val grid: Result<AppGridConfiguration>? = null,
        val gridEdit: GridEdit? = null,
        val selection: DesktopSelection? = null,
    ) {
        fun desktopFor(entry: Long, entryDesktop: AppGridDesktop? = null): AppGridDesktop = grid?.getOrNull()?.desktops
            ?.selected(selection?.takeIf { it.entry == entry }?.desktop ?: entryDesktop) ?: AppGridDesktop.MAIN
    }

    data class DesktopSelection(val entry: Long, val desktop: AppGridDesktop)

    data class GridEdit(val component: String, val desktop: AppGridDesktop?, val error: Int? = null) {
        val working: Boolean get() = error == null
    }

    private val mutableState = MutableStateFlow(State())
    val state = combine(mutableState, grid.observeSettings()) { current, settings ->
        current.copy(grid = settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), State())
    private var refresh: Job? = null

    fun refresh() {
        refresh?.cancel()
        refresh = viewModelScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) { catalog.load() }
                mutableState.update { it.copy(apps = apps, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                mutableState.update { it.copy(loading = false, status = R.string.apps_load_failed) }
            }
        }
    }

    fun status(message: Int) = mutableState.update { it.copy(status = message) }

    fun selectDesktop(entry: Long, desktop: AppGridDesktop) {
        if (state.value.grid?.getOrNull()?.desktops?.enabled != true) return
        mutableState.update { it.copy(selection = DesktopSelection(entry, desktop)) }
    }

    fun changePin(app: LocalApp, pinned: Boolean, desktop: AppGridDesktop) = editGrid(app, desktop, R.string.apps_pin_save_failed) {
        grid.changePin(app.id, pinned, desktop)
    }

    fun swapPins(app: LocalApp, other: LocalApp, desktop: AppGridDesktop) = editGrid(app, desktop, R.string.apps_pin_save_failed) {
        grid.swapPins(app.id, other.id, desktop)
    }

    /** Moves [app] to the place of [target] in [visible], the app order [desktop] shows; see `AppGridConfiguration.move`. */
    fun moveApp(app: LocalApp, target: LocalApp, desktop: AppGridDesktop, visible: List<String>) =
        editGrid(app, desktop, R.string.apps_grid_move_failed) { grid.moveApp(desktop, visible, app.id, target.id) }

    fun setAppHidden(app: LocalApp, hidden: Boolean) = editGrid(app, null, R.string.apps_grid_settings_save_failed) {
        grid.setAppHidden(app.id, hidden, app.hiddenByDefault)
    }

    private fun editGrid(app: LocalApp, desktop: AppGridDesktop?, failure: Int, operation: suspend () -> Unit) {
        if (app.launchEntry == null || !AppGridPins.supports(app.id) || mutableState.value.gridEdit?.working == true) return
        val working = GridEdit(app.id, desktop)
        mutableState.update { it.copy(gridEdit = working) }
        viewModelScope.launch {
            val result = try {
                operation()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AppGridHiddenAppsFull) {
                working.copy(error = R.string.apps_grid_hidden_full)
            } catch (_: AppGridManualOrderFull) {
                working.copy(error = R.string.apps_grid_move_full)
            } catch (_: Exception) {
                working.copy(error = failure)
            }
            mutableState.update { it.copy(gridEdit = result) }
        }
    }

    fun clearGridFailure() = mutableState.update { state ->
        if (state.gridEdit?.error != null) state.copy(gridEdit = null) else state
    }
}
