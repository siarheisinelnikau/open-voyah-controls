package org.openvoyah.controls.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.R
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitGridMath

class SettingsStructureTest {
    private val labels = mapOf(
        R.string.apps_grid_tile_size to "Tile size",
        R.string.settings_part_grid to "App grid",
        R.string.voice_duck_calls to "Duck media during calls",
        R.string.settings_part_ducking to "Media ducking",
        R.string.appearance_accent to "Accent colour",
        R.string.settings_part_theme to "Theme",
    )
    private val label: (Int) -> String = { labels[it] ?: "#$it" }

    @Test
    fun everySectionHasItsOwnRouteAndTheHubListsTen() {
        assertEquals(10, SettingsSection.entries.size)
        assertEquals(SettingsSection.entries.size, SettingsSection.entries.map { it.route }.toSet().size)
        SettingsSection.entries.forEach { assertEquals(it, SettingsSection.of(it.route)) }
        assertNull(SettingsSection.of(ControlsRoute.SETTINGS))
    }

    @Test
    fun everySearchResultOpensAPageThatBelongsToItsSection() {
        SETTINGS_INDEX.forEach { entry ->
            assertEquals("${entry.title} opens ${entry.route}", entry.section, sectionOf(entry.route))
            entry.part?.let { assertEquals(entry.section, it.section) }
            if (entry.part != null) assertEquals(entry.section.route, entry.route)
        }
        // Every part of a section page can be found by at least one of its settings.
        SettingsPartId.entries.forEach { part -> assertTrue("$part", SETTINGS_INDEX.any { it.part == part }) }
    }

    @Test
    fun searchMatchesEveryWordInTheLabelOrThePart() {
        val calls = searchSettings("duck CALLS", label)
        assertEquals(listOf(R.string.voice_duck_calls), calls.map { it.title })
        assertEquals(SettingsPartId.SOUND_DUCKING, calls.single().part)
        // The part title also matches: "theme accent" finds the accent in Appearance → Theme.
        assertTrue(searchSettings("theme accent", label).any { it.title == R.string.appearance_accent })
        assertTrue(searchSettings("   ", label).isEmpty())
        assertTrue(searchSettings("no such setting", label).isEmpty())
    }

    @Test
    fun aRecentChangeSurvivesItsEncodingAndForgetsUnknownPlaces() {
        val change = RecentChange(ControlsRoute.APPEARANCE, SettingsPartId.APPEARANCE_THEME)
        assertEquals(change, RecentChange.decode(change.encode()))
        val page = RecentChange(ControlsRoute.QUICK_ACTIONS, null)
        assertEquals(page, RecentChange.decode(page.encode()))
        assertNull(RecentChange.decode("APPS"))
        assertNull(RecentChange.decode("NO_SUCH_ROUTE|HOME_GRID"))
        assertEquals(RecentChange(ControlsRoute.SOUND, null), RecentChange.decode("SOUND|NO_SUCH_PART"))
    }

    @Test
    fun theGridGivesEachControlTheColumnsItsContentNeeds() {
        // 1100 px with 240 px cells and 16 px gaps: four columns of 263 px.
        val columns = CockpitGridMath.columns(1100, 240, 16)
        assertEquals(4, columns)
        val column = (1100 - 16 * 3) / 4
        assertEquals(1, CockpitGridMath.span(200, column, 16, columns))
        assertEquals(2, CockpitGridMath.span(400, column, 16, columns))
        assertEquals(4, CockpitGridMath.span(5000, column, 16, columns))
        // Short items share a row; one that does not fit the rest of a row starts the next.
        assertEquals(listOf(listOf(0, 1, 2), listOf(3), listOf(4, 5)), CockpitGridMath.rows(listOf(1, 2, 1, 4, 2, 1), 4))
        assertEquals(1, CockpitGridMath.columns(300, 240, 16))
    }
}
