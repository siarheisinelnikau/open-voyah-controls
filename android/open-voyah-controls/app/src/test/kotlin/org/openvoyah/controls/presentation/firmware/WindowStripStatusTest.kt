package org.openvoyah.controls.presentation.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.R
import org.openvoyah.controls.domain.display.WindowConnection
import org.openvoyah.controls.domain.display.WindowStripState
import org.openvoyah.controls.domain.display.WindowStripState.Phase
import org.openvoyah.controls.presentation.maintenance.WindowStripRow

class WindowStripStatusTest {
    // Resource names stand in for their texts; the two that carry a value keep a format argument.
    private val text: (Int) -> String = { id ->
        when (id) {
            R.string.window_strip_status_checking -> "checking %1\$d"
            R.string.window_strip_status_active -> "active %1\$d"
            R.string.window_strip_status_unsupported -> "unsupported %1\$s"
            R.string.window_strip_status_failed -> "failed %1\$s"
            R.string.window_strip_status_off -> "off"
            R.string.window_strip_status_service_off -> "service off"
            R.string.window_strip_status_starting -> "starting"
            else -> "other $id"
        }
    }

    @Test fun everyPhaseHasItsOwnLineAndValuesAreFilledIn() {
        val lines = Phase.entries.associateWith { WindowStripState(it, 171, "oem_policy").statusText(text) }
        assertEquals(Phase.entries.size, lines.values.toSet().size)
        assertEquals("active 171", lines[Phase.ACTIVE])
        assertEquals("checking 171", lines[Phase.CHECKING])
        assertEquals("unsupported oem_policy", lines[Phase.UNSUPPORTED])
        assertEquals("failed oem_policy", lines[Phase.FAILED])
        // Applied without a known width says no more than "starting" does.
        assertEquals("starting", WindowStripState(Phase.CHECKING).statusText(text))
    }

    @Test fun onlyAnAttemptInProgressIsFollowed() {
        assertEquals(setOf(Phase.STARTING, Phase.CHECKING, Phase.WAITING_PANEL),
            Phase.entries.filter { WindowStripState(it).settling }.toSet())
    }

    @Test fun withTheOptionOffTheAgentIsNotAskedAndTheSwitchNeedsTheService() {
        var asked = 0
        val state = { asked++; WindowStripState(Phase.ACTIVE, 171) }
        assertEquals(WindowStripRow(false, "off", changeable = true) to false,
            windowStripRow(false, WindowConnection.ENABLED, state, text))
        for (connection in listOf(WindowConnection.DISABLED, WindowConnection.DISCONNECTED)) {
            assertEquals(WindowStripRow(false, "service off", changeable = false) to false,
                windowStripRow(false, connection, state, text))
        }
        assertEquals(0, asked)
    }

    @Test fun aSavedChoiceShowsWhatTheAgentReportsAndCanAlwaysBeWithdrawn() {
        val (on, settled) = windowStripRow(true, WindowConnection.ENABLED, { WindowStripState(Phase.ACTIVE, 171) }, text)
        assertEquals(WindowStripRow(true, "active 171", changeable = true), on)
        assertFalse(settled)
        val (starting, moving) = windowStripRow(true, WindowConnection.ENABLED, { WindowStripState(Phase.STARTING) }, text)
        assertEquals("starting", starting.status)
        assertTrue(moving)

        var asked = 0
        val (stranded, following) = windowStripRow(true, WindowConnection.DISABLED,
            { asked++; WindowStripState(Phase.ACTIVE, 171) }, text)
        assertEquals(WindowStripRow(true, "service off", changeable = true), stranded)
        assertFalse(following)
        assertEquals(0, asked)
    }
}
