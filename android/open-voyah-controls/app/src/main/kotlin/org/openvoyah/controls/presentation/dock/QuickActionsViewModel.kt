package org.openvoyah.controls.presentation.dock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.domain.dock.QuickAction
import org.openvoyah.controls.domain.dock.QuickActionAppsDestination
import org.openvoyah.controls.domain.dock.QuickActionArea
import org.openvoyah.controls.domain.dock.QuickActionCompactEdge
import org.openvoyah.controls.domain.dock.QuickActionCompactHandleSide
import org.openvoyah.controls.domain.dock.QuickActionCompactLabels
import org.openvoyah.controls.domain.dock.QuickActionHandleAccent
import org.openvoyah.controls.domain.dock.QuickActionHandleSize
import org.openvoyah.controls.domain.dock.QuickActionHeadlightContent
import org.openvoyah.controls.domain.dock.QuickActionHeadlightTap
import org.openvoyah.controls.domain.dock.QuickActionIdleCollapse
import org.openvoyah.controls.domain.dock.QuickActionRailAlignment
import org.openvoyah.controls.domain.dock.QuickActionRailWidth
import org.openvoyah.controls.domain.dock.QuickActionSettings
import org.openvoyah.controls.domain.dock.QuickActionSide
import org.openvoyah.controls.domain.dock.QuickActionStartState
import org.openvoyah.controls.domain.dock.QuickActionStore
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.routines.RoutineLibrary
import org.openvoyah.controls.platform.apps.LauncherShortcut
import org.openvoyah.controls.platform.apps.LauncherShortcuts

internal enum class QuickActionSave { IDLE, SAVING, FAILED }

internal data class QuickActionsUiState(
    val settings: QuickActionSettings? = null,
    val save: QuickActionSave = QuickActionSave.IDLE,
    val routines: Result<List<NamedRoutine>>? = null,
) {
    val routineShortcut: NamedRoutine? get() =
        routines?.getOrNull()?.firstOrNull { it.id == settings?.routineShortcutId }
}

internal class QuickActionsViewModel(
    private val store: QuickActionStore,
    private val apps: LauncherShortcuts,
    routines: Flow<Result<RoutineLibrary>>,
) : ViewModel() {
    private val save = MutableStateFlow(QuickActionSave.IDLE)
    private val routineEntries = routines.map { it.map(RoutineLibrary::entries) }
        .onStart<Result<List<NamedRoutine>>?> { emit(null) }.distinctUntilChanged()
    val state = combine(store.observe(), save, routineEntries, ::QuickActionsUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), QuickActionsUiState())

    private val refresh = MutableStateFlow(0)
    private val requestedApps = state.map { it.settings?.appShortcuts.orEmpty().toSet() }.distinctUntilChanged()
    val appShortcuts: StateFlow<Map<String, LauncherShortcut>?> = combine(requestedApps, refresh) { components, _ ->
        apps.resolve(components)
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), null)

    suspend fun launcherEntry(component: String) = withContext(Dispatchers.IO) { apps.find(component) }
    fun refreshAppShortcuts() = refresh.update { it + 1 }
    fun addApp(component: String) = change { it.addApp(component) }
    fun removeApp(component: String) = change { it.removeApp(component) }
    fun moveApp(component: String, earlier: Boolean) = change { it.moveApp(component, earlier) }
    fun setRoutineShortcut(id: String?) {
        if (id == null || state.value.routines?.getOrNull()?.any { it.id == id } == true) {
            change { it.copy(routineShortcutId = id) }
        }
    }

    fun setEnabled(enabled: Boolean) = change { it.copy(enabled = enabled) }
    fun setSide(side: QuickActionSide) = change { it.copy(side = side) }
    fun setCompactEdge(edge: QuickActionCompactEdge) = change { it.copy(compactEdge = edge) }
    fun setCollapseAfterUse(collapse: Boolean) = change { it.copy(collapseAfterUse = collapse) }
    fun setIdleCollapse(timeout: QuickActionIdleCollapse) = change { it.copy(idleCollapse = timeout) }
    fun setStartState(state: QuickActionStartState) = change { it.copy(startState = state) }
    fun setCompactLabels(labels: QuickActionCompactLabels) = change { it.copy(compactLabels = labels) }
    fun setHighContrastHandle(enabled: Boolean) = change { it.copy(highContrastHandle = enabled) }
    fun setHandleAccent(accent: QuickActionHandleAccent) = change { it.copy(handleAccent = accent) }
    fun setDimIdleHandle(enabled: Boolean) = change { it.copy(dimIdleHandle = enabled) }
    fun setHandleSize(size: QuickActionHandleSize) = change { it.copy(handleSize = size) }
    fun setCollapsedHandleAlignment(alignment: QuickActionRailAlignment) = change { it.copy(collapsedHandleAlignment = alignment) }
    fun setCompactHandleSide(side: QuickActionCompactHandleSide) = change { it.copy(compactHandleSide = side) }
    fun setCompactHandleLabel(show: Boolean) = change { it.copy(showCompactHandleLabel = show) }
    fun setAppsDestination(destination: QuickActionAppsDestination) = change { it.copy(appsDestination = destination) }
    fun setRailWidth(width: QuickActionRailWidth) = change { it.copy(railWidth = width) }
    fun setRailAlignment(alignment: QuickActionRailAlignment) = change { it.copy(sideRailAlignment = alignment) }
    fun setLabels(show: Boolean) = change { it.copy(showLabels = show) }
    fun setShowHeadlightMode(show: Boolean) = change { it.copy(showHeadlightMode = show) }
    fun setHeadlightContent(content: QuickActionHeadlightContent) = change { it.copy(headlightContent = content) }
    fun setHeadlightTap(target: QuickActionHeadlightTap) = change { it.copy(headlightTap = target) }
    fun setKeepSideInset(keep: Boolean) = change { it.copy(keepSideInset = keep) }
    fun setArea(area: QuickActionArea) = change { it.copy(area = area) }
    fun setHideOnVirtualDisplay(hide: Boolean) = change { it.copy(hideOnVirtualDisplay = hide) }
    fun select(action: QuickAction, show: Boolean) = change { it.select(action, show) }
    fun move(action: QuickAction, earlier: Boolean) = change { it.move(action, earlier) }
    fun setLargeShortcuts(actions: Set<QuickAction>) = change { it.copy(largeActions = actions) }

    private fun change(edit: (QuickActionSettings) -> QuickActionSettings) {
        val previous = save.value
        if (state.value.settings == null || previous == QuickActionSave.SAVING ||
            !save.compareAndSet(previous, QuickActionSave.SAVING)) return
        viewModelScope.launch {
            try {
                store.update(edit)
                save.value = QuickActionSave.IDLE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                save.value = QuickActionSave.FAILED
            }
        }
    }
}
