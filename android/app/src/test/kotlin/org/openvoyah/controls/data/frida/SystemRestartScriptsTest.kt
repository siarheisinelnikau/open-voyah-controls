package org.openvoyah.controls.data.frida

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemRestartScriptsTest {
    @Test fun multimediaNeverIncludesCarServicesTheLauncherOrAudio() {
        val forbidden = listOf("com.qinggan.app.launcher", "com.qinggan.canbus.service", "com.qinggan.carsignal.service",
            "com.qinggan.cluster", "com.qinggan.app.vehicle", "com.qinggan.audiopolicy.service", "com.qinggan.systemui",
            "com.qinggan.windowpolicy.service", "com.qinggan.powermanager", "system_server", "audioserver", "mediaserver",
            "org.openvoyah.controls")
        val script = SystemRestartScripts.mediaRestart()
        for (name in forbidden) {
            assertFalse(name, name in SystemRestartScripts.MEDIA_PACKAGES)
            assertFalse(name, script.contains(" $name\n") || script.contains(" $name "))
        }
        assertEquals(listOf("com.qinggan.media", "com.qinggan.app.music", "com.qinggan.app.radio", "com.qinggan.app.video"),
            SystemRestartScripts.MEDIA_PACKAGES)
    }

    @Test fun theScriptStopsEachPackageAndWaitsForTheMediaCenter() {
        val script = SystemRestartScripts.mediaRestart()
        for (pkg in SystemRestartScripts.MEDIA_PACKAGES) assertTrue(pkg, script.contains("ovc_list=\$(ovc_pids $pkg)"))
        assertTrue(script.contains("ovc_center_before=\$(ovc_pids com.qinggan.media)"))
        assertTrue(script.contains("while [ \$ovc_i -lt ${SystemRestartScripts.MEDIA_CENTER_WAIT_SECONDS} ]"))
        // No force-stop: it would leave the factory apps in the stopped state and drop their alarms.
        assertFalse(script.contains("force-stop"))
        assertFalse(script.contains("reboot"))
    }

    @Test fun onlyPlainPackageNamesAndTheMediaCenterAreAccepted() {
        assertThrows(IllegalArgumentException::class.java) { SystemRestartScripts.mediaRestart(listOf("com.qinggan.app.music")) }
        assertThrows(IllegalArgumentException::class.java) {
            SystemRestartScripts.mediaRestart(listOf("com.qinggan.media", "com.x; reboot"))
        }
        assertThrows(IllegalArgumentException::class.java) { SystemRestartScripts.mediaRestart(emptyList()) }
    }

    @Test fun theReportCountsStoppedPackagesAndTheMediaCenterReturn() {
        val result = SystemRestartScripts.parseMediaRestart(
            "OVC_MEDIA\tcom.qinggan.media\tstopped\r\nOVC_MEDIA\tcom.qinggan.app.music\tstopped\n" +
                "OVC_MEDIA\tcom.qinggan.app.radio\tidle\nOVC_MEDIA\tcom.qinggan.app.video\tidle\nnoise\nOVC_MEDIA_CENTER\tback\t3\n")
        assertEquals(listOf("com.qinggan.media", "com.qinggan.app.music"), result.stopped)
        assertTrue(result.centerBack)
        assertEquals(3, result.seconds)
        assertTrue(result.successful)
    }

    @Test fun aMissingMediaCenterOrAFailedKillIsNotASuccess() {
        val missing = SystemRestartScripts.parseMediaRestart("OVC_MEDIA\tcom.qinggan.media\tstopped\nOVC_MEDIA_CENTER\tmissing\n")
        assertFalse(missing.centerBack)
        assertNull(missing.seconds)
        assertFalse(missing.successful)

        val failed = SystemRestartScripts.parseMediaRestart(
            "OVC_MEDIA\tcom.qinggan.app.music\tfailed\nOVC_MEDIA\tcom.example.other\tstopped\nOVC_MEDIA_CENTER\tback\t1\n")
        assertEquals(listOf("com.qinggan.app.music"), failed.failed)
        assertEquals(emptyList<String>(), failed.stopped)
        assertFalse(failed.successful)
    }

    @Test fun theRebootIsAnOrderlyPowerManagerReboot() {
        val script = SystemRestartScripts.REBOOT
        assertTrue(script.contains("setsid sh -c 'sleep 1; svc power reboot open-voyah-controls' </dev/null >/dev/null 2>&1 &"))
        assertTrue(script.endsWith("printf '%s\\n' OVC_REBOOT_SCHEDULED\n"))
        assertFalse(script.contains("reboot -f") || script.contains("kill"))
    }

    @Test fun reviewRowsOnlyMarkTheRestartOperationsAsVerified() {
        val device = "fp/x:11/1/1:user/release-keys\t30\tarm64-v8a\tf6802h"
        val catalog = "$device\tcom.qinggan.app.launcher\t${"a".repeat(64)}\t${"b".repeat(64)}\n"
        val reviewed = ReviewedFirmware.select(catalog,
            "$device\tmedia-restart\n$device\tsystem-reboot\n",
            ReviewedFirmware.Device("fp/x:11/1/1:user/release-keys", "30", "arm64-v8a", "f6802h"))
        assertEquals(setOf(ReviewedFirmware.MEDIA_RESTART, ReviewedFirmware.SYSTEM_REBOOT), reviewed?.operations)
        val none = ReviewedFirmware.select(catalog, "",
            ReviewedFirmware.Device("fp/x:11/1/1:user/release-keys", "30", "arm64-v8a", "f6802h"))
        assertEquals(emptySet<String>(), none?.operations)
    }
}
