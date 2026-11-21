package org.openvoyah.controls.domain.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGridLauncherOptionsTest {
    @Test fun cardsCanFollowOnlyVisibleOrderedPinsAndKeepTheDefaultWithoutPins() {
        val pinned = setOf("maps", "media", "missing")
        val visible = listOf("maps", "media", "phone")
        assertEquals(DesktopCardPlacement.BEFORE_APPS, AppGridConfiguration().desktopCardPlacement)
        assertEquals(0, DesktopCardPlacement.BEFORE_APPS.appsBeforeCards(visible) { it in pinned })
        val placement = DesktopCardPlacement.AFTER_PINNED_APPS
        assertEquals(2, placement.appsBeforeCards(visible) { it in pinned })
        assertEquals(2, placement.appsBeforeCards(visible.take(2)) { it in pinned })
        assertEquals(0, placement.appsBeforeCards(visible) { false })
        assertEquals(0, placement.appsBeforeCards(emptyList<String>()) { it in pinned })
    }

    @Test fun fixedColumnsRespectTheRequestedMaximumAndTheActualViewport() {
        assertEquals(4, AppGridColumns.FOUR.fit(1280, 188, 12))
        assertEquals(5, AppGridColumns.FIVE.fit(1280, 188, 12))
        assertEquals(6, AppGridColumns.SIX.fit(1232, 188, 12))
        assertEquals(4, AppGridColumns.SIX.fit(925, 188, 12))
        assertEquals(2, AppGridColumns.SIX.fit(400, 148, 12))
        assertEquals(1, AppGridColumns.SIX.fit(112, 148, 12))
        assertEquals(3, AppGridColumns.SIX.fit(388, 118, 12))
        assertEquals(3, AppGridColumns.AUTO.fit(388, 118, 12))
    }

    @Test fun rowsThatFillTheHeightShowOnlyWholeRows() {
        // The central display at 240 dpi in pixels: 660 px of grid, 18 px spacing, 264 px of tile content.
        val height = AppGridRows.fittedTileHeight(660, 264, 18)
        assertEquals(321, height)
        assertTrue(2 * height + 18 <= 660)
        assertTrue(2 * (height + 18) >= 660)
        // A shorter grid, such as one under the search field, still shows two whole rows.
        assertEquals(270, AppGridRows.fittedTileHeight(558, 264, 18))
        assertEquals(192, AppGridRows.fittedTileHeight(1000, 180, 9))
        // Too short for two whole rows: the tiles keep their least height and the grid scrolls.
        assertEquals(264, AppGridRows.fittedTileHeight(522, 264, 18))
        assertEquals(264, AppGridRows.fittedTileHeight(200, 264, 18))
        assertTrue(AppGridRows.fittedTileHeight(Int.MAX_VALUE, 264, 18) in 264..282)
        assertThrows(IllegalArgumentException::class.java) { AppGridRows.fittedTileHeight(660, 0, 18) }
        assertThrows(IllegalArgumentException::class.java) { AppGridRows.fittedTileHeight(660, 264, -1) }
    }

    @Test fun largeTilesGiveSixColumnsBesideTheLauncherPanel() {
        // 1280 dp wide at 240 dpi, less the 145 px panel (97 dp) and 24 dp of page padding on either side.
        assertEquals(6, AppGridColumns.AUTO.fit(1135, 168, 12))
        assertEquals(6, AppGridColumns.SIX.fit(1135, 168, 12))
        assertEquals(7, AppGridColumns.AUTO.fit(1135, 148, 12))
    }

    @Test fun optionsKeepAutomaticSizingAndNoOptionalShortcutByDefault() {
        val grid = AppGridConfiguration()
        assertEquals(AppGridColumns.AUTO, grid.columns)
        assertEquals(emptySet<AppGridShortcut>(), grid.visibleShortcuts)
        assertEquals(AppGridColumns.FOUR, AppGridColumns.parse("4"))
        assertThrows(IllegalArgumentException::class.java) { AppGridColumns.parse("3") }
        assertFalse(AppGridShortcut.entries.any { it.storedValue in setOf("androidSettings", "settings", "search") })
    }

    @Test fun desktopNamesUseStableSlotsAndTheLocalizedFallbackForBlankNames() {
        val names = AppGridDesktopNames(
            first = AppGridDesktopNames.normalize("  Поездки "),
            second = AppGridDesktopNames.normalize("   "),
        )
        assertEquals("Поездки", names.nameFor(AppGridDesktop.FAVORITES_ONE))
        assertNull(names.nameFor(AppGridDesktop.FAVORITES_TWO))
        assertNull(names.nameFor(AppGridDesktop.MAIN))
        assertTrue(AppGridDesktopNames.validInput("Я".repeat(AppGridDesktopNames.MAX_LENGTH)))
        listOf("a\nb", "go\u202Ehome", "x".repeat(AppGridDesktopNames.MAX_LENGTH + 1)).forEach { invalid ->
            assertFalse(AppGridDesktopNames.validInput(invalid))
            assertThrows(IllegalArgumentException::class.java) { AppGridDesktopNames.normalize(invalid) }
        }
    }

    @Test fun desktopNameLengthCountsCompleteUnicodeCodePoints() {
        val name = "Я".repeat(AppGridDesktopNames.MAX_LENGTH - 1) + "\uD83D\uDE97"
        assertEquals(name, AppGridDesktopNames.normalize(name))
        assertFalse(AppGridDesktopNames.validInput(name + "x"))
        assertFalse(AppGridDesktopNames.validInput("\uD83D"))
        assertFalse(AppGridDesktopNames.validInput("\uDE97"))
    }
}
