package org.openvoyah.controls.presentation.display

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.frida.FridaWindowControls
import org.openvoyah.controls.domain.display.AppWindowProfile
import org.openvoyah.controls.presentation.theme.ControlsTheme

object AppDisplayDefaultsView {
    @JvmStatic
    fun create(activity: ComponentActivity): ComposeView? {
        val windows = FridaWindowControls(activity)
        if (!windows.supportsDisplayDefaults()) return null
        val model = ViewModelProvider(activity, viewModelFactory {
            initializer { AppDisplayDefaultsViewModel(windows) }
        })[AppDisplayDefaultsViewModel::class.java]
        return ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { ControlsTheme { AppDisplayDefaultsControl(model) } }
        }
    }
}

@Composable
private fun AppDisplayDefaultsControl(model: AppDisplayDefaultsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    OutlinedButton(model::open, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label(R.string.app_display_defaults_title))
    }
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
            }
        },
        confirmButton = {
            Button(model::save, Modifier.heightIn(min = 48.dp), enabled = state.draft != null && !state.working) {
                Text(label(R.string.ui_104))
            }
        },
        dismissButton = {
            TextButton(model::close, Modifier.heightIn(min = 48.dp), enabled = !state.working) {
                Text(label(R.string.app_display_defaults_close))
            }
        },
    )
}

@Composable
private fun DensityPicker(title: String, dpi: Int?, choices: List<Int?>, enabled: Boolean, change: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title)
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled) {
            Text(densityText(dpi))
        }
        DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 220.dp)) {
            choices.forEach { value ->
                DropdownMenuItem(
                    text = { Text(densityText(value)) },
                    onClick = {
                        expanded = false
                        change(value)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

private fun densityText(dpi: Int?): String = when (dpi) {
    null -> label(R.string.app_display_defaults_off)
    0 -> label(R.string.app_window_default_dpi)
    else -> "$dpi DPI"
}

private fun label(id: Int): String = UiText.get(id)
