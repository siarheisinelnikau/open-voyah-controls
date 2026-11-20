package org.openvoyah.controls.presentation.vehicle

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.vehicle.ExteriorLamp
import org.openvoyah.controls.domain.vehicle.HeadlightInput
import org.openvoyah.controls.domain.vehicle.HeadlightInputState
import org.openvoyah.controls.domain.vehicle.HeadlightSource
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.platform.ui.copyHeadlightReport
import org.openvoyah.controls.platform.vehicle.headlightDiagnosticReport
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout

@Composable
internal fun HeadlightSourcesPopup(close: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.vehicleSettingsActivity() }
    val model = remember(activity) { activity.vehicleSettingsViewModel() }
    val owner = LocalLifecycleOwner.current
    val status by model.headlightStatus.collectAsStateWithLifecycle(lifecycleOwner = owner)
    val scope = rememberCoroutineScope()
    var copyState by remember { mutableStateOf(ReportCopyState.IDLE) }
    HeadlightSourcesDialog(status, close, copyState) {
        if (copyState != ReportCopyState.PREPARING && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            copyState = ReportCopyState.PREPARING
            scope.launch {
                copyState = try {
                    val report = headlightDiagnosticReport(activity, model.headlightStatus.value)
                    if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) ReportCopyState.IDLE
                    else if (copyHeadlightReport(activity, UiText.get(R.string.headlight_sources_title), report)) ReportCopyState.COPIED
                    else ReportCopyState.FAILED
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: RuntimeException) {
                    ControlsLog.warn("headlight_report", "outcome", "collection_failed", "failure", failure)
                    ReportCopyState.FAILED
                }
            }
        }
    }
}

private enum class ReportCopyState(@get:StringRes val result: Int?) {
    IDLE(null), PREPARING(R.string.headlight_sources_preparing), COPIED(R.string.headlight_sources_copied),
    FAILED(R.string.headlight_sources_copy_failed),
}

@Composable
private fun HeadlightSourcesDialog(
    status: HeadlightStatus, close: () -> Unit, copyState: ReportCopyState, copyReport: () -> Unit,
) {
    val density = LocalDensity.current
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val wide = with(density) { windowWidth.toDp() >= 600.dp }
    val layout = LocalCockpitLayout.current
    val columnsMinimum = layout.columnsMinimum(420.dp, density.fontScale)
    val gap = layout.spacing(12.dp)
    CockpitAlertDialog(
        onDismissRequest = close,
        modifier = if (wide) Modifier.width(570.dp) else Modifier,
        properties = DialogProperties(usePlatformDefaultWidth = !wide),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(layout.spacing(4.dp))) {
                Text(UiText.get(R.string.headlight_sources_title))
                CockpitFeedback(copyState.result?.let(UiText::get), error = copyState == ReportCopyState.FAILED)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(gap)) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (maxWidth >= columnsMinimum) 2 else 1,
                        horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                        for (input in HeadlightInput.entries) {
                            Column(Modifier.weight(1f)) {
                                Text(UiText.get(input.label()), style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text(UiText.get(inputStateLabel(status.inputStates[input])),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (status.lastConfirmed != null || status.lamps.values.any { it.lastConfirmed != null }) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(UiText.get(R.string.headlight_sources_last_confirmation),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    status.lastConfirmed?.let { ConfirmedBy(R.string.headlight_selector_label, it.source) }
                    for (lamp in ExteriorLamp.entries) {
                        status.lamps[lamp]?.lastConfirmed?.let { ConfirmedBy(lamp.activityLabel, it.source) }
                    }
                }
            }
        },
        dismissButton = {
            CockpitAction(UiText.get(R.string.headlight_sources_copy), copyReport, enabled = copyState != ReportCopyState.PREPARING)
        },
        confirmButton = { CockpitAction(UiText.get(R.string.headlight_sources_done), close, primary = true) },
    )
}

@Composable
private fun ConfirmedBy(@StringRes signal: Int, source: HeadlightSource?) {
    val sourceName = when (source) {
        HeadlightSource.FACTORY -> R.string.headlight_sources_factory
        HeadlightSource.ANDROID_AUTOMOTIVE -> R.string.headlight_sources_android
        null -> R.string.headlight_sources_unknown
    }
    Text(UiText.get(R.string.headlight_activity_summary).format(UiText.get(signal), UiText.get(sourceName)),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@StringRes
private fun HeadlightInput.label(): Int = when (this) {
    HeadlightInput.FACTORY_SELECTOR -> R.string.headlight_sources_factory_selector
    HeadlightInput.ANDROID_SELECTOR -> R.string.headlight_sources_android_selector
    HeadlightInput.ANDROID_HEADLAMPS -> R.string.headlight_sources_android_headlamps
    HeadlightInput.ANDROID_HIGH_BEAM -> R.string.headlight_sources_android_high_beam
}

@StringRes
private fun inputStateLabel(state: HeadlightInputState?): Int = when (state) {
    HeadlightInputState.MODEL_UNREVIEWED -> R.string.headlight_sources_model_unreviewed
    HeadlightInputState.SERVICE_ABSENT -> R.string.headlight_sources_service_absent
    HeadlightInputState.SERVICE_UNREVIEWED -> R.string.headlight_sources_service_unreviewed
    HeadlightInputState.PLATFORM_UNAVAILABLE -> R.string.headlight_sources_platform_unavailable
    HeadlightInputState.PERMISSION_DENIED -> R.string.headlight_sources_permission_denied
    HeadlightInputState.DISCONNECTED -> R.string.headlight_sources_disconnected
    HeadlightInputState.PROPERTY_UNAVAILABLE -> R.string.headlight_sources_property_unavailable
    HeadlightInputState.INCOMPATIBLE_PROPERTY -> R.string.headlight_sources_incompatible_property
    HeadlightInputState.SUBSCRIPTION_REJECTED -> R.string.headlight_sources_subscription_rejected
    HeadlightInputState.SUBSCRIBED -> R.string.headlight_sources_subscribed
    HeadlightInputState.READ_FAILED -> R.string.headlight_sources_read_failed
    null -> R.string.headlight_sources_waiting
}
