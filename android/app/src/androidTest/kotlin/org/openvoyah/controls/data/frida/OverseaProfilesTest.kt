package org.openvoyah.controls.data.frida

import android.test.AndroidTestCase
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import java.io.File

class OverseaProfilesTest : AndroidTestCase() {
    private val nonce = "1".repeat(32)

    fun testOnlyAnExactSingleProbeReceiptIdentifiesTheInterface() {
        val prefix = "OVC_OVERSEA_PROFILE_PROBE $nonce "
        for (state in listOf("guest", "identified")) assertEquals(UiText.get(R.string.profile_oversea_recovery_missing), OverseaProfiles.refusal(prefix + "api-present account=$state", nonce).message)
        for (state in listOf("unavailable", "unstable")) assertEquals(UiText.get(R.string.profile_oversea_account_unavailable), OverseaProfiles.refusal(prefix + "api-present account=$state", nonce).message)
        assertEquals(UiText.get(R.string.profile_oversea_api_unsupported), OverseaProfiles.refusal(prefix + "unsupported account=unavailable", nonce).message)
        for (reply in listOf("", prefix + "selected", prefix + "api-present account=identified\n" + prefix + "api-present account=identified",
            prefix.replace(nonce, "2".repeat(32)) + "api-present account=identified", prefix + "api-present", prefix + "api-present account=A")) {
            assertEquals(UiText.get(R.string.profile_oversea_probe_unavailable), OverseaProfiles.refusal(reply, nonce).message)
        }
    }

    fun testOverseasSlotsDoNotAcceptCommandsOrOtherProducts() {
        for (slot in listOf("A", "B", "C")) OverseaProfiles.requireSlot(slot)
        for (slot in listOf("a", "D", "A; reboot", "")) {
            try { OverseaProfiles.requireSlot(slot); fail() } catch (_: IllegalArgumentException) { }
        }
        assertTrue(OverseaProfiles.isProduct("f6811"))
        assertFalse(OverseaProfiles.isProduct("f6805h"))
    }

    fun testProbeCommandParsesOnAndroidWithoutExecutingOrChangingAnAccount() {
        val base = ProductionRootAccess.BASE
        val command = OverseaProfiles.probeCommand(base, "$base/open-voyah-controls-frida-17.26.2", "a".repeat(64), nonce)
        assertFalse(command.contains("setprop"))
        assertFalse(command.contains("chooseUser"))
        assertFalse(command.contains("kill "))
        val script = File.createTempFile("oversea-probe-", ".sh", context.cacheDir)
        try {
            script.writeText(command)
            assertEquals(0, ProcessBuilder("/system/bin/sh", "-n", script.path).start().waitFor())
        } finally { script.delete() }
    }
}
