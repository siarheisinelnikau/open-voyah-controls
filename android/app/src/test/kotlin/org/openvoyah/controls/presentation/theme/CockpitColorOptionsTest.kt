package org.openvoyah.controls.presentation.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.settings.AppAccent
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.domain.settings.WallpaperDimming

class CockpitColorOptionsTest {
    private val standardDark = darkColorScheme(
        background = Color(0xFF0A0B10),
        surface = Color(0xFF14161F),
        surfaceContainerHighest = Color(0xFF2B3345),
        inverseSurface = Color(0xFFE5E7EF),
        onSurface = Color(0xFFF5F4F1),
    )

    private val schemes = listOf(
        Triple(AppPalette.DARK, false, standardDark),
        Triple(AppPalette.DARK, true, HighContrastCockpitColors),
        Triple(AppPalette.DAY, false, DayCockpitColors),
        Triple(AppPalette.DAY, true, HighContrastDayCockpitColors),
    )

    @Test fun customAccentsPreserveReadableTextSelectionAndFocusInEveryTheme() {
        for ((palette, highContrast, base) in schemes) {
            assertSame(base, cockpitAccentColors(base, AppAccent.DEFAULT, palette, highContrast))
            val teal = cockpitAccentColors(base, AppAccent.TEAL, palette, highContrast)
            val amber = cockpitAccentColors(base, AppAccent.AMBER, palette, highContrast)
            assertNotEquals(teal.primary, amber.primary)
            assertNotEquals(teal.primaryContainer, amber.primaryContainer)
            for (scheme in listOf(teal, amber)) {
                val minimum = if (highContrast) 7f else 4.5f
                assertContrast(scheme.onPrimary, scheme.primary, minimum)
                assertContrast(scheme.onPrimaryContainer, scheme.primaryContainer, minimum)
                assertContrast(scheme.primary, scheme.primaryContainer, 3f)
                for (surface in listOf(base.background, base.surface, base.surfaceContainerHighest)) {
                    assertContrast(scheme.primary, surface, minimum)
                }
                assertContrast(scheme.inversePrimary, base.inverseSurface, 4.5f)
                assertEquals(scheme.primary, scheme.surfaceTint)
                assertEquals(base.background, scheme.background)
                assertEquals(base.tertiary, scheme.tertiary)
                assertEquals(base.error, scheme.error)
            }
        }
    }

    @Test fun wallpaperShadingKeepsTheOldMiddleAndReadableLabelsAtTheExtremes() {
        for ((palette, _, base) in schemes) {
            val daytime = palette == AppPalette.DAY
            val overlays = WallpaperDimming.entries.map { cockpitWallpaperScrim(base.background, it) }
            val middle = overlays[WallpaperDimming.STANDARD.ordinal]
            assertEquals(if (daytime) .86f else .73f, middle.alpha, .003f)
            assertEquals(if (daytime) base.background else Color.Black, middle.copy(alpha = 1f))
            assertTrue(overlays.zipWithNext().all { (lighter, stronger) -> lighter.alpha < stronger.alpha })
            for (scrim in overlays) {
                assertContrast(base.onSurface, scrim.compositeOver(Color.White), 7f)
                assertContrast(base.onSurface, scrim.compositeOver(Color.Black), 7f)
            }
        }
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Float) {
        val a = foreground.luminance()
        val b = background.luminance()
        val actual = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
        assertTrue("Contrast ratio $actual is below $minimum for $foreground on $background", actual >= minimum)
    }
}
