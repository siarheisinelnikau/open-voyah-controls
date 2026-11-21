package org.openvoyah.controls.data.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openvoyah.controls.domain.apps.AppOpMode

class RootAppCommandsTest {
    private val root = "test \"\$(id -u)\" = 0"

    private fun rejected(command: () -> Unit) {
        try {
            command()
            fail("An unsafe value reached the shell")
        } catch (_: IllegalArgumentException) {
            // Rejected before dispatch.
        }
    }

    @Test fun permissionCommandsGrantAndRevokeOneRuntimePermission() {
        assertEquals("$root && pm grant --user 0 org.example.media android.permission.CAMERA",
            RootAppCommands.permission(0, "org.example.media", "android.permission.CAMERA", true))
        assertEquals("$root && pm revoke --user 10 org.example.media android.permission.CAMERA",
            RootAppCommands.permission(10, "org.example.media", "android.permission.CAMERA", false))
    }

    @Test fun appOpCommandsSetThePackageModeAndTheNumericUidMode() {
        // Android 9's appops has no --uid option; a number in the package position is a UID on 9 and 11.
        assertEquals("$root && appops set --user 0 org.example.media android:system_alert_window allow && " +
            "appops set 10123 android:system_alert_window allow",
            RootAppCommands.appOp(0, "org.example.media", 10123, "android:system_alert_window", AppOpMode.ALLOWED))
        val commands = mutableListOf<String>()
        RootPermissionWriter { commands += it; "" }.setAppOp(0, "org.example.media", 10123, "android:get_usage_stats", AppOpMode.IGNORED)
        assertEquals(listOf("$root && appops set --user 0 org.example.media android:get_usage_stats ignore && " +
            "appops set 10123 android:get_usage_stats ignore"), commands)
        rejected { RootAppCommands.appOp(0, "org.example.media", 0, "android:system_alert_window", AppOpMode.ALLOWED) }
    }

    @Test fun onlyDecisiveAppOpModesAreWritten() {
        // Default and foreground modes depend on other state; a switch writes only an explicit answer.
        rejected { RootAppCommands.appOp(0, "org.example.media", 10123, "android:system_alert_window", AppOpMode.DEFAULT) }
        rejected { RootAppCommands.appOp(0, "org.example.media", 10123, "android:system_alert_window", AppOpMode.FOREGROUND) }
    }

    @Test fun appOpReadsMarkEachOperationAndStopOnAFailure() {
        val operations = listOf("android:system_alert_window", "android:get_usage_stats")
        assertEquals(listOf(
            "$root || exit 1",
            "echo '${RootAppCommands.APP_OP} android:system_alert_window'",
            "appops get --user 0 org.example.media android:system_alert_window || exit 1",
            "echo '${RootAppCommands.APP_OP} android:get_usage_stats'",
            "appops get --user 0 org.example.media android:get_usage_stats || exit 1",
            "",
        ), RootAppCommands.readAppOps(0, "org.example.media", operations).lines())
        rejected { RootAppCommands.readAppOps(0, "org.example.media", emptyList()) }
        rejected { RootAppCommands.readAppOps(0, "org.example.media", listOf("SYSTEM_ALERT_WINDOW")) }
    }

    @Test fun appOpAnswersFollowAndroidsUidPackageAndDefaultOrder() {
        val operations = listOf("android:system_alert_window", "android:write_settings", "android:get_usage_stats",
            "android:request_install_packages", "android:manage_external_storage")
        val marker = RootAppCommands.APP_OP
        val output = listOf(
            "$marker android:system_alert_window",
            "Uid mode: SYSTEM_ALERT_WINDOW: deny",
            "SYSTEM_ALERT_WINDOW: allow; time=+2m3s ago",
            "$marker android:write_settings",
            "WRITE_SETTINGS: allow; time=+11ms ago; duration=+5ms",
            "$marker android:get_usage_stats",
            "No operations.",
            "Default mode: default",
            "$marker android:request_install_packages",
            "No operations.",
            "$marker android:unrelated",
            "UNRELATED: allow",
        ).joinToString("\r\n")
        assertEquals(mapOf(
            "android:system_alert_window" to AppOpMode.ERRORED,
            "android:write_settings" to AppOpMode.ALLOWED,
            "android:get_usage_stats" to AppOpMode.DEFAULT,
            "android:request_install_packages" to AppOpMode.DEFAULT,
        ), RootAppCommands.appOpModes(output, operations))
        // An answer Controls cannot read stays unknown rather than defaulting.
        assertEquals(emptyMap<String, AppOpMode>(),
            RootAppCommands.appOpModes(listOf("$marker android:write_settings", "WRITE_SETTINGS: maybe").joinToString("\n"), operations))
        val reads = mutableListOf<String>()
        val modes = RootPermissionWriter { reads += it; output }.readAppOps(0, "org.example.media", operations)
        assertEquals(AppOpMode.ERRORED, modes["android:system_alert_window"])
        assertEquals(listOf(RootAppCommands.readAppOps(0, "org.example.media", operations)), reads)
    }

