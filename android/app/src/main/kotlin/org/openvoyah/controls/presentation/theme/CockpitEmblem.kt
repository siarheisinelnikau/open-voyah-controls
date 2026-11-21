package org.openvoyah.controls.presentation.theme

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

// The open wings reach beyond the badge on both sides and above it.
private const val WING_REACH = 51f
private const val WING_RISE = 27f
private const val MARK_WIDTH = LaunchEmblem.WIDTH + WING_REACH * 2f
private const val MARK_HEIGHT = LaunchEmblem.HEIGHT + WING_RISE

/**
 * The winged mark drawn in the roles of the active theme. Inside the app the launcher raster would bring its own
 * black plate, which reads as a black disc on the daytime palette and ignores the accent and contrast choices.
 */
@Composable
internal fun CockpitEmblem(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val palette = remember(colors) { emblemPalette(colors) }
    Spacer(modifier.drawWithCache {
        val fit = minOf(size.width / MARK_WIDTH, size.height / MARK_HEIGHT)
        if (fit <= 0f) return@drawWithCache onDrawBehind { }
        val emblem = LaunchEmblem(this, layoutDirection, fit.coerceIn(.2f, 3f), palette)
        val left = (size.width - MARK_WIDTH * fit) / 2f + WING_REACH * fit
        val top = (size.height - MARK_HEIGHT * fit) / 2f + WING_RISE * fit
        onDrawBehind {
            withTransform({
                translate(left, top)
                scale(fit, fit, pivot = Offset.Zero)
            }) {
                with(emblem) { draw(bodyOpacity = 1f, spread = 1f, wingOpacity = 1f, lightX = null, charge = 0f) }
            }
        }
    })
}

/**
 * The steel of the mark for a page background. On a dark page it is the bright metal of the startup intro; on a
 * light page the ramp runs from ink to a mid tone instead, because bright metal would vanish there.
 */
internal fun emblemPalette(colors: ColorScheme): LaunchPalette {
    val base = launchPalette(colors)
    if (colors.background.luminance() <= .5f) return base
    val metal = LaunchMetal(
        shade = colors.onBackground,
        body = lerp(colors.onBackground, colors.secondary, .7f),
        light = lerp(colors.secondary, Color.White, .45f),
    )
    return base.copy(metal = metal)
}
