package org.openvoyah.controls.presentation.display

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.display.DisplayPreviewArrangement
import org.openvoyah.controls.domain.display.DisplayPreviewPaneOrder
import org.openvoyah.controls.domain.display.PreviewMediaAction
import org.openvoyah.controls.domain.display.PreviewMediaFallback
import org.openvoyah.controls.domain.display.PreviewSeekStep
import org.openvoyah.controls.platform.display.VirtualDisplayPreview.Status
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitReveal
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch

@Composable
internal fun DisplayScreen(
    state: DisplayViewModel.State, status: Status,
    media: DisplayViewModel.Player?, preferredMediaLabel: String, back: () -> Unit,
    needsMediaAccess: Boolean, canvasDensity: Density,
    chooseDensity: () -> Unit, mediaAccess: () -> Unit, configureNavigation: () -> Unit,
    clusterMedia: (Boolean) -> Unit, previewMedia: (Boolean) -> Unit, previewNavigation: (Boolean) -> Unit,
    playbackProgress: (Boolean) -> Unit,
    albumArtwork: (Boolean) -> Unit, transportControls: (Boolean) -> Unit,
    transport: (PreviewMediaAction, Long, PreviewSeekStep) -> Unit, chooseSource: () -> Unit,
    chooseMediaFallback: () -> Unit,
    chooseArrangement: () -> Unit, choosePaneOrder: () -> Unit, chooseSeekStep: () -> Unit,
    previewDetails: () -> Unit,
    surface: @Composable (Modifier) -> Unit,
) {
    CockpitDetail(label(R.string.virtual_display), label(R.string.display_screen_description), back) {
        CockpitColumns(firstWeight = .8f, first = {
            CockpitSection(label(R.string.display_scale_heading), CockpitIcon.DISPLAY) {
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
                if (state.changingNavigation) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (needsMediaAccess && state.previewContent.showMedia) {
                    CockpitFeedback(label(R.string.display_preview_media_access_required))
                }
                CockpitSwitch(label(R.string.cluster_media_share), state.clusterMedia, change = clusterMedia)
            }
        }, second = {
            CockpitSection(label(R.string.virtual_display), CockpitIcon.DISPLAY, label(R.string.display_preview_description)) {
                val options = state.previewContent
                val mediaOptionsEnabled = options.showMedia && !state.savingPreview
                val showControls = options.showMedia && options.showTransportControls
                val bothPanes = options.showMedia && options.showNavigation
                val allowedPlayer = media?.takeIf { options.mediaFallback.permits(options.preferredMediaPackage, it.packageName) }
                // One switch per row: side by side, a label could be read as belonging to the neighbouring switch.
                CockpitSwitch(label(R.string.display_preview_show_media), options.showMedia, !state.savingPreview, previewMedia)
                CockpitSwitch(label(R.string.display_preview_show_playback_progress),
                    options.showPlaybackProgress, mediaOptionsEnabled, playbackProgress)
                CockpitSwitch(label(R.string.display_preview_show_navigation), options.showNavigation, !state.savingPreview, previewNavigation)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val normalHeight = (maxWidth / 2.65f).coerceIn(145.dp, if (showControls) 166.dp else 218.dp)
                    val previewHeight = if (options.showMedia || options.showNavigation) {
                        val width = previewContentWidth(constraints.maxWidth, canvasDensity)
                        val scale = previewTextScale(rememberPreviewTypography(options.textSize), canvasDensity)
                        val sideBySide = bothPanes && options.arrangement.usesSideBySide(width, scale, options.showAlbumArtwork)
                        val requiredHeight = previewPaneHeight(sideBySide, scale,
                            options.showMedia && options.showPlaybackProgress, bothPanes)
                        val densityRatio = canvasDensity.density / LocalDensity.current.density
                        maxOf(normalHeight, (requiredHeight * densityRatio).dp)
                    } else normalHeight
                    surface(Modifier.fillMaxWidth().height(previewHeight)
                        .clip(MaterialTheme.shapes.medium).background(previewColorScheme(options.palette).background))
                }
                CockpitReveal(showControls) {
                    PreviewTransportControls(allowedPlayer, options.seekStep, mediaOptionsEnabled, transport)
                }
                if (showControls && state.mediaCommandUnavailable) {
                    CockpitFeedback(label(R.string.display_preview_command_unavailable), error = true)
                }
                val text = when (status) {
                    Status.Waiting -> label(R.string.display_preview_waiting)
                    Status.Unavailable -> label(R.string.display_unavailable)
                    is Status.Ready -> "${status.width} × ${status.height} · ${status.dpi} DPI"
                }
                CockpitFeedback(text)
                CockpitChoice(label(R.string.display_preview_details), enabled = !state.savingPreview, onClick = previewDetails)
                CockpitReveal(bothPanes) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CockpitChoice(label(R.string.display_preview_arrangement), previewArrangementText(options.arrangement),
                            enabled = !state.savingPreview, modifier = Modifier.widthIn(min = 196.dp).weight(1f),
                            onClick = chooseArrangement)
                        CockpitChoice(label(R.string.display_preview_pane_order), previewPaneOrderText(options.paneOrder),
                            enabled = !state.savingPreview, modifier = Modifier.widthIn(min = 196.dp).weight(1f),
                            onClick = choosePaneOrder)
                    }
                }
                CockpitSwitch(label(R.string.display_preview_show_album_artwork),
                    options.showAlbumArtwork, mediaOptionsEnabled, albumArtwork)
                CockpitSwitch(label(R.string.display_preview_show_transport_controls),
                    options.showTransportControls, mediaOptionsEnabled, transportControls)
                val sourceLabel = when {
                    options.preferredMediaPackage.isEmpty() -> label(R.string.display_preview_automatic_source)
                    allowedPlayer?.packageName == options.preferredMediaPackage -> preferredMediaLabel
                    options.mediaFallback == PreviewMediaFallback.PREFERRED_ONLY ->
                        "$preferredMediaLabel · ${label(R.string.display_preview_preferred_player_unavailable)}"
                    else -> "$preferredMediaLabel · ${label(R.string.display_preview_automatic_source_fallback)}"
                }
                CockpitChoice(label(R.string.display_preview_media_source), sourceLabel,
                    mediaOptionsEnabled, onClick = chooseSource)
                CockpitReveal(options.preferredMediaPackage.isNotEmpty()) {
                    CockpitChoice(label(R.string.display_preview_media_fallback), previewFallbackText(options.mediaFallback),
                        mediaOptionsEnabled, onClick = chooseMediaFallback)
                }
                CockpitReveal(showControls) {
                    CockpitChoice(label(R.string.display_preview_seek_step), previewSeekStepText(options.seekStep),
                        mediaOptionsEnabled, onClick = chooseSeekStep)
                }
                if (state.previewSaveFailed) CockpitFeedback(label(R.string.display_preview_save_failed), error = true)
            }
        })
    }
}

