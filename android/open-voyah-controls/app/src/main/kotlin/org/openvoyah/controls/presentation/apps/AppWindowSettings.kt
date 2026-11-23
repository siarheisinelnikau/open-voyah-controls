package org.openvoyah.controls.presentation.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.display.AppWindowProfile
import org.openvoyah.controls.domain.display.WindowConnection
import org.openvoyah.controls.domain.display.WindowProfileDelivery
import org.openvoyah.controls.domain.display.WindowProfileDelivery.Outcome
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun AppWindowSettings(window: AppSettingsViewModel.Window, model: AppSettingsViewModel) {
    val profile = window.profile
    if (profile == null) {
        CockpitFeedback(label(window.message ?: R.string.ui_80), error = window.message != null)
        if (window.message != null && !window.busy) CockpitAction(label(R.string.app_window_retry), model::loadWindow)
        return
    }

    Text(label(R.string.app_window_explainer), color = MaterialTheme.colorScheme.onSurfaceVariant)
    val editable = !window.busy
    val inset = profile.panelWidthPx
    StepSlider(
        label(R.string.app_window_panel_width),
        if (inset == 0) label(R.string.app_window_panel_auto) else label(R.string.app_window_pixels).format(inset),
        inset / AppWindowProfile.INSET_STEP_PX,
        AppWindowProfile.MAX_INSET_PX / AppWindowProfile.INSET_STEP_PX,
        editable,
    ) { step -> model.changeWindow { it.copy(panelWidthPx = step * AppWindowProfile.INSET_STEP_PX) } }
    WindowDimensions(profile, editable, model::changeWindow)
    if (window.connection == WindowConnection.DISABLED) {
        CockpitAction(label(R.string.app_window_enable), model::enableWindows, enabled = editable)
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        CockpitAction(label(R.string.app_window_restore), model::resetWindow, enabled = editable, destructive = true)
        CockpitAction(label(if (window.connection == WindowConnection.ENABLED) R.string.app_window_apply else R.string.ui_104),
            model::applyWindow, enabled = editable, primary = true)
    }
    val message = when {
        window.busy -> label(R.string.ui_80)
        window.message != null -> label(window.message)
        window.result != null -> resultText(window.result)
        window.connection == WindowConnection.DISCONNECTED -> label(R.string.app_window_offline)
        else -> null
    }
    CockpitFeedback(message, error = window.message == R.string.app_window_save_failed ||
        window.result == Outcome.FAILED || window.result == Outcome.UNCONFIRMED)
}

@Composable
private fun WindowDimensions(profile: AppWindowProfile, editable: Boolean, change: ((AppWindowProfile) -> AppWindowProfile) -> Unit) {
    val widths: @Composable (Modifier) -> Unit = { modifier ->
        StepSlider(label(R.string.app_window_width), "${profile.widthPercent}%",
            (profile.widthPercent - 50) / 5, 10, editable, modifier) { step ->
            change { it.copy(widthPercent = 50 + step * 5) }
        }
    }
    val density: @Composable (Modifier) -> Unit = { modifier ->
        val dpi = profile.densityDpi.takeUnless { profile.inheritDensity }
        val text = when (dpi) {
            null -> label(R.string.app_window_inherit_dpi)
            0 -> label(R.string.app_window_default_dpi)
            else -> "$dpi DPI"
        }
        StepSlider(label(R.string.app_window_dpi), text,
            densityChoices.indexOf(dpi), densityChoices.lastIndex, editable, modifier) { step ->
            val selected = densityChoices[step]
            change { it.copy(densityDpi = selected ?: 0, inheritDensity = selected == null) }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(value, color = MaterialTheme.colorScheme.primary)
        }
        // A dot per step is noise at this density, and TalkBack should read the value instead of a step index.
        Slider(step.toFloat(), { change(it.roundToInt()) },
            Modifier.fillMaxWidth().semantics {
                contentDescription = title
                stateDescription = value
            },
            enabled = enabled, valueRange = 0f..last.toFloat(), steps = last - 1,
            colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                disabledActiveTickColor = Color.Transparent, disabledInactiveTickColor = Color.Transparent))
    }
}

private fun resultText(result: WindowProfileDelivery): String = when (result) {
    is WindowProfileDelivery.Applied -> String.format(Locale.ROOT, label(R.string.app_window_applied),
        result.widthPx, result.dpi, result.leftPx)
    is WindowProfileDelivery.DensityOnly -> String.format(Locale.ROOT, label(R.string.app_window_density_only), result.dpi)
    Outcome.NEXT_LAUNCH -> label(R.string.app_window_saved_next)
    Outcome.UNSUPPORTED_WINDOW -> label(R.string.app_window_unsupported)
    Outcome.UNSUPPORTED_TASK -> label(R.string.app_window_task_unsupported)
    Outcome.NO_SPACE -> label(R.string.app_window_no_space)
    Outcome.UNCONFIRMED -> label(R.string.app_window_unconfirmed)
    Outcome.FAILED -> label(R.string.app_window_failed)
    Outcome.SAVED -> label(R.string.app_window_saved)
}

private val densityChoices = listOf(null) + AppWindowProfile.densities

private fun label(id: Int) = UiText.get(id)
