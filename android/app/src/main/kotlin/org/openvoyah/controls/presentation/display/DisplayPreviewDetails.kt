package org.openvoyah.controls.presentation.display

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.display.DisplayPreviewArrangement
import org.openvoyah.controls.domain.display.DisplayPreviewDistanceUnits
import org.openvoyah.controls.domain.display.DisplayPreviewMediaSubtitle
import org.openvoyah.controls.domain.display.DisplayPreviewPalette
import org.openvoyah.controls.domain.display.DisplayPreviewPaneBalance
import org.openvoyah.controls.domain.display.DisplayPreviewRoadNames
import org.openvoyah.controls.domain.settings.AppRoleTextSize
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitModal
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction

@Composable
internal fun DisplayPreviewDetails(
    state: DisplayViewModel.State,
    close: () -> Unit,
    mediaSubtitle: (DisplayPreviewMediaSubtitle) -> Unit,
    showCurrentRoad: (Boolean) -> Unit,
    showNextRoad: (Boolean) -> Unit,
    showTurnDistance: (Boolean) -> Unit,
    hideEmptyPanes: (Boolean) -> Unit,
    palette: (DisplayPreviewPalette) -> Unit,
    paneBalance: (DisplayPreviewPaneBalance) -> Unit,
    distanceUnits: (DisplayPreviewDistanceUnits) -> Unit,
    roadNames: (DisplayPreviewRoadNames) -> Unit,
    textSize: (AppRoleTextSize) -> Unit,
) {
    val options = state.previewContent
    val mediaEnabled = options.showMedia && !state.savingPreview
    val routeEnabled = options.showNavigation && !state.savingPreview
    CockpitModal(UiText.get(R.string.display_preview_details), footer = {
        CockpitTextAction(UiText.get(R.string.display_preview_details_close), close)
    }) {
        CockpitColumns(minimumWidth = 500.dp, first = {
            PreviewChoices(R.string.display_preview_text_size, AppRoleTextSize.entries, options.textSize,
                !state.savingPreview, ::previewTextSizeText, textSize)
            PreviewChoices(R.string.display_preview_palette, DisplayPreviewPalette.entries, options.palette,
                !state.savingPreview, ::previewPaletteText, palette)
            PreviewChoices(R.string.display_preview_pane_balance, DisplayPreviewPaneBalance.entries, options.paneBalance,
                mediaEnabled && routeEnabled && options.arrangement != DisplayPreviewArrangement.STACKED,
                ::previewPaneBalanceText, paneBalance)
            CockpitSwitch(UiText.get(R.string.display_preview_hide_empty_panes), options.hideEmptyPanes,
                !state.savingPreview, hideEmptyPanes)
        }, second = {
            PreviewChoices(R.string.display_preview_media_subtitle, DisplayPreviewMediaSubtitle.entries,
                options.mediaSubtitle, mediaEnabled, ::previewMediaSubtitleText, mediaSubtitle)
            CockpitSwitch(UiText.get(R.string.display_preview_show_current_road), options.showCurrentRoad,
                routeEnabled, showCurrentRoad)
            CockpitSwitch(UiText.get(R.string.display_preview_show_next_road), options.showNextRoad, routeEnabled, showNextRoad)
            PreviewChoices(R.string.display_preview_road_names, DisplayPreviewRoadNames.entries, options.roadNames,
                routeEnabled && (options.showCurrentRoad || options.showNextRoad), ::previewRoadNamesText, roadNames)
            CockpitSwitch(UiText.get(R.string.display_preview_show_turn_distance), options.showTurnDistance,
                routeEnabled, showTurnDistance)
            PreviewChoices(R.string.display_preview_distance_units, DisplayPreviewDistanceUnits.entries, options.distanceUnits,
                routeEnabled && options.showTurnDistance, ::previewDistanceUnitsText, distanceUnits)
        })
        if (state.previewSaveFailed) CockpitFeedback(UiText.get(R.string.display_preview_save_failed), error = true)
    }
}

@Composable
private fun <T> PreviewChoices(
    title: Int,
    options: List<T>,
    current: T,
    enabled: Boolean,
    optionText: (T) -> String,
    choose: (T) -> Unit,
) {
    Column {
        Text(UiText.get(title), style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in options) {
                val selected = current == option
                Row(
                    Modifier.heightIn(min = 50.dp)
                        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) {
                            choose(option)
                        }
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null, enabled = enabled)
                    Text(optionText(option), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun previewTextSizeText(size: AppRoleTextSize): String = UiText.get(when (size) {
    AppRoleTextSize.FOLLOW -> R.string.appearance_text_follow
    AppRoleTextSize.STANDARD -> R.string.appearance_option_standard
    AppRoleTextSize.LARGE -> R.string.appearance_text_large
    AppRoleTextSize.EXTRA_LARGE -> R.string.appearance_text_extra_large
})

private fun previewPaletteText(palette: DisplayPreviewPalette): String = UiText.get(when (palette) {
    DisplayPreviewPalette.COCKPIT -> R.string.display_preview_palette_cockpit
    DisplayPreviewPalette.DAYLIGHT -> R.string.display_preview_palette_daylight
})

private fun previewPaneBalanceText(balance: DisplayPreviewPaneBalance): String = UiText.get(when (balance) {
    DisplayPreviewPaneBalance.EQUAL -> R.string.display_preview_pane_balance_equal
    DisplayPreviewPaneBalance.MEDIA -> R.string.display_preview_pane_balance_media
    DisplayPreviewPaneBalance.NAVIGATION -> R.string.display_preview_pane_balance_navigation
})

private fun previewDistanceUnitsText(units: DisplayPreviewDistanceUnits): String = UiText.get(when (units) {
    DisplayPreviewDistanceUnits.METRIC -> R.string.display_preview_distance_units_metric
    DisplayPreviewDistanceUnits.IMPERIAL -> R.string.display_preview_distance_units_imperial
})

private fun previewRoadNamesText(names: DisplayPreviewRoadNames): String = UiText.get(when (names) {
    DisplayPreviewRoadNames.ORIGINAL -> R.string.display_preview_road_names_original
    DisplayPreviewRoadNames.LATIN -> R.string.display_preview_road_names_latin
})

internal fun previewMediaSubtitleText(subtitle: DisplayPreviewMediaSubtitle): String = UiText.get(when (subtitle) {
    DisplayPreviewMediaSubtitle.ARTIST -> R.string.display_preview_media_subtitle_artist
    DisplayPreviewMediaSubtitle.ALBUM -> R.string.display_preview_media_subtitle_album
    DisplayPreviewMediaSubtitle.NONE -> R.string.display_preview_media_subtitle_none
})
