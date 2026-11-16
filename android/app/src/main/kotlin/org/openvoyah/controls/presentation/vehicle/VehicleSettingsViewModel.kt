package org.openvoyah.controls.presentation.vehicle

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.openvoyah.controls.R
import org.openvoyah.controls.platform.ui.VehicleSettingsDestination as StockVehicleDestination

internal data class VehicleSettingsState(
    val available: Set<StockVehicleDestination> = emptySet(),
    val status: Int? = null,
)

internal class VehicleSettingsViewModel(
    private val isAvailable: (StockVehicleDestination) -> Boolean,
) : ViewModel() {
    private val mutable = MutableStateFlow(VehicleSettingsState())
    val state = mutable.asStateFlow()

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
}
