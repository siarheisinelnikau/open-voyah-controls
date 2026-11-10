package org.openvoyah.controls.presentation.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.frida.FridaWindowControls.Connection
import org.openvoyah.controls.data.frida.FridaWindowControls.Result
import org.openvoyah.controls.data.frida.FridaWindowControls.Status
import org.openvoyah.controls.domain.display.AppWindowProfile
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun AppWindowSettings(window: AppSettingsViewModel.Window, model: AppSettingsViewModel) {
    val profile = window.profile
    val colors = MaterialTheme.colorScheme
    Text(label(R.string.app_window_explainer), color = colors.onSurfaceVariant)
    if (profile != null) {
        val editable = !window.busy
        WindowDimensions(profile, editable, model::changeWindow)
        val inset = profile.panelWidthPx
        StepSlider(label(R.string.app_window_panel_width),
            if (inset == 0) label(R.string.app_window_panel_auto) else label(R.string.app_window_pixels).format(inset),
            (inset / 20).coerceIn(0, 30), 30, editable,
        ) { step -> model.changeWindow { it.copy(panelWidthPx = step * 20) } }
        if (window.connection == Connection.DISABLED) {
            TextButton(model::enableWindows, Modifier.heightIn(min = 48.dp), enabled = editable) {
                Text(label(R.string.app_window_enable))
            }
        }
        val message = when {
            window.busy -> label(R.string.ui_80)
            window.message != null -> label(window.message)
            window.result != null -> resultText(window.result)
            window.connection == Connection.DISCONNECTED -> label(R.string.window_hook_requirement)
            else -> null
        }
        if (message != null) {
            Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.primary)
        }
        Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton({ model.applyWindow(true) }, Modifier.heightIn(min = 48.dp), enabled = editable) {
                Text(label(R.string.app_window_restore))
            }
            Button({ model.applyWindow(false) }, Modifier.heightIn(min = 48.dp), enabled = editable) {
                Text(label(if (window.connection == Connection.ENABLED) R.string.app_window_apply else R.string.ui_104))
            }
        }
    } else {
        Text(label(window.message ?: R.string.ui_80), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = colors.onSurfaceVariant)
    }
}

@Composable
private fun WindowDimensions(profile: AppWindowProfile, editable: Boolean, change: ((AppWindowProfile) -> AppWindowProfile) -> Unit) {
    val widths: @Composable (Modifier) -> Unit = { modifier ->
        StepSlider(label(R.string.app_window_width), "${profile.widthPercent}%",
            (profile.widthPercent - 50) / 5, 10, editable, modifier) { step ->
            change { it.copy(widthPercent = 50 + step * 5) }
        }
    }
    val densities = AppWindowProfile.densities
    val density: @Composable (Modifier) -> Unit = { modifier ->
        StepSlider(label(R.string.app_window_dpi),
            if (profile.densityDpi == 0) label(R.string.app_window_default_dpi) else "${profile.densityDpi} DPI",
            densities.indexOf(profile.densityDpi), densities.lastIndex, editable, modifier) { step ->
            change { it.copy(densityDpi = densities[step]) }
        }
    }
    BoxWithConstraints {
        if (maxWidth >= 520.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                widths(Modifier.weight(1f))
                density(Modifier.weight(1f))
            }
        } else {
            Column {
                widths(Modifier.fillMaxWidth())
                density(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun StepSlider(
    title: String,
    value: String,
    step: Int,
    last: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    change: (Int) -> Unit,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(value, color = MaterialTheme.colorScheme.primary)
        }
        Slider(step.toFloat(), { change(it.roundToInt()) }, Modifier.fillMaxWidth().semantics { contentDescription = title },
            enabled = enabled, valueRange = 0f..last.toFloat(), steps = last - 1)
    }
}

private fun resultText(result: Result): String = when (result.status) {
    Status.APPLIED -> String.format(Locale.ROOT, label(R.string.app_window_applied), result.width, result.dpi, result.left)
    Status.DENSITY_ONLY -> String.format(Locale.ROOT, label(R.string.app_window_density_only), result.dpi)
    Status.NO_TASK -> label(R.string.app_window_saved_next)
    Status.UNSUPPORTED_WINDOW -> label(R.string.app_window_unsupported)
    Status.UNSUPPORTED_TASK -> label(R.string.app_window_task_unsupported)
    Status.NO_SPACE -> label(R.string.app_window_no_space)
    Status.UNAVAILABLE -> label(R.string.window_service_unavailable)
    Status.FAILED -> label(R.string.app_window_failed)
    Status.SAVED -> label(R.string.app_window_saved)
}

private fun label(id: Int) = UiText.get(id)
