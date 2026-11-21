package org.openvoyah.controls.presentation.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout
import org.openvoyah.controls.presentation.theme.cockpitEntrance
import org.openvoyah.controls.presentation.theme.rememberEntranceOrder
import org.openvoyah.controls.presentation.theme.rememberPressMotion
import org.openvoyah.controls.presentation.theme.scaledText

private data class SettingsLink(val icon: CockpitIcon, val title: Int, val description: Int, val open: () -> Unit)

@Composable
internal fun HomeSettingsLinks(open: (ControlsRoute) -> Unit, language: () -> Unit, systemSettings: () -> Unit) {
    val layout = LocalCockpitLayout.current
    val spacing = Arrangement.spacedBy(layout.spacing(12.dp))
    val wideEnough = layout.columnsMinimum(740.dp, LocalDensity.current.fontScale, layout.navigationLabelScale)
    val links = listOf(
        SettingsLink(CockpitIcon.BUTTONS, R.string.cockpit_buttons, R.string.cockpit_panel_description) { open(ControlsRoute.BUTTONS) },
        SettingsLink(CockpitIcon.VOICE, R.string.voice_options_title, R.string.cockpit_voice_description) { open(ControlsRoute.VOICE) },
        SettingsLink(CockpitIcon.DISPLAY, R.string.virtual_display, R.string.cockpit_display_description) { open(ControlsRoute.DISPLAY) },
        SettingsLink(CockpitIcon.CAR, R.string.vehicle_settings_title, R.string.vehicle_settings_tile_summary) { open(ControlsRoute.VEHICLE) },
        SettingsLink(CockpitIcon.ENERGY, R.string.vehicle_automation_entry, R.string.vehicle_automation_entry_summary) {
            open(ControlsRoute.VEHICLE_AUTOMATION)
        },
        SettingsLink(CockpitIcon.APPS, R.string.quick_actions_title, R.string.quick_actions_summary) { open(ControlsRoute.QUICK_ACTIONS) },
        SettingsLink(CockpitIcon.ENERGY, R.string.routines_title, R.string.routines_summary) { open(ControlsRoute.ROUTINES) },
        SettingsLink(CockpitIcon.SETTINGS, R.string.firmware_settings, R.string.cockpit_firmware_description) { open(ControlsRoute.FIRMWARE) },
        SettingsLink(CockpitIcon.SETTINGS, R.string.ui_61, R.string.cockpit_system_description, systemSettings),
        SettingsLink(CockpitIcon.DOWNLOAD, R.string.updates_title, R.string.updates_settings_summary) { open(ControlsRoute.UPDATES) },
        SettingsLink(CockpitIcon.SEARCH, R.string.diagnostics_title, R.string.diagnostics_link_summary) {
            open(ControlsRoute.DIAGNOSTICS)
        },
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= wideEnough) 2 else 1
        Column(verticalArrangement = spacing) {
            links.chunked(columns).forEach { row ->
                Row(horizontalArrangement = spacing) {
                    row.forEach { link -> HomeActionRow(UiText.get(link.title), UiText.get(link.description), link.icon,
                        link.open, Modifier.weight(1f)) }
                    if (columns == 2 && row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    HomeActionRow(UiText.get(R.string.cockpit_language), if (UiText.language() == "ru") "Русский" else "English",
        CockpitIcon.SETTINGS, language)
}

@Composable
internal fun HomeActionRow(title: String, subtitle: String, icon: CockpitIcon, open: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val layout = LocalCockpitLayout.current
    val labelStyle = MaterialTheme.typography.titleMedium.scaledText(layout.navigationLabelScale / layout.textScale)
    val press = rememberPressMotion()
    val arrival = rememberEntranceOrder()?.let { Modifier.cockpitEntrance(it) } ?: Modifier
    Surface(onClick = open, modifier = modifier.fillMaxWidth().then(arrival).then(press.modifier), shape = MaterialTheme.shapes.medium,
        interactionSource = press.source, color = press.color, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(horizontal = layout.spacing(15.dp), vertical = layout.spacing(8.dp)),
            horizontalArrangement = Arrangement.spacedBy(layout.spacing(14.dp)), verticalAlignment = Alignment.CenterVertically) {
            CockpitGlyph(icon, Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = labelStyle)
                if (subtitle.isNotBlank()) Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
            }
            CockpitGlyph(CockpitIcon.ARROW, Modifier.size(20.dp))
        }
    }
}
