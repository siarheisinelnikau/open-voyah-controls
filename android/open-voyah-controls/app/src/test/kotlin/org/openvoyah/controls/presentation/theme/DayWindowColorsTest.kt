package org.openvoyah.controls.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daytime window theme paints the frame before Compose does. Its colors are resources, the Compose roles are
 * Kotlin values; this keeps the two from drifting apart, which would show as a flash at every start.
 */
class DayWindowColorsTest {
    private val resources = File("src/main/res/values")

    private fun color(name: String): Int {
        val text = File(resources, "cockpit_colors.xml").readText()
        val value = Regex("""<color name="$name">#([0-9A-Fa-f]{6})</color>""").find(text)
        return 0xFF000000.toInt() or checkNotNull(value) { "Missing $name" }.groupValues[1].toInt(16)
    }

    @Test fun theDaytimeWindowUsesTheDaytimeRoles() {
        assertEquals(DayCockpitColors.background.toArgb(), color("cockpit_day_background"))
        assertEquals(DayCockpitColors.surface.toArgb(), color("cockpit_day_surface"))
        assertEquals(DayCockpitColors.onBackground.toArgb(), color("cockpit_day_text"))
        assertEquals(DayCockpitColors.onSurfaceVariant.toArgb(), color("cockpit_day_muted"))
        assertEquals(DayCockpitColors.primary.toArgb(), color("cockpit_day_accent"))
    }

    @Test fun theDaytimeThemeOverridesEveryWindowColorOfTheDarkOne() {
        val styles = File(resources, "styles.xml").readText()
        val dark = checkNotNull(Regex("""<style name="AppTheme" [^>]*>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL).find(styles))
        val day = checkNotNull(Regex("""<style name="AppTheme\.Day">(.*?)</style>""", RegexOption.DOT_MATCHES_ALL).find(styles))
        val item = Regex("""<item name="(android:[A-Za-z]+)">([^<]*)</item>""")
        val darkColors = item.findAll(dark.groupValues[1]).filter { "cockpit_" in it.groupValues[2] }.map { it.groupValues[1] }.toSet()
        val dayItems = item.findAll(day.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
        assertTrue("Dark window colors without a daytime value: ${darkColors - dayItems.keys}", dayItems.keys.containsAll(darkColors))
        assertTrue(dayItems.filterKeys { it in darkColors }.values.all { "cockpit_day_" in it })
        assertEquals("true", dayItems["android:windowLightStatusBar"])
        // Guard the assumption above: the dark theme does name its window colors through cockpit resources.
        assertTrue(Color(color("cockpit_background")).red < .2f && darkColors.size >= 5)
    }
}
