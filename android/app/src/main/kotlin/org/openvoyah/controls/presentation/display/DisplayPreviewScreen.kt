package org.openvoyah.controls.presentation.display

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.presentation.theme.CockpitMotion

@Immutable
internal data class DisplayPreviewState(val media: String = "", val route: String = "")

@Composable
internal fun DisplayPreviewScreen(state: DisplayPreviewState) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.padding(16.dp), contentAlignment = Alignment.CenterStart) {
            if (maxWidth < 420.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PreviewSection(R.string.display_preview_track, state.media.ifBlank { UiText.get(R.string.display_no_media) })
                PreviewSection(R.string.display_navigation, state.route.ifBlank { UiText.get(R.string.display_no_route) })
            } else Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                PreviewSection(R.string.display_preview_track, state.media.ifBlank { UiText.get(R.string.display_no_media) }, Modifier.weight(1f))
                PreviewSection(R.string.display_navigation, state.route.ifBlank { UiText.get(R.string.display_no_route) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PreviewSection(title: Int, text: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(UiText.get(title), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        AnimatedContent(text, transitionSpec = {
            fadeIn(tween(CockpitMotion.CHANGE)).togetherWith(fadeOut(tween(CockpitMotion.EXIT)))
        }, label = "display content") { value ->
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
