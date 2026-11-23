package org.openvoyah.controls.presentation.vehicle.automation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignalKind
import org.openvoyah.controls.platform.ui.copyReportToClipboard
import org.openvoyah.controls.platform.vehicle.automation.vehicleAutomationDiagnosticReport
import org.openvoyah.controls.platform.vehicle.automation.VehicleWorkflowRuntime
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.vehicle.vehicleSettingsViewModel

internal class VehicleDiagnosticsViewModel(
    private val report: suspend () -> String,
    private val copy: (String) -> Boolean,
) : ViewModel() {
    private val mutable = MutableStateFlow<Int?>(null)
    val status = mutable.asStateFlow()
    private var request: Job? = null

    fun copyReport() {
        if (request?.isActive == true) return
        mutable.value = R.string.vehicle_diagnostics_preparing
        request = viewModelScope.launch {
            mutable.value = try {
                if (copy(report())) R.string.vehicle_diagnostics_copied else R.string.vehicle_diagnostics_failed
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { R.string.vehicle_diagnostics_failed }
        }
    }

    fun close() { request?.cancel(); request = null; mutable.value = null }
}

@Composable
internal fun VehicleDiagnosticsPopup(activity: ComponentActivity, close: () -> Unit) {
    val signals = remember(activity) { activity.vehicleSignalsModel() }
    val lamps = remember(activity) { activity.vehicleSettingsViewModel() }
    val runtime = remember(activity) { VehicleWorkflowRuntime.get(activity.applicationContext) }
    val model = remember(activity) {
        ViewModelProvider(activity, viewModelFactory { initializer {
            VehicleDiagnosticsViewModel(
                report = { vehicleAutomationDiagnosticReport(activity.applicationContext, signals.telemetry.value,
                    lamps.headlightStatus.value, signals.source.diagnostics.value, runtime.state.value) },
                copy = { copyReportToClipboard(activity, UiText.get(R.string.vehicle_diagnostics_title), it) },
            )
        } })[VehicleDiagnosticsViewModel::class.java]
    }
    val telemetry by signals.telemetry.collectAsStateWithLifecycle()
    val native by signals.source.diagnostics.collectAsStateWithLifecycle()
    val runtimeState by runtime.state.collectAsStateWithLifecycle()
    val status by model.status.collectAsStateWithLifecycle()
    DisposableEffect(model) { onDispose { model.close() } }
    CockpitAlertDialog(onDismissRequest = close,
        title = { Text(UiText.get(R.string.vehicle_diagnostics_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(UiText.get(R.string.vehicle_diagnostics_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(UiText.get(R.string.vehicle_diagnostics_closed))
                Text(UiText.get(if (runtimeState.backgroundConnected) R.string.vehicle_runtime_background_ready
                    else R.string.vehicle_runtime_foreground_only), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(UiText.get(R.string.vehicle_diagnostics_missing).format(
                    telemetry.unavailable.joinToString { UiText.get(it.labelResource()) }),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${telemetry.identity.product} · Android ${telemetry.identity.sdk} · " +
                    (native.readerState?.name?.lowercase() ?: UiText.get(R.string.vehicle_diagnostics_waiting)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status?.let { CockpitFeedback(UiText.get(it), error = it == R.string.vehicle_diagnostics_failed) }
            }
        },
        confirmButton = {
            CockpitAction(UiText.get(R.string.vehicle_diagnostics_copy), model::copyReport,
                enabled = status != R.string.vehicle_diagnostics_preparing, primary = true)
        },
        dismissButton = { CockpitTextAction(UiText.get(R.string.ui_89), close) },
    )
}

private fun VehicleSignalKind.labelResource(): Int = when (this) {
    VehicleSignalKind.GEAR -> R.string.vehicle_signal_gear
    VehicleSignalKind.SPEED -> R.string.vehicle_signal_speed
    VehicleSignalKind.DOORS -> R.string.vehicle_signal_doors
    VehicleSignalKind.OUTSIDE_TEMPERATURE -> R.string.vehicle_signal_outside
    VehicleSignalKind.DRIVING_MODE -> R.string.vehicle_signal_drive_mode
}
