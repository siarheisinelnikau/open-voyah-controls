package org.openvoyah.controls.presentation.display

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog as AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.display.AppWindowProfile
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitOptionDialog

@Composable
internal fun AppDisplayDefaultsControl(model: AppDisplayDefaultsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    CockpitChoice(label(R.string.app_display_defaults_title), onClick = model::open)
    if (!state.visible) return

    AlertDialog(
        onDismissRequest = model::close,
        title = { Text(label(R.string.app_display_defaults_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(label(R.string.app_display_defaults_description))
                state.draft?.let { draft ->
                    DensityPicker(label(R.string.app_display_defaults_central), draft.centralDpi,
                        AppWindowProfile.densities, !state.working) { model.change(true, it) }
                    DensityPicker(label(R.string.app_display_defaults_secondary), draft.secondaryDpi,
                        listOf(null) + AppWindowProfile.densities, !state.working) { model.change(false, it) }
                }
                val message = if (state.working) R.string.ui_80 else state.message
                message?.let { Text(label(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            CockpitAction(label(R.string.ui_104), model::save, enabled = state.draft != null && !state.working, primary = true)
        },
        // An outlined action rather than a text one: Close must look unavailable while the defaults are written.
        dismissButton = { CockpitAction(label(R.string.app_display_defaults_close), model::close, enabled = !state.working) },
    )
}

@Composable
private fun DensityPicker(title: String, dpi: Int?, choices: List<Int?>, enabled: Boolean, change: (Int?) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    CockpitChoice(title, densityText(dpi), enabled) { choosing = true }
    if (choosing) CockpitOptionDialog(title, choices, dpi, ::densityText, change, { choosing = false }, enabled = enabled)
}

private fun densityText(dpi: Int?): String = when (dpi) {
    null -> label(R.string.app_display_defaults_off)
    0 -> label(R.string.app_window_default_dpi)
    else -> "$dpi DPI"
}

private fun label(id: Int): String = UiText.get(id)
