package org.openvoyah.controls.domain.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppGridManualOrderTest {
    @Test fun manualOrderPreservesPinPrecedenceDefaultHidingAndFavorites() {
        val alpha = app("alpha")
        val bravo = app("bravo")
        val hidden = app("hidden", hiddenByDefault = true)
        val nova = app("nova")
        val pinned = app("pinned", hiddenByDefault = true)
        val offline = app("offline")
        val utility = AppGridOrderApp("org.example.withoutlauncher", hiddenByDefault = false)
        val catalog = listOf(alpha, bravo, hidden, nova, pinned, utility)
        val grid = AppGridConfiguration(
            pinnedApps = listOf(pinned.component),
            desktops = AppGridDesktops(enabled = true, firstFavorites = listOf(alpha.component, bravo.component)),
            sortOrder = AppGridSortOrder.MANUAL,
            manualAppOrder = listOf(offline.component, bravo.component, hidden.component, alpha.component, pinned.component),
        )

        assertEquals(listOf(pinned, bravo, alpha, nova, utility),
            grid.arrange(catalog, AppGridOrderApp::component, AppGridOrderApp::hiddenByDefault))
        assertEquals(listOf(pinned, bravo, hidden, alpha, nova, utility),
            grid.copy(hideByDefault = false).arrange(catalog, AppGridOrderApp::component, AppGridOrderApp::hiddenByDefault))
        assertEquals(listOf(pinned, alpha, bravo, nova, utility),
            grid.copy(sortOrder = AppGridSortOrder.NAME).arrange(catalog, AppGridOrderApp::component, AppGridOrderApp::hiddenByDefault))
        assertEquals(listOf(alpha, bravo),
            grid.arrange(catalog, AppGridOrderApp::component, AppGridOrderApp::hiddenByDefault, AppGridDesktop.FAVORITES_ONE))
        assertEquals(listOf(bravo, alpha, nova), grid.editableManualApps(catalog, AppGridOrderApp::component, AppGridOrderApp::hiddenByDefault))
    }

    @Test fun movingVisibleAppsPreservesHiddenAndMissingSlotsAndIgnoresAnOldDirection() {
        val oldOrder = listOf("alpha", "hidden", "bravo", "pinned", "offline")
        val reordered = AppGridManualOrder.move(oldOrder, listOf("alpha", "bravo", "new"), "bravo", "alpha", earlier = true)
        assertEquals(listOf("bravo", "hidden", "alpha", "pinned", "offline"), reordered)
        assertEquals(reordered,
            AppGridManualOrder.move(reordered, listOf("bravo", "alpha", "new"), "bravo", "alpha", earlier = true))
        assertEquals(listOf("bravo", "hidden", "new", "pinned", "offline", "alpha"),
            AppGridManualOrder.move(reordered, listOf("bravo", "alpha", "new"), "new", "alpha", earlier = true))
    }

    @Test fun movingAnUnrankedRowRanksOnlyThePrefixNeededForThatPosition() {
        val catalog = listOf("alpha", "bravo", "charlie", "delta")
        assertEquals(listOf("alpha", "charlie", "bravo"),
            AppGridManualOrder.move(emptyList(), catalog, "charlie", "bravo", earlier = true))
        assertEquals(listOf("alpha", "charlie", "bravo"),
            AppGridManualOrder.move(emptyList(), catalog, "bravo", "charlie", earlier = false))
    }

    @Test fun fullOrderRetainsSavedRowsAndRejectsAdditionalPositions() {
        val full = List(AppGridManualOrder.MAX_APPS) { "app-$it" }
        val visible = full.take(2) + "new"
        assertEquals(full.take(2).reversed() + full.drop(2),
            AppGridManualOrder.move(full, visible, full[0], full[1], earlier = false))
        assertThrows(AppGridManualOrderFull::class.java) {
            AppGridManualOrder.move(full, visible, "new", full[1], earlier = true)
        }
    }

    private fun app(name: String, hiddenByDefault: Boolean = false) =
        AppGridOrderApp("org.example.$name/org.example.$name.Main", hiddenByDefault)
}
