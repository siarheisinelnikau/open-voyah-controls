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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.rememberPressMotion

internal enum class HomeDestination { APPS, LISTEN, VOICE, DISPLAY, VEHICLE, FIRMWARE, SYSTEM, UPDATES, BUTTONS }
private data class SettingsLink(val icon: CockpitIcon, val title: Int, val description: Int, val destination: HomeDestination)

@Composable
internal fun HomeSettingsLinks(open: (HomeDestination) -> Unit, language: () -> Unit) {
    val links = listOf(
        SettingsLink(CockpitIcon.BUTTONS, R.string.cockpit_buttons, R.string.cockpit_panel_description, HomeDestination.BUTTONS),
        SettingsLink(CockpitIcon.VOICE, R.string.voice_options_title, R.string.cockpit_voice_description, HomeDestination.VOICE),
        SettingsLink(CockpitIcon.DISPLAY, R.string.virtual_display, R.string.cockpit_display_description, HomeDestination.DISPLAY),
        SettingsLink(CockpitIcon.CAR, R.string.vehicle_settings_title, R.string.vehicle_settings_tile_summary, HomeDestination.VEHICLE),
        SettingsLink(CockpitIcon.SETTINGS, R.string.firmware_settings, R.string.cockpit_firmware_description, HomeDestination.FIRMWARE),
        SettingsLink(CockpitIcon.SETTINGS, R.string.ui_61, R.string.cockpit_system_description, HomeDestination.SYSTEM),
        SettingsLink(CockpitIcon.DOWNLOAD, R.string.updates_title, R.string.updates_settings_summary, HomeDestination.UPDATES),
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 740.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            links.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { link -> HomeActionRow(UiText.get(link.title), UiText.get(link.description), link.icon,
                        { open(link.destination) }, Modifier.weight(1f)) }
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
    val press = rememberPressMotion()
    Surface(onClick = open, modifier = modifier.fillMaxWidth().then(press.modifier), shape = MaterialTheme.shapes.medium,
        interactionSource = press.source, color = press.color, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(horizontal = 15.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CockpitGlyph(icon, Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
            }
            CockpitGlyph(CockpitIcon.ARROW, Modifier.size(20.dp))
        }
    }
}
