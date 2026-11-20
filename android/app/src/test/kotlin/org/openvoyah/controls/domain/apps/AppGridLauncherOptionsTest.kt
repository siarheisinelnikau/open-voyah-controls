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

    @Test fun optionsKeepAutomaticSizingAndEveryOptionalShortcutByDefault() {
        val grid = AppGridConfiguration()
        assertEquals(AppGridColumns.AUTO, grid.columns)
        assertEquals(AppGridShortcut.entries.toSet(), grid.visibleShortcuts)
        assertEquals(AppGridColumns.FOUR, AppGridColumns.parse("4"))
        assertThrows(IllegalArgumentException::class.java) { AppGridColumns.parse("3") }
        assertTrue(AppGridShortcut.ANDROID_SETTINGS in grid.visibleShortcuts)
        assertFalse(AppGridShortcut.entries.any { it.storedValue == "settings" || it.storedValue == "search" })
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
