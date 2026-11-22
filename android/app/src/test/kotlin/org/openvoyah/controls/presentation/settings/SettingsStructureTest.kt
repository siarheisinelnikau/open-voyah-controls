package org.openvoyah.controls.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.R
import org.openvoyah.controls.presentation.navigation.ControlsRoute

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
    fun groupsFillTheShorterColumnInTheirOrder() {
        val (left, right) = balancedColumns(listOf("grid" to 11, "desktops" to 9, "cards" to 4, "factory" to 4)) { it.second }
        assertEquals(listOf("grid", "factory"), left.map { it.first })
        assertEquals(listOf("desktops", "cards"), right.map { it.first })
        val (one, none) = balancedColumns(listOf("only" to 3)) { it.second }
        assertEquals(1, one.size)
        assertTrue(none.isEmpty())
    }
}