    @Test fun onlyQualifiedNamesReachTheShell() {
        val unsafe = listOf("org.example.media; reboot", "org.example.media\nreboot", "'org.example.media'", "*", "media",
            "org.example.\$(id)", "org.example.media:remote")
        for (value in unsafe) {
            rejected { RootAppCommands.forceStop(0, value) }
            rejected { RootAppCommands.permission(0, value, "android.permission.CAMERA", true) }
            rejected { RootAppCommands.permission(0, "org.example.media", value, true) }
            rejected { RootAppCommands.appOp(0, value, 10123, "android:system_alert_window", AppOpMode.ALLOWED) }
            rejected { RootAppCommands.readAppOps(0, value, listOf("android:system_alert_window")) }
        }
        rejected { RootAppCommands.appOp(0, "org.example.media", 10123, "SYSTEM_ALERT_WINDOW; reboot", AppOpMode.ALLOWED) }
        rejected { RootAppCommands.forceStop(-1, "org.example.media") }
    }

    @Test fun forceStopChecksRootStopsOnceAndMatchesNamedProcesses() {
        val script = RootAppCommands.forceStop(0, "ru.yandex.yandexnavi")
        val lines = script.lines()
        assertEquals("$root || exit 1", lines.first())
        assertEquals(1, Regex("am force-stop").findAll(script).count())
        assertTrue(script.contains("am force-stop --user 0 ru.yandex.yandexnavi || exit 1"))
        // Toybox ps: the main process and the package's named processes such as ru.yandex.yandexnavi:AppMetrica.
        // An empty process list proves nothing, so the script fails instead of reporting a stop.
        assertTrue(script.contains("ovc_names=\$(ps -A -o NAME=) && test -n \"\$ovc_names\" || exit 1"))
        assertTrue(script.contains("for ovc_name in \$ovc_names; do"))
        assertTrue(script.contains("case \"\$ovc_name\" in ru.yandex.yandexnavi|ru.yandex.yandexnavi:*)"))
        assertTrue(script.contains("test \"\$ovc_try\" -ge 10"))
        assertTrue(script.endsWith("\n"))
    }

    @Test fun stopOutputIsReadFromItsLastLineOnly() {
        assertEquals(true, RootAppCommands.stopped("${RootAppCommands.STOPPED}\n"))
        assertEquals(false, RootAppCommands.stopped("some warning\r\n${RootAppCommands.RUNNING}\r\n"))
        assertNull(RootAppCommands.stopped(""))
        assertNull(RootAppCommands.stopped("${RootAppCommands.STOPPED}\nError: unexpected\n"))
        assertNull(RootAppCommands.stopped("OVC_APP_STOPPED_LATER"))
    }

    @Test fun theStopperSendsOneScriptAndReportsItsAnswer() {
        val scripts = mutableListOf<String>()
        val stopper = RootAppStopper { scripts += it; RootAppCommands.STOPPED }
        assertEquals(true, stopper.stop(0, "org.example.media"))
        assertEquals(listOf(RootAppCommands.forceStop(0, "org.example.media")), scripts)
        assertEquals(false, RootAppStopper { RootAppCommands.RUNNING }.stop(0, "org.example.media"))
        assertNull(RootAppStopper { "" }.stop(0, "org.example.media"))
        assertFalse(scripts.size > 1)
    }
}