@Composable
private fun PreviewTransportControls(
    media: DisplayViewModel.Player?, seekStep: PreviewSeekStep, enabled: Boolean,
    transport: (PreviewMediaAction, Long, PreviewSeekStep) -> Unit,
) {
    if (media == null || media.actions.isEmpty()) {
        CockpitFeedback(label(R.string.display_preview_controls_unavailable))
        return
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (action in media.actions) {
            val resource = when (action) {
                PreviewMediaAction.PREVIOUS -> R.string.display_preview_previous
                PreviewMediaAction.PLAY -> R.string.display_preview_play
                PreviewMediaAction.PAUSE -> R.string.display_preview_pause
                PreviewMediaAction.NEXT -> R.string.display_preview_next
                PreviewMediaAction.SEEK_BACK -> R.string.display_preview_seek_back
                PreviewMediaAction.SEEK_FORWARD -> R.string.display_preview_seek_forward
            }
            val title = label(resource).let { if (action.seekDirection == 0) it else it.format(seekStep.seconds) }
            CockpitAction(title, { transport(action, media.revision, seekStep) }, enabled = enabled,
                primary = action == PreviewMediaAction.PLAY || action == PreviewMediaAction.PAUSE)
        }
    }
}

internal fun previewDensityText(dpi: Int): String = if (dpi == 0) label(R.string.display_auto_density) else "$dpi DPI"

internal fun previewArrangementText(arrangement: DisplayPreviewArrangement): String = label(when (arrangement) {
    DisplayPreviewArrangement.AUTO -> R.string.display_preview_automatic_source
    DisplayPreviewArrangement.STACKED -> R.string.display_preview_arrangement_stacked
    DisplayPreviewArrangement.SIDE_BY_SIDE -> R.string.display_preview_arrangement_side_by_side
})

internal fun previewPaneOrderText(order: DisplayPreviewPaneOrder): String = label(when (order) {
    DisplayPreviewPaneOrder.MEDIA_FIRST -> R.string.display_preview_media_first
    DisplayPreviewPaneOrder.ROUTE_FIRST -> R.string.display_preview_route_first
})

internal fun previewSeekStepText(step: PreviewSeekStep): String = label(R.string.display_preview_seek_seconds).format(step.seconds)

internal fun previewFallbackText(fallback: PreviewMediaFallback): String = label(when (fallback) {
    PreviewMediaFallback.AUTOMATIC -> R.string.display_preview_media_fallback_automatic
    PreviewMediaFallback.PREFERRED_ONLY -> R.string.display_preview_media_fallback_preferred_only
})

private fun label(id: Int): String = UiText.get(id)
