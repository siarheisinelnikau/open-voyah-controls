package org.openvoyah.controls.presentation.display

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.display.PlaybackProgress
import org.openvoyah.controls.domain.display.DisplayPreviewPaneOrder
import org.openvoyah.controls.presentation.theme.CockpitMotion

@Composable
internal fun DisplayPreviewScreen(state: DisplayPreviewState, visible: Boolean, density: Density) {
    val typography = rememberPreviewTypography(state.textSize)
    CompositionLocalProvider(LocalDensity provides density) {
        MaterialTheme(colorScheme = previewColorScheme(state.palette), typography = typography) {
            DisplayPreviewCanvas(state, visible)
        }
    }
}

@Composable
private fun DisplayPreviewCanvas(state: DisplayPreviewState, visible: Boolean) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.padding(PREVIEW_PADDING_DP.dp), contentAlignment = Alignment.CenterStart) {
            val media = state.media
            val route = state.route?.ifBlank {
                val message = if (state.routeDetailsHidden) R.string.display_preview_route_details_hidden else R.string.display_no_route
                UiText.get(message)
            }
            when {
                media == null && route == null -> if (!state.blankWhenIdle) {
                    Text(UiText.get(R.string.display_preview_hidden), Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge)
                }
                media == null -> PreviewSection(R.string.display_preview_route, checkNotNull(route))
                route == null -> PreviewMedia(state, media, visible)
                else -> {
                    val mediaFirst = state.paneOrder == DisplayPreviewPaneOrder.MEDIA_FIRST
                    val scale = previewTextScale(MaterialTheme.typography, LocalDensity.current)
                    val sideBySide = state.arrangement.usesSideBySide(maxWidth.value, scale, state.reserveArtworkSpace)
                    val pane: @Composable (Boolean, Modifier) -> Unit = { isMedia, modifier ->
                        if (isMedia) PreviewMedia(state, media, visible, modifier)
                        else PreviewSection(R.string.display_preview_route, route, modifier)
                    }
                    if (sideBySide) {
                        val mediaShare = state.paneBalance.mediaShare(maxWidth.value, scale, state.reserveArtworkSpace)
                        Row(horizontalArrangement = Arrangement.spacedBy(PREVIEW_COLUMN_GAP_DP.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            val firstShare = if (mediaFirst) mediaShare else 1f - mediaShare
                            pane(mediaFirst, Modifier.weight(firstShare))
                            pane(!mediaFirst, Modifier.weight(1f - firstShare))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(PREVIEW_ROW_GAP_DP.dp)) {
                            pane(mediaFirst, Modifier.fillMaxWidth())
                            pane(!mediaFirst, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewMedia(
    state: DisplayPreviewState, track: DisplayPreviewTrack, visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val title = track.title.ifBlank {
        UiText.get(if (state.preferredPlayerUnavailable) R.string.display_preview_preferred_player_unavailable else R.string.display_no_media)
    }
    val progress = state.playbackProgress
    val artwork = state.albumArtwork
    val subtitle = track.subtitle
    val text = if (subtitle == null) title else "$title\n${subtitle.text}"
    val accessibleText = subtitle?.let { "$title\n${previewMediaSubtitleText(it.kind)} ${it.text}" }
    key(state.strictPlayerPackage, state.preferredPlayerUnavailable) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PREVIEW_ARTWORK_GAP_DP.dp)) {
            if (artwork != null) {
                val image = remember(artwork) { artwork.asImageBitmap() }
                Image(image, contentDescription = null, modifier = Modifier.size(PREVIEW_ARTWORK_DP.dp).clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop)
            }
            PreviewSection(R.string.display_preview_track, text, Modifier.weight(1f),
                maxLines = if (progress == null) 3 else 2, accessibleText = accessibleText) {
                if (progress != null) PlaybackIndicator(progress, visible)
            }
        }
    }
}

@Composable
private fun PreviewSection(
    title: Int,
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 3,
    accessibleText: String? = null,
    footer: @Composable () -> Unit = {},
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(UiText.get(title), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        val semantics = if (accessibleText == null) Modifier else Modifier.clearAndSetSemantics {
            contentDescription = accessibleText
        }
        AnimatedContent(text, modifier = semantics, transitionSpec = {
            fadeIn(tween(CockpitMotion.CHANGE)).togetherWith(fadeOut(tween(CockpitMotion.EXIT)))
        }, label = "display content") { value ->
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = maxLines,
                overflow = TextOverflow.Ellipsis)
        }
        footer()
    }
}

@Composable
private fun PlaybackIndicator(playback: PlaybackProgress, visible: Boolean) {
    var position by remember(playback, visible) {
        mutableLongStateOf(if (visible) playback.positionAt(SystemClock.elapsedRealtime()) else playback.positionMillis())
    }
    LaunchedEffect(playback, visible) {
        playback.previewPositions(visible, SystemClock::elapsedRealtime).collect { position = it }
    }
    val duration = playback.durationMillis()
    LinearProgressIndicator(progress = { (position.toDouble() / duration).toFloat() }, modifier = Modifier.fillMaxWidth())
    Text(
        "${playbackTime(position)} / ${playbackTime(duration)}",
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds / 1_000
    val hours = seconds / 3_600
    val minutes = (seconds / 60 % 60).toString()
    val remainder = (seconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.padStart(2, '0')}:$remainder" else "$minutes:$remainder"
}
