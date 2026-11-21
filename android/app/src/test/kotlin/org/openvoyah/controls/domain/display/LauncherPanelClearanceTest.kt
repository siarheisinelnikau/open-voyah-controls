package org.openvoyah.controls.domain.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.display.LauncherPanelClearance.leftPaddingPx
import org.openvoyah.controls.domain.display.LauncherPanelClearance.panelWidthPx
import org.openvoyah.controls.domain.display.LauncherPanelClearance.stripConfirmed
import org.openvoyah.controls.domain.display.WindowStripState.Phase

class LauncherPanelClearanceTest {
    private fun padding(strip: WindowStripState?, contentLeft: Int = 0, inset: Int = 0, display: Int = 0): Int =
        leftPaddingPx(panelWidthPx(display, 160, strip), contentLeft, inset, stripConfirmed(strip))

    @Test fun withoutAnyAnswerTheFullPanelWidthIsKept() {
        assertEquals(145, padding(null))
    }

    @Test fun everyReceiptThatIsNotVerifiedKeepsThePadding() {
        for (phase in Phase.entries.filter { it != Phase.ACTIVE }) {
            assertEquals(phase.name, 145, padding(WindowStripState(phase)))
        }
        // Applied or armed with a measured width: the agent's own width is kept, not dropped.
        assertEquals(171, padding(WindowStripState(Phase.CHECKING, 171)))
        assertEquals(171, padding(WindowStripState(Phase.STARTING, 171)))
    }

    @Test fun aVerifiedStripDropsThePadding() {
        assertTrue(stripConfirmed(WindowStripState(Phase.ACTIVE, 171)))
        assertEquals(0, padding(WindowStripState(Phase.ACTIVE, 171)))
    }

    @Test fun aVerifiedReceiptWithoutWidthIsNotAConfirmation() {
        assertFalse(stripConfirmed(WindowStripState(Phase.ACTIVE, 0)))
        assertEquals(145, padding(WindowStripState(Phase.ACTIVE, 0)))
    }

    @Test fun aSystemInsetThatCoversThePanelIsNotDoubled() {
        assertEquals(145, padding(null, inset = 145))
        assertEquals(171, padding(WindowStripState(Phase.ACTIVE, 171), inset = 171))
        // A wider inset is respected as it is.
        assertEquals(200, padding(null, inset = 200))
    }

    @Test fun contentThatAlreadyStartsRightOfThePanelNeedsNoGap() {
        assertEquals(0, padding(null, contentLeft = 145))
        assertEquals(0, padding(null, contentLeft = 740))
        // Partly beside the panel: only the covered part is added.
        assertEquals(45, padding(null, contentLeft = 100))
    }

    @Test fun otherDisplaysHaveNoPanel() {
        assertEquals(0, padding(null, display = 2))
        assertEquals(24, padding(null, display = 2, inset = 24))
    }

    @Test fun theFallbackWidthFollowsTheDisplayDensity() {
        assertEquals(145, panelWidthPx(0, 160, null))
        assertEquals(218, panelWidthPx(0, 240, null))
        assertEquals(145, panelWidthPx(0, 0, null))
    }
}
