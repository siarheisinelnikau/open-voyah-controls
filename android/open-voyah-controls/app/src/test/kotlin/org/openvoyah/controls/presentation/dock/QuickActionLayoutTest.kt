package org.openvoyah.controls.presentation.dock

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.openvoyah.controls.domain.dock.QuickAction
import org.openvoyah.controls.domain.dock.QuickActionArea
import org.openvoyah.controls.domain.dock.QuickActionCompactEdge
import org.openvoyah.controls.domain.dock.QuickActionCompactHandleSide
import org.openvoyah.controls.domain.dock.QuickActionCompactLabels
import org.openvoyah.controls.domain.dock.QuickActionHandleSize
import org.openvoyah.controls.domain.dock.QuickActionIdleCollapse
import org.openvoyah.controls.domain.dock.QuickActionRailWidth
import org.openvoyah.controls.domain.dock.QuickActionSettings
import org.openvoyah.controls.domain.dock.QuickActionSide

class QuickActionLayoutTest {
    @Test fun selectionKeepsOrderAndAtLeastOneShortcut() {
        val initial = QuickActionSettings(actions = listOf(QuickAction.APPS))
        assertSame(initial, initial.select(QuickAction.APPS, false))
        assertSame(initial, initial.move(QuickAction.SETTINGS, earlier = false))
        val expanded = initial.select(QuickAction.VEHICLE, true).select(QuickAction.SETTINGS, true)
        assertSame(expanded, expanded.select(QuickAction.VEHICLE, true))
        assertSame(expanded, expanded.move(QuickAction.APPS, earlier = true))
        assertSame(expanded, expanded.move(QuickAction.SETTINGS, earlier = false))
        val reordered = expanded.move(QuickAction.VEHICLE, earlier = true)
        assertEquals(listOf(QuickAction.VEHICLE, QuickAction.APPS, QuickAction.SETTINGS), reordered.actions)
        assertEquals(expanded, reordered.move(QuickAction.VEHICLE, earlier = false))
        assertEquals(listOf(QuickAction.VEHICLE, QuickAction.SETTINGS), reordered.select(QuickAction.APPS, false).actions)
        val destinations = initial.select(QuickAction.SEAT_MEMORY, true).select(QuickAction.ROUTINES, true)
        assertEquals(listOf(QuickAction.APPS, QuickAction.ROUTINES, QuickAction.SEAT_MEMORY),
            destinations.move(QuickAction.ROUTINES, earlier = true).actions)
        assertThrows(IllegalArgumentException::class.java) { initial.copy(actions = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { initial.copy(actions = initial.actions + initial.actions) }
    }

    @Test fun screenAreaGeometryAndKeyboardUseIndependentVisibilityRules() {
        val off = QuickActionSettings()
        val on = off.copy(enabled = true)
        fun layout(settings: QuickActionSettings, w: Int = 1280, h: Int = 480, scale: Float = 1f,
                   apps: Boolean = true, keyboard: Boolean = false) =
            quickActionLayout(w.dp, h.dp, scale, settings, apps, keyboard)
        assertEquals(DockEdge.HIDDEN, layout(off).edge)
        assertEquals(QuickActionLayout(DockEdge.RIGHT, 132.dp), layout(on))
        assertEquals(QuickActionLayout(DockEdge.LEFT, 68.dp), layout(on.copy(side = QuickActionSide.LEFT, showLabels = false)))
        assertEquals(DockEdge.BOTTOM, layout(on, w = 683, h = 400).edge)
        assertEquals(DockEdge.BOTTOM, layout(on, scale = 1.3f).edge)
        assertEquals(DockEdge.BOTTOM, layout(on, w = 360, h = 640, scale = 2f).edge)
        assertEquals(DockEdge.HIDDEN, layout(on, w = 683, h = 180).edge)
        assertEquals(DockEdge.HIDDEN, layout(on, w = 220, h = 510, scale = 2f).edge)
        assertEquals(DockEdge.HIDDEN, layout(on, keyboard = true).edge)
        assertEquals(DockEdge.HIDDEN, layout(on.copy(area = QuickActionArea.APPS), apps = false).edge)
        assertEquals(DockEdge.RIGHT, layout(on.copy(area = QuickActionArea.APPS)).edge)
        assertEquals(DockEdge.HIDDEN, layout(on.copy(area = QuickActionArea.OTHER_SCREENS)).edge)
        assertEquals(DockEdge.RIGHT, layout(on.copy(area = QuickActionArea.OTHER_SCREENS), apps = false).edge)
    }

    @Test fun compactWidthReservesTheSameMinimumPageAndLargeTypeStillUsesTheHorizontalBar() {
        val settings = QuickActionSettings(enabled = true, railWidth = QuickActionRailWidth.COMPACT, collapseAfterUse = true)
        fun layout(width: Int, scale: Float = 1f, options: QuickActionSettings = settings, expanded: Boolean = true) =
            quickActionLayout(width.dp, 400.dp, scale, options, apps = true, keyboardOpen = false, expanded = expanded)
        assertEquals(QuickActionLayout(DockEdge.RIGHT, 104.dp), layout(744))
        assertEquals(DockEdge.BOTTOM, layout(743).edge)
        assertEquals(DockEdge.BOTTOM, layout(744, options = settings.copy(railWidth = QuickActionRailWidth.STANDARD)).edge)
        assertEquals(QuickActionLayout(DockEdge.RIGHT, 56.dp), layout(696, options = settings.copy(showLabels = false)))
        assertEquals(QuickActionLayout(DockEdge.RIGHT, 48.dp), layout(744, expanded = false))
        assertEquals(QuickActionLayout(DockEdge.RIGHT, 130.dp), layout(930, 1.25f))
        assertEquals(DockEdge.BOTTOM, layout(1280, 1.3f).edge)
    }

    @Test fun collapsingNeverMovesTheHandleOrCrowdsOutThePage() {
        val settings = QuickActionSettings(enabled = true, collapseAfterUse = true, compactEdge = QuickActionCompactEdge.TOP)
        fun fit(width: Int, height: Int, opened: Boolean, scale: Float = 1f, keyboard: Boolean = false) =
            quickActionLayout(width.dp, height.dp, scale, settings, apps = true, keyboardOpen = keyboard, expanded = opened)
        assertEquals(QuickActionLayout(DockEdge.RIGHT, 48.dp), fit(1280, 480, false))
        assertEquals(QuickActionLayout(DockEdge.RIGHT, 132.dp), fit(1280, 480, true))
        // A 48 dp rail could fit here by itself. Its open rail would change edges.
        for (opened in listOf(false, true)) {
            assertEquals(DockEdge.TOP, fit(700, 400, opened).edge)
            assertEquals(DockEdge.TOP, fit(360, 640, opened, 2f).edge)
            assertEquals(DockEdge.HIDDEN, fit(300, 640, opened, 2f).edge)
            assertEquals(DockEdge.HIDDEN, fit(360, 380, opened, 2f).edge)
            assertEquals(DockEdge.HIDDEN, fit(1280, 480, opened, keyboard = true).edge)
        }
        assertEquals(DockEdge.BOTTOM, quickActionLayout(683.dp, 400.dp, 1f,
            settings.copy(compactEdge = QuickActionCompactEdge.BOTTOM), apps = true, keyboardOpen = false).edge)
    }

    @Test fun idleTimeoutAlsoKeepsSpaceForTheHandle() {
        val settings = QuickActionSettings(enabled = true, idleCollapse = QuickActionIdleCollapse.TEN_SECONDS)
        val wide = quickActionLayout(1280.dp, 480.dp, 1f, settings, apps = false, keyboardOpen = false, expanded = false)
        assertEquals(QuickActionLayout(DockEdge.RIGHT, 48.dp), wide)
        val cramped = quickActionLayout(200.dp, 400.dp, 1f, settings, apps = false, keyboardOpen = false, expanded = false)
        assertEquals(DockEdge.HIDDEN, cramped.edge)
    }

    @Test fun fixedSideInsetKeepsTheLargerRailOrHandleWidthInBothStates() {
        val fixed = QuickActionSettings(enabled = true, railWidth = QuickActionRailWidth.COMPACT,
            showLabels = false, collapseAfterUse = true, handleSize = QuickActionHandleSize.LARGE, keepSideInset = true)
        for (expanded in listOf(false, true)) {
            assertEquals(QuickActionLayout(DockEdge.LEFT, 60.dp), quickActionLayout(
                700.dp, 400.dp, 1f, fixed.copy(side = QuickActionSide.LEFT), apps = true, keyboardOpen = false, expanded = expanded,
            ))
            assertEquals(QuickActionLayout(DockEdge.RIGHT, 104.dp), quickActionLayout(
                744.dp, 400.dp, 1f, fixed.copy(showLabels = true), apps = true, keyboardOpen = false, expanded = expanded,
            ))
            assertEquals(DockEdge.BOTTOM, quickActionLayout(
                699.dp, 400.dp, 1f, fixed, apps = true, keyboardOpen = false, expanded = expanded,
            ).edge)
            assertEquals(DockEdge.HIDDEN, quickActionLayout(
                744.dp, 400.dp, 1f, fixed, apps = true, keyboardOpen = true, expanded = expanded,
            ).edge)
        }
    }

    @Test fun largeCollapsedHandleAndOpenRailBothLeaveRoomForThePage() {
        val large = QuickActionSettings(enabled = true, side = QuickActionSide.LEFT, showLabels = false,
            collapseAfterUse = true, railWidth = QuickActionRailWidth.COMPACT, compactEdge = QuickActionCompactEdge.TOP,
            handleSize = QuickActionHandleSize.LARGE, compactHandleSide = QuickActionCompactHandleSide.RIGHT)
        fun fit(width: Int, height: Int, opened: Boolean, settings: QuickActionSettings = large) =
            quickActionLayout(width.dp, height.dp, 1f, settings, apps = true, keyboardOpen = false, expanded = opened)
        assertEquals(QuickActionLayout(DockEdge.LEFT, 60.dp), fit(700, 400, false))
        assertEquals(QuickActionLayout(DockEdge.LEFT, 56.dp), fit(700, 400, true))
        for (opened in listOf(false, true)) {
            assertEquals(DockEdge.TOP, fit(699, 400, opened).edge)
            assertEquals(DockEdge.TOP, fit(112, 236, opened).edge)
            assertEquals(DockEdge.HIDDEN, fit(111, 236, opened).edge)
            assertEquals(DockEdge.HIDDEN, fit(112, 235, opened).edge)
        }
        val standard = large.copy(handleSize = QuickActionHandleSize.STANDARD)
        assertEquals(QuickActionLayout(DockEdge.LEFT, 48.dp), fit(696, 400, false, standard))
        assertEquals(QuickActionLayout(DockEdge.LEFT, 56.dp), fit(696, 400, true, standard))
    }

    @Test fun compactHandleKeepsTheDefaultStartAndSupportsPhysicalSides() {
        val directions = listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)
        assertEquals(listOf(true, true), directions.map(QuickActionCompactHandleSide.DEFAULT::isAtStart))
        assertEquals(listOf(true, false), directions.map(QuickActionCompactHandleSide.LEFT::isAtStart))
        assertEquals(listOf(false, true), directions.map(QuickActionCompactHandleSide.RIGHT::isAtStart))
    }

    @Test fun compactLabelChoiceOnlyChangesFitOnHorizontalBars() {
        val labels = QuickActionSettings(enabled = true)
        val icons = labels.copy(compactLabels = QuickActionCompactLabels.ICONS)
        fun fit(width: Int, settings: QuickActionSettings) =
            quickActionLayout(width.dp, 400.dp, 1f, settings, apps = false, keyboardOpen = false)
        assertEquals(QuickActionLayout(DockEdge.RIGHT, 132.dp), fit(1280, icons))
        assertEquals(DockEdge.HIDDEN, fit(150, labels).edge)
        assertEquals(DockEdge.BOTTOM, fit(150, icons).edge)
    }
}
