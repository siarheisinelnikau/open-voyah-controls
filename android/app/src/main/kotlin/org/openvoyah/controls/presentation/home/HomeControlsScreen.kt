package org.openvoyah.controls.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Slider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch

@Composable
internal fun HomeButtons(state: HomeControlsState, actions: HomeActions) {
    if (!state.available) { CockpitFeedback(state.error, error = true); return }
    CockpitSection(UiText.get(R.string.ui_67), CockpitIcon.BUTTONS, UiText.get(R.string.ui_68)) {
        CockpitFeedback(state.health)
        CockpitSwitch(UiText.get(R.string.ui_69), state.panelEnabled, change = actions::panelEnabled)
        if (state.navigatorHomeCardSupported) {
            CockpitSwitch(UiText.get(R.string.home_navigator_card), state.navigatorHomeCard,
                change = actions::navigatorHomeCard)
            if (state.navigatorHomeCard) {
                NavigatorWidgetDpi(state.navigatorWidgetDpi, actions::navigatorWidgetDpi)
                CockpitAction(UiText.get(R.string.home_navigator_widget_settings),
                    { actions.command(HomeCommand.NAVIGATOR_WIDGET) })
            }
            CockpitSwitch(UiText.get(R.string.home_weather_card), state.weatherCard, change = actions::weatherCard)
            if (state.weatherCard) {
                OutlinedTextField(state.weatherCity, actions::weatherCity, singleLine = true,
                    label = { Text(UiText.get(R.string.home_weather_city)) }, modifier = Modifier.fillMaxWidth())
            }
        }
        state.panels.forEach { row -> key(row.slot) {
            CockpitChoice(row.title, row.action) { actions.choosePanel(row.slot) }
        } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CockpitAction(UiText.get(R.string.ui_73), { actions.command(HomeCommand.CONNECT) }, primary = true)
            CockpitAction(UiText.get(R.string.ui_75), { actions.command(HomeCommand.STATUS) })
            CockpitAction(UiText.get(R.string.ui_76), { actions.command(HomeCommand.DISCONNECT) })
        }
        Text(UiText.get(R.string.ui_78), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    CockpitSection(UiText.get(R.string.ui_37), CockpitIcon.BUTTONS) {
        Text(UiText.get(if (state.connected) R.string.ui_38 else R.string.ui_39),
            style = MaterialTheme.typography.titleMedium,
            color = if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(UiText.get(if (state.connected) R.string.ui_40 else R.string.ui_41), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(UiText.get(R.string.button_fallback_scope), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        CockpitAction(UiText.get(if (state.connected) R.string.ui_42 else R.string.button_service_enable),
            { actions.command(HomeCommand.ACCESSIBILITY) })
        CockpitSwitch(UiText.get(R.string.ui_43), state.buttonsEnabled, change = actions::buttonsEnabled)
    }
    CockpitSection(UiText.get(R.string.ui_44), CockpitIcon.SETTINGS) {
        if (state.bindings.isEmpty()) {
            Text(UiText.get(R.string.ui_45), style = MaterialTheme.typography.titleMedium)
            Text(UiText.get(R.string.ui_46), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.bindings.forEach { row -> key(row.id) {
            CockpitChoice(row.title, row.device + "\n" + row.actions) { actions.editBinding(row.id) }
        } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CockpitAction(UiText.get(R.string.ui_53), { actions.command(HomeCommand.CAPTURE) }, primary = true)
            CockpitAction(UiText.get(R.string.ui_54), { actions.command(HomeCommand.MANUAL) })
        }
        if (state.steeringSupported) {
            CockpitAction(UiText.get(R.string.oem_steering), { actions.command(HomeCommand.STEERING) }, Modifier.fillMaxWidth())
            Text(UiText.get(R.string.oem_steering_scope), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun HomeSettings(state: HomeControlsState, actions: HomeActions) {
    CockpitSection(UiText.get(R.string.ui_55), CockpitIcon.SETTINGS) {
        if (state.available) {
            Text(UiText.get(R.string.ui_56) + state.holdMs + UiText.get(R.string.ui_57) + state.debounceMs + UiText.get(R.string.ui_58),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CockpitAction(UiText.get(R.string.ui_59), { actions.command(HomeCommand.TIMINGS) })
        } else CockpitFeedback(state.error, error = true)
        CockpitAction(UiText.get(R.string.ui_62), { actions.command(HomeCommand.RESET) })
    }
    CockpitSection(UiText.get(R.string.settings_archive_title), CockpitIcon.DOWNLOAD, UiText.get(R.string.settings_archive_description)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CockpitAction(UiText.get(R.string.settings_archive_export), { actions.command(HomeCommand.EXPORT) })
            CockpitAction(UiText.get(R.string.settings_archive_import), { actions.command(HomeCommand.IMPORT) })
        }
    }
    CockpitSection(UiText.get(R.string.ui_63), CockpitIcon.CAR) {
        Text(UiText.get(R.string.ui_64), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(UiText.get(R.string.ui_65), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val NAVIGATOR_WIDGET_DPI = (120..240 step 20).toList()

/** Steps of 20 dpi; saved when the slider is let go, so the card is not redrawn on every step of a drag. */
@Composable
private fun NavigatorWidgetDpi(dpi: Int, change: (Int) -> Unit) {
    var step by remember(dpi) { mutableIntStateOf(NAVIGATOR_WIDGET_DPI.indexOf(dpi).coerceAtLeast(0)) }
    val shown = NAVIGATOR_WIDGET_DPI[step]
    val value = if (shown == 160) UiText.get(R.string.home_navigator_widget_dpi_default) else "$shown DPI"
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(UiText.get(R.string.home_navigator_widget_dpi), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(value, color = MaterialTheme.colorScheme.primary)
        }
        Slider(step.toFloat(), { step = it.roundToInt() }, Modifier.fillMaxWidth().semantics { stateDescription = value },
            valueRange = 0f..NAVIGATOR_WIDGET_DPI.lastIndex.toFloat(), steps = NAVIGATOR_WIDGET_DPI.lastIndex - 1,
            onValueChangeFinished = { change(NAVIGATOR_WIDGET_DPI[step]) })
    }
}
