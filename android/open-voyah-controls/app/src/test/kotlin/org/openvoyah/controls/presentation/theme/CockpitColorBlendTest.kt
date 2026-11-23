package org.openvoyah.controls.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitColorBlendTest {
    private val night = HighContrastCockpitColors
    private val day = DayCockpitColors

    @Test fun theEndsOfABlendAreTheSchemesThemselves() {
        assertSame(night, blend(night, day, 0f))
        assertSame(day, blend(night, day, 1f))
        assertSame(day, blend(night, day, 1.4f))
    }

    @Test fun everyRoleAScreenReadsMovesTogether() {
        val middle = blend(night, day, .5f)

        assertEquals(lerp(night.background, day.background, .5f), middle.background)
        assertEquals(lerp(night.onSurface, day.onSurface, .5f), middle.onSurface)
        assertEquals(lerp(night.primary, day.primary, .5f), middle.primary)
        assertEquals(lerp(night.outlineVariant, day.outlineVariant, .5f), middle.outlineVariant)
        assertEquals(lerp(night.surfaceContainerHighest, day.surfaceContainerHighest, .5f), middle.surfaceContainerHighest)
        assertEquals(lerp(night.error, day.error, .5f), middle.error)
    }

    @Test fun anAccentOrContrastChangeMixesRolesAndKeepsTextReadable() {
        val amber = night.copy(primary = Color(0xFFFFE397), onSurface = Color(0xFFE8EEFA))
        for (step in 1..9) {
            val mixed = themeTransition(night, amber, step / 10f)
            assertEquals(lerp(night.primary, amber.primary, step / 10f), mixed.primary)
            assertTrue("step $step", contrast(mixed.onBackground, mixed.background) > 10f)
        }
    }

    @Test fun aChangeBetweenNightAndDayDissolvesThroughThePageColorInsteadOfThroughGreyText() {
        assertSame(night, themeTransition(night, day, 0f))
        assertSame(day, themeTransition(night, day, 1f))
        // First the content melts into the old page color, which itself does not move yet.
        val leaving = themeTransition(night, day, .19f)
        assertEquals(night.background, leaving.background)
        assertTrue(contrast(leaving.onBackground, leaving.background) < contrast(night.onBackground, night.background))
        // Then one flat color carries the page across: no text is drawn in a color that differs from it.
        val crossing = themeTransition(night, day, .5f)
        assertTrue(near(lerp(night.background, day.background, .5f), crossing.background))
        assertEquals(crossing.background, crossing.onBackground)
        assertEquals(crossing.background, crossing.surface)
        assertEquals(crossing.background, crossing.primary)
        assertEquals(crossing.background, crossing.outlineVariant)
        // Finally the content emerges on the new page color, already in the new roles.
        val arriving = themeTransition(night, day, .9f)
        assertEquals(day.background, arriving.background)
        assertTrue(arriving.onBackground.red < arriving.background.red)
        assertTrue(contrast(arriving.onBackground, arriving.background) > 2f)
    }

    private fun near(first: Color, second: Color): Boolean = listOf(first.red - second.red, first.green - second.green,
        first.blue - second.blue, first.alpha - second.alpha).all { kotlin.math.abs(it) < .012f }

    @Test fun anEqualSchemeBuiltAgainIsNotANewGoal() {
        assertTrue(sameRoles(night, night))
        assertTrue(sameRoles(night, night.copy()))
        assertFalse(sameRoles(night, day))
        assertFalse(sameRoles(night, night.copy(primary = Color(0xFF8AF7E7))))
        assertFalse(sameRoles(night, night.copy(outlineVariant = Color.White)))
    }

    private fun contrast(first: Color, second: Color): Float {
        fun luminance(color: Color) = .2126f * color.red + .7152f * color.green + .0722f * color.blue
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
    }
}
