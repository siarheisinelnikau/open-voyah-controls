package org.openvoyah.controls.data.frida

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.data.frida.ActivationRecovery.Outcome

class ActivationRecoveryTest {
    @Test fun theScriptRunsTheVerifiedBootEntryInTheRequestedMode() {
        val script = ActivationRecovery.script("/data/adb/open-voyah-controls", ActivationRecovery.Mode.MANUAL)
        assertTrue(script.contains("/system/bin/sh '/data/adb/open-voyah-controls/boot-start.sh' --recover manual 2>/dev/null"))
        assertTrue(ActivationRecovery.script("/data/adb/open-voyah-controls", ActivationRecovery.Mode.AUTO).contains("--recover auto"))
        assertThrows(IllegalArgumentException::class.java) { ActivationRecovery.script("/data/adb/x'; reboot; '", ActivationRecovery.Mode.AUTO) }
        assertThrows(IllegalArgumentException::class.java) { ActivationRecovery.script("/data/adb/../tmp", ActivationRecovery.Mode.AUTO) }
    }

    @Test fun aRecoveredDeploymentNamesItsCauseAndChannels() {
        val result = ActivationRecovery.parse("noise\nOVC_RECOVER outcome=recovered reason=lifetime channels=main,locale-hiboard,window\n")
        assertEquals(ActivationRecovery.Result(Outcome.RECOVERED, "lifetime", listOf("main", "locale-hiboard", "window")), result)
    }

    @Test fun everySupervisorOutcomeIsRead() {
        assertEquals(ActivationRecovery.Result(Outcome.NONE, "active"), ActivationRecovery.parse("OVC_RECOVER outcome=none reason=active"))
        assertEquals(ActivationRecovery.Result(Outcome.REBOOT, "visual"), ActivationRecovery.parse("OVC_RECOVER outcome=reboot reason=visual"))
        assertEquals(ActivationRecovery.Result(Outcome.REBOOT, "same-boot"), ActivationRecovery.parse("OVC_RECOVER outcome=reboot reason=same-boot"))
        assertEquals(ActivationRecovery.Result(Outcome.WAITING, "locked"), ActivationRecovery.parse("OVC_RECOVER outcome=waiting reason=locked"))
        assertEquals(ActivationRecovery.Result(Outcome.PC, "deployment"), ActivationRecovery.parse("OVC_RECOVER outcome=pc reason=deployment"))
    }

    @Test fun noOrUnknownReplyMeansThePcIsNeeded() {
        // boot-start.sh prints nothing on standard output when the scripts no longer match the receipt.
        assertEquals(ActivationRecovery.Result(Outcome.PC, "unverified"), ActivationRecovery.parse(""))
        assertEquals(ActivationRecovery.Result(Outcome.PC, "unverified"), ActivationRecovery.parse("OVC_RECOVER outcome=maybe reason=x"))
        assertEquals(Outcome.PC, ActivationRecovery.parse("OVC_RECOVER reason=lifetime").outcome)
    }

    @Test fun unexpectedFieldTextIsNotPassedOn() {
        val result = ActivationRecovery.parse("OVC_RECOVER outcome=recovered reason=LIFE;rm channels=main;x")
        assertEquals(ActivationRecovery.Result(Outcome.RECOVERED, "-"), result)
    }
}
