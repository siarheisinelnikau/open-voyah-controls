package org.openvoyah.controls.presentation.vehicle.automation

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openvoyah.controls.data.climate.LocalSeasonalClimatePreferences
import org.openvoyah.controls.platform.vehicle.automation.NativeVehicleTelemetryMonitor
import org.openvoyah.controls.platform.vehicle.automation.VehicleWorkflowRuntime
import kotlinx.coroutines.flow.StateFlow
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.presentation.climate.SeasonalClimateViewModel

/** Screen state observes the same native source as background automation. */
internal class VehicleSignalsViewModel(
    val source: NativeVehicleTelemetryMonitor,
    val telemetry: StateFlow<VehicleTelemetry>,
) : ViewModel()

internal fun ComponentActivity.vehicleSignalsModel(): VehicleSignalsViewModel =
    ViewModelProvider(this, viewModelFactory { initializer {
        val runtime = VehicleWorkflowRuntime.get(applicationContext)
        VehicleSignalsViewModel(runtime.source, runtime.telemetry)
    } })[VehicleSignalsViewModel::class.java]

internal fun ComponentActivity.vehicleAutomationModel(): VehicleAutomationViewModel =
    ViewModelProvider(this, viewModelFactory { initializer {
        VehicleAutomationViewModel(VehicleWorkflowRuntime.get(applicationContext).controller)
    } })[VehicleAutomationViewModel::class.java]

internal fun ComponentActivity.seasonalClimateModel(): SeasonalClimateViewModel =
    ViewModelProvider(this, viewModelFactory { initializer {
        val runtime = VehicleWorkflowRuntime.get(applicationContext)
        SeasonalClimateViewModel(LocalSeasonalClimatePreferences(applicationContext), runtime.telemetry,
            runtime.climatePort, SystemClock::elapsedRealtimeNanos)
    } })[SeasonalClimateViewModel::class.java]
