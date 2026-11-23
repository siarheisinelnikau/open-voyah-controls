package org.openvoyah.controls.presentation.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.openvoyah.controls.R
import org.openvoyah.controls.domain.vehicle.HeadlightMonitor
import org.openvoyah.controls.domain.vehicle.HeadlightMonitoringStore
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.platform.ui.VehicleSettingsDestination as StockVehicleDestination

internal data class VehicleSettingsState(
    val available: Set<StockVehicleDestination> = emptySet(),
    val status: Int? = null,
)

internal data class HeadlightMonitoringState(
    val whileControlsOpen: Boolean? = null,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
)

internal class VehicleSettingsViewModel(
    private val isAvailable: (StockVehicleDestination) -> Boolean,
    headlightMonitor: HeadlightMonitor,
    scope: CoroutineScope? = null,
    private val monitoringStore: HeadlightMonitoringStore? = null,
    sharedHeadlightStatus: StateFlow<HeadlightStatus>? = null,
) : ViewModel() {
    private val tasks = scope ?: viewModelScope
    private val mutable = MutableStateFlow(VehicleSettingsState())
    private val monitoringSave = MutableStateFlow(MonitoringSave.IDLE)
    private var factoryLightingVisit: Job? = null
    val state = mutable.asStateFlow()
    val headlightStatus = sharedHeadlightStatus ?: headlightMonitor.observe().stateIn(
        tasks, SharingStarted.WhileSubscribed(stopTimeoutMillis = 250, replayExpirationMillis = 0),
        HeadlightStatus(),
    )
    val headlightMonitoring = combine(monitoringStore?.observeWhileControlsOpen() ?: flowOf(null), monitoringSave) { enabled, save ->
        HeadlightMonitoringState(enabled, save == MonitoringSave.SAVING, save == MonitoringSave.FAILED)
    }.stateIn(tasks, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), HeadlightMonitoringState())

    fun setHeadlightMonitoring(enabled: Boolean) {
        val store = monitoringStore ?: return
        val current = headlightMonitoring.value.whileControlsOpen ?: return
        val save = monitoringSave.value
        if (current == enabled || save == MonitoringSave.SAVING || !monitoringSave.compareAndSet(save, MonitoringSave.SAVING)) return
        tasks.launch {
            monitoringSave.value = try {
                store.setWhileControlsOpen(enabled)
                MonitoringSave.IDLE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                MonitoringSave.FAILED
            }
        }
    }

    fun beginFactoryLightingVisit() {
        if (factoryLightingVisit?.isActive == true) return
        // Keep receiving controller observations while the factory panel covers the Controls window.
        factoryLightingVisit = tasks.launch(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(FACTORY_LIGHTING_VISIT_LIMIT_MS) { headlightStatus.collect() }
        }
    }

    fun endFactoryLightingVisit() {
        factoryLightingVisit?.cancel()
        factoryLightingVisit = null
    }

    fun refresh() {
        val available = StockVehicleDestination.entries.filter { destination ->
            try { isAvailable(destination) } catch (_: RuntimeException) { false }
        }.toSet()
        mutable.update { it.copy(available = available) }
    }

    fun unavailable(destination: StockVehicleDestination) {
        mutable.update { it.copy(available = it.available - destination,
            status = R.string.vehicle_settings_unavailable) }
    }

    fun opened() { mutable.update { it.copy(status = null) } }
    fun openFailed() { mutable.update { it.copy(status = R.string.ui_85) } }

    private enum class MonitoringSave { IDLE, SAVING, FAILED }

    private companion object {
        const val FACTORY_LIGHTING_VISIT_LIMIT_MS = 120_000L
    }
}
