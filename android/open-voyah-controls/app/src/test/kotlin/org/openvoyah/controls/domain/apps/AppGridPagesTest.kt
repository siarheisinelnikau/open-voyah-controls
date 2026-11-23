package org.openvoyah.controls.domain.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AppGridPagesTest {
    @Test fun rowsFitWholeRowsWithSpacingAndAlwaysOne() {
        // The car's grid: 160 dp rows with 12 dp between them in about 400 dp give two rows.
        assertEquals(2, AppGridPages.rows(400, 174, 12))
        assertEquals(1, AppGridPages.rows(100, 174, 12))
        assertEquals(1, AppGridPages.rows(-5, 174, 12))
        assertEquals(3, AppGridPages.rows(3 * 100 + 2 * 10, 100, 10))
        assertEquals(2, AppGridPages.rows(3 * 100 + 2 * 10 - 1, 100, 10))
    }

    @Test fun tilesFillPagesOfWholeRows() {
        // Six columns and two rows, as on the head unit: twelve tiles a page.
        val pages = AppGridPages.paginate(List(30) { 1 }, columns = 6, rows = 2)
        assertEquals(listOf(0..11, 12..23, 24..29), pages)
        assertEquals(0, AppGridPages.pageOf(pages, 11))
        assertEquals(1, AppGridPages.pageOf(pages, 12))
        assertEquals(2, AppGridPages.pageOf(pages, 99))
    }

    @Test fun aWideCellThatDoesNotFitStartsTheNextRowAndMayStartTheNextPage() {
        // Controls tile, a half-width card, four apps: the card fits beside the tile, the apps fill the row and the next.
        assertEquals(listOf(0..5), AppGridPages.paginate(listOf(1, 3, 1, 1, 1, 1), columns = 6, rows = 2))
        // A card of four cells after five tiles does not fit the first row, so it starts the second.
        assertEquals(listOf(0..7, 8..8), AppGridPages.paginate(listOf(1, 1, 1, 1, 1, 4, 1, 1, 1), columns = 6, rows = 2))
        // A full-width state message takes a row of its own; one wider than the grid is narrowed to the grid.
        assertEquals(listOf(0..1, 2..2), AppGridPages.paginate(listOf(1, 6, 9), columns = 6, rows = 2))
    }

    @Test fun anEmptyGridStillHasOnePage() {
        assertEquals(listOf(IntRange.EMPTY), AppGridPages.paginate(emptyList(), columns = 4, rows = 3))
        assertEquals(0, AppGridPages.pageOf(listOf(IntRange.EMPTY), 0))
        assertThrows(IllegalArgumentException::class.java) { AppGridPages.paginate(listOf(1), columns = 0, rows = 1) }
    }

    @Test fun placingAnAppTakesTheTargetsPlaceInEitherDirection() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), AppGridMoves.place(order, "a", "c"))
        assertEquals(listOf("d", "a", "b", "c"), AppGridMoves.place(order, "d", "a"))
        assertEquals(order, AppGridMoves.place(order, "a", "a"))
        assertEquals(order, AppGridMoves.place(order, "a", "missing"))
    }

    @Test fun aMoveStaysInsideTheDraggedAppsGroup() {
        val pinned = setOf("p1", "p2")
        val order = listOf("p1", "p2", "a", "b", "c")
        val sameGroup = { x: String, y: String -> (x in pinned) == (y in pinned) }
        assertEquals("c", AppGridMoves.targetWithin(order, "a", "c", sameGroup))
        assertEquals("a", AppGridMoves.targetWithin(order, "c", "p1", sameGroup))
        assertEquals("p2", AppGridMoves.targetWithin(order, "p1", "b", sameGroup))
    }

    @Test fun manualOrderRanksOnlyWhatTheMoveNeedsAndKeepsHiddenRanks() {
        // "hidden" keeps its saved rank although All apps does not show it now.
        val saved = listOf("b", "hidden", "a")
        val visible = listOf("b", "a", "c", "d")
        assertEquals(listOf("hidden", "a", "c", "b"), AppGridMoves.manualOrder(saved, visible, "b", "c", rankAll = false))
        assertEquals(listOf("d", "b", "hidden", "a", "c"), AppGridMoves.manualOrder(saved, visible, "d", "b", rankAll = false))
        // From another sort order every visible app is ranked, so only the moved app changes place on screen.
        assertEquals(listOf("a", "c", "b", "d"), AppGridMoves.manualOrder(saved, visible, "b", "c", rankAll = true))
        assertEquals(saved, AppGridMoves.manualOrder(saved, visible, "b", "missing", rankAll = false))
        assertThrows(AppGridManualOrderFull::class.java) {
            AppGridMoves.manualOrder(List(AppGridManualOrder.MAX_APPS) { "saved$it" }, visible, "b", "c", rankAll = false)
        }
    }

    private fun component(name: String) = "org.example.$name/org.example.$name.Main"

    @Test fun movingOnAllAppsReordersPinsOrTheOthersAndMakesTheOrderManual() {
        val (p1, p2, a, b, c) = listOf("p1", "p2", "a", "b", "c").map(::component)
        val grid = AppGridConfiguration(pinnedApps = listOf(p1, p2), sortOrder = AppGridSortOrder.NAME)
        val visible = listOf(p1, p2, a, b, c)

        assertEquals(AppGridMove(pinnedApps = listOf(p2, p1)), grid.move(AppGridDesktop.MAIN, visible, p1, p2))
        val unpinned = grid.move(AppGridDesktop.MAIN, visible, c, a)
        assertEquals(AppGridMove(manualAppOrder = listOf(c, a, b), sortOrder = AppGridSortOrder.MANUAL), unpinned)
        // A pinned app cannot take an unpinned app's place, or the other way round.
        assertNull(grid.move(AppGridDesktop.MAIN, visible, p1, a))
        assertNull(grid.move(AppGridDesktop.MAIN, visible, a, a))
        assertNull(grid.move(AppGridDesktop.MAIN, visible, a, component("absent")))

        // Already manual: the saved order is kept and only the moved positions change.
        val manual = grid.copy(sortOrder = AppGridSortOrder.MANUAL, manualAppOrder = listOf(b, a))
        assertEquals(AppGridMove(manualAppOrder = listOf(a, c, b)), manual.move(AppGridDesktop.MAIN, listOf(p1, p2, b, a, c), b, c))
    }

    @Test fun movingOnAFavoritesDesktopReordersItsPins() {
        val (a, b, c) = listOf("a", "b", "c").map(::component)
        val desktops = AppGridDesktops(enabled = true, firstFavorites = listOf(a, b, c))
        val grid = AppGridConfiguration(desktops = desktops)
        val move = grid.move(AppGridDesktop.FAVORITES_ONE, listOf(a, b, c), c, a)
        assertEquals(AppGridMove(desktops = desktops.copy(firstFavorites = listOf(c, a, b))), move)
        // With extra desktops off the grid is All apps, where these apps are not pinned.
        assertEquals(AppGridSortOrder.MANUAL,
            grid.copy(desktops = desktops.copy(enabled = false)).move(AppGridDesktop.FAVORITES_ONE, listOf(a, b, c), c, a)?.sortOrder)
    }
}
