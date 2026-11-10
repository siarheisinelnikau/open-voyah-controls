package org.openvoyah.controls.presentation.display

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.platform.display.VirtualDisplayPreview.Status
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch

@Composable
internal fun DisplayScreen(
    state: DisplayViewModel.State, status: Status, defaults: AppDisplayDefaultsViewModel?, back: () -> Unit,
    chooseDensity: () -> Unit, mediaAccess: () -> Unit, configureNavigation: () -> Unit,
    clusterMedia: (Boolean) -> Unit, surface: @Composable (Modifier) -> Unit,
) {
    CockpitDetail(label(R.string.app_display_settings_title), label(R.string.display_screen_description), back) {
        CockpitColumns(firstWeight = .8f, first = {
            CockpitSection(label(R.string.display_scale_heading), CockpitIcon.DISPLAY) {
                defaults?.let { AppDisplayDefaultsControl(it) }
                CockpitChoice(label(R.string.display_density), previewDensityText(state.density), onClick = chooseDensity)
            }
            CockpitSection(label(R.string.display_sources_heading), CockpitIcon.SETTINGS) {
                val message = when {
                    state.changingNavigation -> R.string.ui_80
                    state.navigationEnabled -> R.string.cockpit_enabled
                    else -> R.string.cockpit_disabled
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CockpitAction(label(R.string.display_media_access), mediaAccess)
                    CockpitAction(label(R.string.display_navigation), configureNavigation, enabled = !state.changingNavigation)
                }
                CockpitFeedback(state.message ?: label(R.string.display_navigation) + " · " + label(message))
                CockpitSwitch(label(R.string.cluster_media_share), state.clusterMedia, change = clusterMedia)
            }
        }, second = {
            CockpitSection(label(R.string.virtual_display), CockpitIcon.DISPLAY, label(R.string.display_preview_description)) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    surface(Modifier.fillMaxWidth().height((maxWidth / 2.65f).coerceIn(145.dp, 218.dp))
                        .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.background))
                }
                val text = when (status) {
                    Status.Waiting -> label(R.string.display_preview_waiting)
                    Status.Unavailable -> label(R.string.display_unavailable)
                    is Status.Ready -> "${status.width} × ${status.height} · ${status.dpi} DPI"
                }
                CockpitFeedback(text, status == Status.Unavailable)
            }
        })
    }
}

internal fun previewDensityText(dpi: Int): String = if (dpi == 0) label(R.string.display_auto_density) else "$dpi DPI"

private fun label(id: Int): String = UiText.get(id)
