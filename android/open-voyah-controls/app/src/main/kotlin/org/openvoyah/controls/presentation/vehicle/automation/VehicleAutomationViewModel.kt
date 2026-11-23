package org.openvoyah.controls.presentation.vehicle.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.domain.vehicle.automation.DrivingModeHistory
import org.openvoyah.controls.domain.vehicle.automation.HeadlightGearAction
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationController
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationPort
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationStore
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry

internal typealias DrivingModeRestorePrompt = org.openvoyah.controls.domain.vehicle.automation.DrivingModeRestorePrompt
internal typealias VehicleAutomationState = org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationState

/** A screen binds to the process controller; it never owns automatic vehicle work. */
internal class VehicleAutomationViewModel private constructor(
    private val controllerFactory: (CoroutineScope) -> VehicleAutomationController,
) : ViewModel() {
    constructor(controller: VehicleAutomationController) : this({ controller })

    constructor(store: VehicleAutomationStore, history: DrivingModeHistory, telemetry: Flow<VehicleTelemetry>,
        lighting: Flow<HeadlightStatus>, port: VehicleAutomationPort, nowNanos: () -> Long,
        scope: CoroutineScope? = null, logEvent: (String) -> Unit = {}) : this({ owned ->
        VehicleAutomationController(store, history, telemetry, lighting, port, nowNanos, scope ?: owned, logEvent)
    })

    private val controller = controllerFactory(viewModelScope)
    val state = controller.state
    fun startMonitoring() = controller.startMonitoring()
    fun stopMonitoring() = controller.stopMonitoring()
    fun setHeadlightsEnabled(enabled: Boolean) = controller.setHeadlightsEnabled(enabled)
    fun setDriveAction(action: HeadlightGearAction) = controller.setDriveAction(action)
    fun setParkAction(action: HeadlightGearAction) = controller.setParkAction(action)
    fun setDrivingMemoryEnabled(enabled: Boolean) = controller.setDrivingMemoryEnabled(enabled)
    fun prepareRestore() = controller.prepareRestore()
    fun dismissRestore() = controller.dismissRestore()
    fun confirmRestore(prompt: DrivingModeRestorePrompt) = controller.confirmRestore(prompt)
    fun rememberCurrent() = controller.rememberCurrent()
}
