package org.openvoyah.controls.presentation.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.AppGridDesktopNames
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.CockpitTextField

@Composable
internal fun DesktopNamesDialog(names: AppGridDesktopNames, close: () -> Unit, save: (String, String) -> Unit) {
    var first by remember { mutableStateOf(names.first) }
    var second by remember { mutableStateOf(names.second) }
    CockpitAlertDialog(
        onDismissRequest = close,
        title = { Text(UiText.get(R.string.apps_grid_desktop_names)) },
        dismissButton = { CockpitTextAction(UiText.get(R.string.ui_89), close) },
        confirmButton = { CockpitAction(UiText.get(R.string.apps_grid_names_save), {
            close()
            save(first, second)
        }, primary = true) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(UiText.get(R.string.apps_grid_names_limit).format(AppGridDesktopNames.MAX_LENGTH),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                DesktopNameField(AppGridDesktop.FAVORITES_ONE, first) { first = it }
                DesktopNameField(AppGridDesktop.FAVORITES_TWO, second) { second = it }
            }
        },
    )
}

@Composable
private fun DesktopNameField(desktop: AppGridDesktop, value: String, change: (String) -> Unit) {
    val label = desktop.desktopLabel()
    val reset = UiText.get(R.string.apps_grid_names_reset_for).format(label)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CockpitTextField(value, { if (AppGridDesktopNames.validInput(it)) change(it) }, label, Modifier.weight(1f),
            guideId = R.string.apps_grid_desktop_names)
        CockpitAction(UiText.get(R.string.apps_grid_names_reset), { change("") }, Modifier.semantics { contentDescription = reset },
            enabled = value.isNotEmpty(), destructive = true)
    }
}
