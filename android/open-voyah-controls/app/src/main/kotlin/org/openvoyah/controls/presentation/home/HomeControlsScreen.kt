package org.openvoyah.controls.presentation.home

import android.content.ComponentName
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
import androidx.compose.ui.platform.LocalContext
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
        if (state.homeCardActivitySupported) {
            val context = LocalContext.current
            val label = remember(context, state.homeCardActivity) {
                val component = ComponentName.unflattenFromString(state.homeCardActivity)
                component?.let {
                    runCatching { context.packageManager.getActivityInfo(it, 0).loadLabel(context.packageManager).toString() }
                        .getOrDefault(it.packageName)
                }
            }
            CockpitChoice(R.string.home_card_activity, label ?: UiText.get(R.string.home_card_activity_default),
                onClick = actions::chooseHomeCardActivity)
            if (state.homeCardActivity.isNotEmpty()) {
                HomeCardScale(R.string.home_card_activity_dpi, HOME_CARD_DPI_STEPS, state.homeCardDpi,
                    format = { UiText.get(R.string.home_card_activity_dpi_value).format(it) }, change = actions::homeCardDpi)
            }
        }
        val navigatorWindow = state.homeCardActivitySupported &&
            state.homeCardActivity.startsWith(SettingsStore.YANDEX_NAVIGATOR + "/")
        if (!navigatorWindow) {
            CockpitSwitch(R.string.home_navigator_card, state.navigatorHomeCard, change = actions::navigatorHomeCard)
        }
        if (navigatorWindow || state.navigatorHomeCard) {
            NavigatorWidgetScale(state.navigatorWidgetScale, actions::navigatorWidgetScale)
            CockpitAction(UiText.get(R.string.home_navigator_widget_settings), { actions.command(HomeCommand.NAVIGATOR_WIDGET) },
                guideId = R.string.home_navigator_widget_settings)
        }
        CockpitSwitch(R.string.home_weather_card, state.weatherCard, change = actions::weatherCard)
        if (state.weatherCard) {
            CockpitTextField(state.weatherCity, actions::weatherCity,
                label = UiText.get(R.string.home_weather_city), modifier = Modifier.fillMaxWidth(), guideId = R.string.home_weather_city)
        }
        CockpitSwitch(R.string.home_media_card_apps, state.mediaCardApps, change = actions::mediaCardApps)
        CockpitSwitch(R.string.home_media_card_hide_online, state.mediaCardHideOnline, change = actions::mediaCardHideOnline)
    }
    if (state.available) {
        CockpitSection(UiText.get(R.string.driver_media_heading), CockpitIcon.DISPLAY,
            UiText.get(R.string.driver_media_description)) {
            if (!state.driverMediaHasPin) CockpitFeedback(UiText.get(R.string.driver_media_missing_pin), error = true)
            CockpitSwitch(R.string.home_cluster_card_apps, state.clusterCardApps, change = actions::clusterCardApps)
            CockpitSwitch(R.string.driver_media_layout, state.driverMediaLayout, enabled = state.clusterCardApps,
                change = actions::driverMediaLayout)
            CockpitSwitch(R.string.driver_navigation_card, state.driverNavigationCard, change = actions::driverNavigationCard)
            Text(UiText.get(R.string.driver_navigation_description), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.driverNavigatorSupported) DriverNavigatorActions(state, actions)
        }
    }
}

@Composable
private fun DriverNavigatorActions(state: HomeControlsState, actions: HomeActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CockpitAction(UiText.get(R.string.driver_navigator_open),
            { actions.command(HomeCommand.OPEN_DRIVER_NAVIGATOR) },
            enabled = !state.driverNavigatorBusy, guideId = R.string.driver_navigator_open)
        CockpitAction(UiText.get(R.string.driver_navigator_close),
            { actions.command(HomeCommand.CLOSE_DRIVER_NAVIGATOR) },
            enabled = !state.driverNavigatorBusy, guideId = R.string.driver_navigator_close)
    }
    if (state.driverNavigatorBusy) CockpitFeedback(UiText.get(R.string.driver_navigator_working))
    state.driverNavigatorFeedback?.let { CockpitFeedback(it.message(), error = !it.successful()) }
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
            { actions.command(HomeCommand.ACCESSIBILITY) }, guideId = R.string.button_service_enable)
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
            CockpitAction(UiText.get(R.string.ui_53), { actions.command(HomeCommand.CAPTURE) }, primary = true, guideId = R.string.ui_53)
            CockpitAction(UiText.get(R.string.ui_54), { actions.command(HomeCommand.MANUAL) }, guideId = R.string.ui_54)
        }
    }
    CockpitSection(UiText.get(R.string.ui_55), CockpitIcon.SETTINGS) {
        if (state.available) {
            Text(UiText.get(R.string.ui_56) + state.holdMs + UiText.get(R.string.ui_57) + state.debounceMs + UiText.get(R.string.ui_58),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CockpitAction(UiText.get(R.string.ui_59), { actions.command(HomeCommand.TIMINGS) }, guideId = R.string.ui_59)
        } else CockpitFeedback(state.error, error = true)
        CockpitAction(UiText.get(R.string.ui_62), { actions.command(HomeCommand.RESET) }, guideId = R.string.ui_62)
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
            CockpitAction(UiText.get(R.string.settings_archive_export), { actions.command(HomeCommand.EXPORT) }, guideId = R.string.settings_archive_export)
            CockpitAction(UiText.get(R.string.settings_archive_import), { actions.command(HomeCommand.IMPORT) }, guideId = R.string.settings_archive_import)
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
    HomeCardScale(R.string.home_navigator_widget_scale, NAVIGATOR_WIDGET_SCALE, percent,
        format = { if (it == SettingsStore.NAVIGATOR_WIDGET_SCALE) UiText.get(R.string.home_navigator_widget_scale_default) else "$it %" },
        change = change, showGuide = true)
}

private val HOME_CARD_DPI_STEPS = (100..320 step 10).toList()

@Composable
private fun HomeCardScale(title: Int, values: List<Int>, selected: Int, format: (Int) -> String,
    change: (Int) -> Unit, showGuide: Boolean = false) {
    var step by remember(selected) { mutableIntStateOf(values.indexOf(selected).coerceAtLeast(0)) }
    val value = format(values[step])
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(UiText.get(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(value, color = MaterialTheme.colorScheme.primary)
            if (showGuide) SettingInfo(UiText.get(title), title)
        }
        Slider(step.toFloat(), { step = it.roundToInt() }, Modifier.fillMaxWidth().semantics { stateDescription = value },
            valueRange = 0f..values.lastIndex.toFloat(), steps = values.lastIndex - 1,
            onValueChangeFinished = { change(values[step]) })
    }
}

/** The supervisor paused the activation in this boot; it comes back by itself after the next system restart. */
@Composable
internal fun ActivationPaused(paused: Boolean) {
    if (paused) CockpitFeedback(UiText.get(R.string.activation_paused), error = true)
}
