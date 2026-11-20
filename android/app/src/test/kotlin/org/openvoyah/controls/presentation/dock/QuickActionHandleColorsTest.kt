package org.openvoyah.controls.presentation.dock

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.dock.QuickActionHandleAccent
import org.openvoyah.controls.domain.settings.AppAccent
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.presentation.theme.DayCockpitColors
import org.openvoyah.controls.presentation.theme.HighContrastCockpitColors
import org.openvoyah.controls.presentation.theme.HighContrastDayCockpitColors
import org.openvoyah.controls.presentation.theme.cockpitAccentColors

class QuickActionHandleColorsTest {
    @Test fun customAccentsStayReadableWithAndWithoutHighContrast() {
        val palettes = listOf(
            darkColorScheme(surfaceContainerLow = Color(0xFF10121A)), HighContrastCockpitColors,
            DayCockpitColors, HighContrastDayCockpitColors,
        )
        for (colors in palettes) {
            for (highContrast in listOf(false, true)) {
                val theme = QuickActionHandleAccent.THEME.collapsedColors(colors, highContrast)
                assertEquals(if (highContrast) colors.inverseSurface else colors.primaryContainer, theme.fill)
                assertEquals(if (highContrast) colors.inverseOnSurface else colors.onPrimaryContainer, theme.ink)
                val amber = QuickActionHandleAccent.AMBER.collapsedColors(colors, highContrast)
                val teal = QuickActionHandleAccent.TEAL.collapsedColors(colors, highContrast)
                assertNotEquals(amber.fill, teal.fill)
                for (accent in listOf(amber, teal)) {
                    assertContrast(accent.ink, accent.fill, if (highContrast) 7f else 4.5f)
                    assertContrast(accent.outline ?: accent.fill, colors.surfaceContainerLow, if (highContrast) 7f else 3f)
                    accent.outline?.let { assertContrast(it, accent.fill, 3f) }
                }
            }
            for (accent in listOf(QuickActionHandleAccent.AMBER, QuickActionHandleAccent.TEAL)) {
                assertNotEquals(accent.collapsedColors(colors, false).fill, accent.collapsedColors(colors, true).fill)
            }
        }
    }

    @Test fun dimmedHandleKeepsReadableInkAndOutlineAcrossCockpitPalettes() {
        val dark = darkColorScheme(
            surfaceContainerLow = Color(0xFF10121A), onSurface = Color(0xFFF5F4F1),
            primaryContainer = Color(0xFF202C5B), onPrimaryContainer = Color(0xFFDCE2FF),
            inverseSurface = Color(0xFFE5E7EF), inverseOnSurface = Color(0xFF1B2030),
        )
        val palettes = listOf(
            Triple(AppPalette.DARK, false, dark), Triple(AppPalette.DARK, true, HighContrastCockpitColors),
            Triple(AppPalette.DAY, false, DayCockpitColors), Triple(AppPalette.DAY, true, HighContrastDayCockpitColors),
        )
        for ((palette, contrastTheme, base) in palettes) {
            for (themeAccent in AppAccent.entries) {
                val colors = cockpitAccentColors(base, themeAccent, palette, contrastTheme)
                for (handleAccent in QuickActionHandleAccent.entries) {
                    for (highContrast in listOf(false, true)) {
                        val full = handleAccent.collapsedColors(colors, highContrast)
                        val idle = full.dimmedForIdle(colors, highContrast)
                        val rail = colors.surfaceContainerLow
                        assertTrue("The idle fill moves toward the rail for $palette, $themeAccent and $handleAccent",
                            contrast(idle.fill, rail) < contrast(full.fill, rail))
                        assertContrast(idle.ink, idle.fill, if (highContrast) 7f else 4.5f)
                        val outline = checkNotNull(idle.outline)
                        assertContrast(outline, idle.fill, 3f)
                        assertContrast(outline, rail, 3f)
                    }
                }
            }
        }
    }

    @Test fun idleDimmingFallsBackToFullColorsWhenNoInkMeetsHighContrast() {
        val full = QuickActionHandleColors(Color.Black, Color.White)
        val colors = darkColorScheme(surfaceContainerLow = Color(0xFF808080), onSurface = Color.White)
        assertEquals(full, full.dimmedForIdle(colors, highContrast = true))
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Float) {
        val actual = contrast(foreground, background)
        assertTrue("Contrast ratio $actual is below $minimum for $foreground on $background", actual >= minimum)
    }

    private fun contrast(foreground: Color, background: Color): Float {
        val a = foreground.luminance()
        val b = background.luminance()
        return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
    }
}
