package org.openvoyah.controls.presentation.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import org.openvoyah.controls.domain.settings.AppBackgroundTreatment

internal val LocalCockpitBackgroundTreatment = compositionLocalOf { AppBackgroundTreatment.AMBIENT }

/** Quiet, size-cached lighting behind the shared screens; no independent redraw or input handling. */
@Composable
internal fun CockpitBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val decoration = when (LocalCockpitBackgroundTreatment.current) {
        AppBackgroundTreatment.AMBIENT -> Modifier.ambientBackdrop(colors.background, colors.primary, colors.tertiary)
        AppBackgroundTreatment.SOLID -> Modifier.background(colors.background)
    }
    CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
        Box(modifier.fillMaxSize().then(decoration)) { content() }
    }
}

private fun Modifier.ambientBackdrop(background: Color, primary: Color, tertiary: Color): Modifier =
    drawWithCache {
        val shortEdge = size.minDimension.coerceAtLeast(1f)
        val coolLight = Brush.radialGradient(
            colors = listOf(primary.copy(alpha = .09f), primary.copy(alpha = .025f), primary.copy(alpha = 0f)),
            center = Offset(size.width * .94f, size.height * .06f),
            radius = shortEdge * 1.1f,
        )
        val warmShade = Brush.radialGradient(
            colors = listOf(tertiary.copy(alpha = .035f), tertiary.copy(alpha = 0f)),
            center = Offset(size.width * .03f, size.height * .98f),
            radius = shortEdge * .85f,
        )
        onDrawBehind {
            drawRect(background)
            drawRect(coolLight)
            drawRect(warmShade)
        }
    }
