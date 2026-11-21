package org.openvoyah.controls.data.frida

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openvoyah.controls.domain.display.WindowStripState
import org.openvoyah.controls.domain.display.WindowStripState.Phase

class WindowStripReceiptTest {
    private fun decode(insets: Any?, width: Any? = null, status: Any? = "applied") =
        WindowStripReceipt.decodeFields(status, insets, width)

    @Test fun theProgressOfAnAttemptIsReadWithItsWidth() {
        assertEquals(WindowStripState(Phase.OFF), decode("inactive", 0))
        assertEquals(WindowStripState(Phase.STARTING, 171), decode("armed", 171))
        assertEquals(WindowStripState(Phase.CHECKING, 171), decode("applied", 171))
        assertEquals(WindowStripState(Phase.ACTIVE, 171), decode("verified", 171))
        for (waiting in listOf("no_panel", "waiting_panel", "panel_lost")) {
            assertEquals(waiting, WindowStripState(Phase.WAITING_PANEL), decode(waiting, 171))
        }
        assertEquals(WindowStripState(Phase.OTHER_USER), decode("other_user"))
    }

    @Test fun aRefusalKeepsTheAgentsTokenAndIsToldApartFromAFailure() {
        assertEquals(WindowStripState(Phase.UNSUPPORTED, code = "oem_policy"), decode("oem_policy"))
        assertEquals(WindowStripState(Phase.UNSUPPORTED, code = "system_nav_bar"), decode("system_nav_bar", 0))
        assertEquals(WindowStripState(Phase.FAILED, code = "verify_failed"), decode("verify_failed"))
        assertEquals(WindowStripState(Phase.FAILED, code = "guard_unavailable"), decode("guard_unavailable"))
        assertEquals(WindowStripState(Phase.BLOCKED), decode("blocked"))
        assertEquals(WindowStripState(Phase.NOT_UNDONE), decode("rollback_failed"))
    }

    @Test fun anythingOutsideTheVocabularyIsUnknownAndNeverShownAsText() {
        val unknown = WindowStripState(Phase.UNKNOWN)
        assertEquals(unknown, decode("verified"))
        assertEquals(unknown, decode("verified", 0))
        assertEquals(unknown, decode("verified", 4096))
        assertEquals(unknown, decode("verified", "171"))
        assertEquals(unknown, decode("verified\nstatus=forged", 171))
        assertEquals(unknown, decode("private.package/.Activity"))
        assertEquals(unknown, decode(null))
        assertEquals(unknown, decode(7))
        assertEquals(unknown, decode("verified", 171, status = "failed"))
        assertEquals(unknown, decode("verified", 171, status = null))
        // A width outside the plausible range is dropped where the phase does not depend on it.
        assertEquals(WindowStripState(Phase.CHECKING), decode("applied", 4096))
    }
}
