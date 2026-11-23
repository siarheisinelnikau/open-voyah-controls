package org.openvoyah.controls.presentation.display

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import java.util.Locale
import kotlin.math.roundToInt
import org.openvoyah.controls.core.display.DisplayEventBus.Media
import org.openvoyah.controls.core.display.DisplayEventBus.Navigation
import org.openvoyah.controls.core.display.DisplayEventBus.Snapshot
import org.openvoyah.controls.core.display.PlaybackProgress
import org.openvoyah.controls.domain.display.DisplayPreviewArrangement
import org.openvoyah.controls.domain.display.DisplayPreviewContent
import org.openvoyah.controls.domain.display.DisplayPreviewDistanceUnits
import org.openvoyah.controls.domain.display.DisplayPreviewMediaSubtitle
import org.openvoyah.controls.domain.display.DisplayPreviewPalette
import org.openvoyah.controls.domain.display.DisplayPreviewPaneBalance
import org.openvoyah.controls.domain.display.DisplayPreviewPaneOrder
import org.openvoyah.controls.domain.settings.AppRoleTextSize
import org.openvoyah.controls.platform.media.PreviewMediaSnapshot

/** A null section is hidden. An empty visible section shows a status message. */
@Immutable
internal data class DisplayPreviewState(
    val media: DisplayPreviewTrack? = DisplayPreviewTrack(""),
    val route: String? = "",
    val playbackProgress: PlaybackProgress? = null,
    val albumArtwork: Bitmap? = null,
    val arrangement: DisplayPreviewArrangement = DisplayPreviewArrangement.AUTO,
    val paneOrder: DisplayPreviewPaneOrder = DisplayPreviewPaneOrder.MEDIA_FIRST,
    val reserveArtworkSpace: Boolean = false,
    val blankWhenIdle: Boolean = false,
    val palette: DisplayPreviewPalette = DisplayPreviewPalette.COCKPIT,
    val paneBalance: DisplayPreviewPaneBalance = DisplayPreviewPaneBalance.EQUAL,
    val routeDetailsHidden: Boolean = false,
    val textSize: AppRoleTextSize = AppRoleTextSize.FOLLOW,
    val preferredPlayerUnavailable: Boolean = false,
    val strictPlayerPackage: String? = null,
)

@Immutable
internal data class DisplayPreviewTrack(val title: String, val subtitle: Subtitle? = null) {
    @Immutable
    data class Subtitle(val text: String, val kind: DisplayPreviewMediaSubtitle)
}

internal fun Snapshot.toPreviewState(
    content: DisplayPreviewContent,
    previewMedia: PreviewMediaSnapshot? = null,
    locale: Locale = Locale.ENGLISH,
): DisplayPreviewState {
    val selected = previewMedia?.takeIf { content.mediaFallback.permits(content.preferredMediaPackage, it.packageName) }
    val preferredUnavailable = !content.mediaFallback.permits(content.preferredMediaPackage, selected?.packageName)
    val strictPlayer = content.preferredMediaPackage.takeUnless { content.mediaFallback.permits(it, null) }
    val track = if (content.showMedia) {
        val observation = selected?.media ?: if (preferredUnavailable) Media.EMPTY else media()
        observation.toPreviewTrack(content.mediaSubtitle)
            .takeUnless { content.hideEmptyPanes && it.title.isBlank() }
    } else null
    val route = navigation()
    val routeText = if (content.showNavigation) {
        val road = if (content.showCurrentRoad) content.roadNames.formatRoadName(route.road()) else ""
        val nextRoad = if (content.showNextRoad) content.roadNames.formatRoadName(route.nextRoad()) else ""
        val distance = if (content.showTurnDistance) content.distanceUnits.formatDistance(route.meters(), locale) else ""
        val detail = listOf(nextRoad, distance).filter { it.isNotBlank() }.joinToString(" · ")
        listOf(road, detail).filter { it.isNotBlank() }.joinToString("\n")
            .takeUnless { content.hideEmptyPanes && it.isBlank() }
    } else null
    val progress = if (track != null && content.showPlaybackProgress) {
        when {
            selected != null -> selected.progress
            preferredUnavailable -> null
            else -> playbackProgress()
        }
    } else null
    val artwork = if (track != null && content.showAlbumArtwork) selected?.artwork else null
    val hasBothPanes = track != null && routeText != null
    return DisplayPreviewState(track, routeText, progress, artwork,
        arrangement = if (hasBothPanes) content.arrangement else DisplayPreviewArrangement.AUTO,
        paneOrder = if (hasBothPanes) content.paneOrder else DisplayPreviewPaneOrder.MEDIA_FIRST,
        reserveArtworkSpace = hasBothPanes && content.showAlbumArtwork,
        blankWhenIdle = content.hideEmptyPanes && (content.showMedia || content.showNavigation) &&
            track == null && routeText == null,
        palette = content.palette,
        paneBalance = if (hasBothPanes && content.arrangement != DisplayPreviewArrangement.STACKED) {
            content.paneBalance
        } else DisplayPreviewPaneBalance.EQUAL,
        routeDetailsHidden = routeText == "" && route.detailsHiddenBy(content),
        textSize = content.textSize,
        preferredPlayerUnavailable = track != null && preferredUnavailable,
        strictPlayerPackage = strictPlayer.takeIf { track != null },
    )
}

private fun Navigation.detailsHiddenBy(content: DisplayPreviewContent): Boolean =
    (!content.showCurrentRoad && !content.showNextRoad && !content.showTurnDistance) ||
        (!content.showCurrentRoad && road().isNotBlank()) ||
        (!content.showNextRoad && nextRoad().isNotBlank()) ||
        (!content.showTurnDistance && meters() >= 0)

private fun Media.toPreviewTrack(kind: DisplayPreviewMediaSubtitle): DisplayPreviewTrack {
    if (!available()) return DisplayPreviewTrack("")
    val value = when (kind) {
        DisplayPreviewMediaSubtitle.ARTIST -> artist()
        DisplayPreviewMediaSubtitle.ALBUM -> album()
        DisplayPreviewMediaSubtitle.NONE -> ""
    }
    val subtitle = value.takeIf { it.isNotBlank() }?.let { DisplayPreviewTrack.Subtitle(it, kind) }
    return DisplayPreviewTrack(title(), subtitle)
}

private const val METERS_PER_MILE = 1_609.344
private const val METERS_PER_FOOT = 0.3048

internal fun DisplayPreviewDistanceUnits.formatDistance(meters: Int, locale: Locale = Locale.ENGLISH): String = when {
    meters < 0 -> ""
    this == DisplayPreviewDistanceUnits.METRIC -> "$meters m"
    meters < METERS_PER_MILE -> "${(meters / METERS_PER_FOOT).roundToInt()} ft"
    else -> String.format(locale, "%.1f mi", meters / METERS_PER_MILE)
}
