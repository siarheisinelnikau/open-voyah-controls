package org.openvoyah.controls.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class HighContrastPaletteTest {
    @Test fun textAndControlsRemainLegibleOnDarkSurfaces() {
        val colors = HighContrastCockpitColors
        assertContrast("body text", colors.onBackground, colors.background, 7f)
        assertContrast("card text", colors.onSurface, colors.surface, 7f)
        assertContrast("card secondary text", colors.onSurfaceVariant, colors.surfaceVariant, 7f)
        assertContrast("raised card text", colors.onSurface, colors.surfaceContainerHighest, 7f)
        assertContrast("action label", colors.onPrimary, colors.primary, 7f)
        assertContrast("selected action label", colors.onPrimaryContainer, colors.primaryContainer, 7f)
        assertContrast("voice accent", colors.tertiary, colors.surface, 7f)
        assertContrast("error feedback", colors.error, colors.surface, 7f)
        assertContrast("outlined control", colors.outline, colors.surface, 3f)
        assertContrast("raised card border", colors.outlineVariant, colors.surfaceVariant, 3f)
    }

    @Test fun daylightPaletteAndWallpapersSupportIndependentContrastModes() {
        val palettes = listOf(DayCockpitColors to 4.5f, HighContrastDayCockpitColors to 7f)
        for ((colors, minimumTextContrast) in palettes) {
            assertContrast("day body text", colors.onBackground, colors.background, minimumTextContrast)
            assertContrast("day card text", colors.onSurface, colors.surface, minimumTextContrast)
            assertContrast("day secondary text", colors.onSurfaceVariant, colors.surfaceVariant, minimumTextContrast)
            assertContrast("day action label", colors.onPrimary, colors.primary, minimumTextContrast)
            assertContrast("day action accent", colors.primary, colors.surface, minimumTextContrast)
            assertContrast("day selected action label", colors.onPrimaryContainer, colors.primaryContainer, minimumTextContrast)
            assertContrast("day voice accent", colors.tertiary, colors.surface, minimumTextContrast)
            assertContrast("day error", colors.error, colors.surface, minimumTextContrast)
            assertContrast("day card border", colors.outlineVariant, colors.surfaceContainerHighest, 3f)
            val scrim = cockpitWallpaperScrim(colors.background)
            for (photo in listOf(Color.Black, Color.White)) {
                val visibleBackground = scrim.compositeOver(photo)
                assertContrast("day wallpaper label", colors.onSurface, visibleBackground, minimumTextContrast)
                assertContrast("day wallpaper icon", colors.primary, visibleBackground, 3f)
            }
        }
        assertTrue(HighContrastDayCockpitColors.onSurface.luminance() < DayCockpitColors.onSurface.luminance())
        assertEquals(Color.Black.copy(alpha = 0.73f), cockpitWallpaperScrim(HighContrastCockpitColors.background))
    }

    private fun assertContrast(role: String, first: Color, second: Color, minimum: Float) {
        val a = first.luminance()
        val b = second.luminance()
        val ratio = (max(a, b) + 0.05f) / (min(a, b) + 0.05f)
        assertTrue("$role contrast $ratio is below $minimum", ratio >= minimum)
    }
}
