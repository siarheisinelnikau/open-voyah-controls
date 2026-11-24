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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.settings.SettingsStore
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextField
import org.openvoyah.controls.presentation.theme.SettingInfo

/** Shows the bridge error in place of a part that needs the bridge; true when the part has nothing else to show. */
@Composable
private fun unavailable(state: HomeControlsState): Boolean {
    if (!state.available) CockpitFeedback(state.error, error = true)
    return !state.available
}

/**
 * Settings → Home screen → Factory home screen: the Navigator map card, the weather and the media card's sources of the
 * factory launcher.
 */
@Composable
internal fun HomeFactoryScreen(state: HomeControlsState, actions: HomeActions) {
    CockpitSection(UiText.get(R.string.settings_part_factory_home), CockpitIcon.HOME, UiText.get(R.string.settings_factory_home_summary)) {
        if (unavailable(state)) return@CockpitSection
        if (!state.navigatorHomeCardSupported) {
            Text(UiText.get(R.string.settings_factory_home_unsupported), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@CockpitSection
        }
        CockpitSwitch(R.string.home_navigator_card, state.navigatorHomeCard, change = actions::navigatorHomeCard)
        if (state.navigatorHomeCard) {
            NavigatorWidgetScale(state.navigatorWidgetScale, actions::navigatorWidgetScale)
            CockpitAction(UiText.get(R.string.home_navigator_widget_settings), { actions.command(HomeCommand.NAVIGATOR_WIDGET) })
        }
        CockpitSwitch(R.string.home_weather_card, state.weatherCard, change = actions::weatherCard)
        if (state.weatherCard) {
            CockpitTextField(state.weatherCity, actions::weatherCity,
                label = UiText.get(R.string.home_weather_city), modifier = Modifier.fillMaxWidth(), guideId = R.string.home_weather_city)
        }
        CockpitSwitch(R.string.home_media_card_apps, state.mediaCardApps, change = actions::mediaCardApps)
        CockpitSwitch(R.string.home_media_card_hide_online, state.mediaCardHideOnline, change = actions::mediaCardHideOnline)
    }
}

/** Settings → Buttons → Factory panel: the app shortcuts of the launcher panel. */
@Composable
internal fun HomePanel(state: HomeControlsState, actions: HomeActions) {
    CockpitSection(UiText.get(R.string.ui_67), CockpitIcon.BUTTONS, UiText.get(R.string.ui_68)) {
        if (unavailable(state)) return@CockpitSection
        ActivationPaused(state.activationPaused)
        CockpitFeedback(state.health)
        CockpitSwitch(R.string.ui_69, state.panelEnabled, change = actions::panelEnabled)
        state.panels.forEach { row -> key(row.slot) {
            CockpitChoice(row.title, row.action, guideId = R.string.ui_67) { actions.choosePanel(row.slot) }
        } }
    }
}

/** Settings → Buttons → Steering wheel. */
@Composable
internal fun HomeSteering(state: HomeControlsState, actions: HomeActions) {
    CockpitSection(UiText.get(R.string.settings_part_steering), CockpitIcon.CAR, UiText.get(R.string.oem_steering_scope),
        guideId = R.string.oem_steering) {
        if (unavailable(state)) return@CockpitSection
        if (state.steeringSupported) {
            CockpitAction(UiText.get(R.string.oem_steering), { actions.command(HomeCommand.STEERING) }, Modifier.fillMaxWidth())
        } else {
            Text(UiText.get(R.string.settings_steering_unsupported), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Settings → Buttons → Hardware buttons: the accessibility service, the assignments and the press timings. */
@Composable
internal fun HomeHardwareButtons(state: HomeControlsState, actions: HomeActions) {
    CockpitSection(UiText.get(R.string.ui_37), CockpitIcon.BUTTONS) {
        Text(UiText.get(if (state.connected) R.string.ui_38 else R.string.ui_39),
            style = MaterialTheme.typography.titleMedium,
            color = if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(UiText.get(if (state.connected) R.string.ui_40 else R.string.ui_41), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(UiText.get(R.string.button_fallback_scope), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        CockpitAction(UiText.get(if (state.connected) R.string.ui_42 else R.string.button_service_enable),
            { actions.command(HomeCommand.ACCESSIBILITY) })
        CockpitSwitch(R.string.ui_43, state.buttonsEnabled, change = actions::buttonsEnabled)
    }
    CockpitSection(UiText.get(R.string.ui_44), CockpitIcon.SETTINGS, guideId = R.string.ui_44) {
        if (state.bindings.isEmpty()) {
            Text(UiText.get(R.string.ui_45), style = MaterialTheme.typography.titleMedium)
            Text(UiText.get(R.string.ui_46), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.bindings.forEach { row -> key(row.id) {
            CockpitChoice(row.title, row.device + "\n" + row.actions, guideId = R.string.ui_44) { actions.editBinding(row.id) }
        } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CockpitAction(UiText.get(R.string.ui_53), { actions.command(HomeCommand.CAPTURE) }, primary = true)
            CockpitAction(UiText.get(R.string.ui_54), { actions.command(HomeCommand.MANUAL) })
        }
    }
    CockpitSection(UiText.get(R.string.ui_55), CockpitIcon.SETTINGS) {
        if (state.available) {
            Text(UiText.get(R.string.ui_56) + state.holdMs + UiText.get(R.string.ui_57) + state.debounceMs + UiText.get(R.string.ui_58),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CockpitAction(UiText.get(R.string.ui_59), { actions.command(HomeCommand.TIMINGS) })
        } else CockpitFeedback(state.error, error = true)
        CockpitAction(UiText.get(R.string.ui_62), { actions.command(HomeCommand.RESET) })
    }
}

/** Settings → System → Connection: the Frida agent and what the connection to the vehicle needs. */
@Composable
internal fun HomeConnection(state: HomeControlsState, actions: HomeActions) {
    CockpitSection(UiText.get(R.string.settings_part_connection), CockpitIcon.CAR) {
        if (unavailable(state)) return@CockpitSection
        ActivationPaused(state.activationPaused)
        CockpitFeedback(state.health)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CockpitAction(UiText.get(R.string.ui_73), { actions.command(HomeCommand.CONNECT) }, primary = true)
            CockpitAction(UiText.get(R.string.ui_75), { actions.command(HomeCommand.STATUS) })
            CockpitAction(UiText.get(R.string.ui_76), { actions.command(HomeCommand.DISCONNECT) })
        }
        Text(UiText.get(R.string.ui_78), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    CockpitSection(UiText.get(R.string.ui_63), CockpitIcon.CAR) {
        Text(UiText.get(R.string.ui_64), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(UiText.get(R.string.ui_65), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Settings → System → Backup. */
@Composable
internal fun HomeBackup(actions: HomeActions) {
    CockpitSection(UiText.get(R.string.settings_archive_title), CockpitIcon.DOWNLOAD, UiText.get(R.string.settings_archive_description)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CockpitAction(UiText.get(R.string.settings_archive_export), { actions.command(HomeCommand.EXPORT) })
            CockpitAction(UiText.get(R.string.settings_archive_import), { actions.command(HomeCommand.IMPORT) })
        }
    }
}

private val NAVIGATOR_WIDGET_SCALE = (SettingsStore.NAVIGATOR_WIDGET_SCALE_MIN..200 step 10).toList()

/**
 * Percent, steps of 10; more is larger content. Saved when the slider is let go, so the card is not redrawn on
 * every step of a drag.
 */
@Composable
private fun NavigatorWidgetScale(percent: Int, change: (Int) -> Unit) {
    var step by remember(percent) {
        mutableIntStateOf(NAVIGATOR_WIDGET_SCALE.indexOf(percent).takeIf { it >= 0 } ?: NAVIGATOR_WIDGET_SCALE.indexOf(SettingsStore.NAVIGATOR_WIDGET_SCALE))
    }
    val shown = NAVIGATOR_WIDGET_SCALE[step]
    val value = if (shown == SettingsStore.NAVIGATOR_WIDGET_SCALE) UiText.get(R.string.home_navigator_widget_scale_default) else "$shown %"
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(UiText.get(R.string.home_navigator_widget_scale), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(value, color = MaterialTheme.colorScheme.primary)
            SettingInfo(UiText.get(R.string.home_navigator_widget_scale), R.string.home_navigator_widget_scale)
        }
        Slider(step.toFloat(), { step = it.roundToInt() }, Modifier.fillMaxWidth().semantics { stateDescription = value },
            valueRange = 0f..NAVIGATOR_WIDGET_SCALE.lastIndex.toFloat(), steps = NAVIGATOR_WIDGET_SCALE.lastIndex - 1,
            onValueChangeFinished = { change(NAVIGATOR_WIDGET_SCALE[step]) })
    }
}

/** The supervisor paused the activation in this boot; it comes back by itself after the next system restart. */
@Composable
internal fun ActivationPaused(paused: Boolean) {
    if (paused) CockpitFeedback(UiText.get(R.string.activation_paused), error = true)
}
