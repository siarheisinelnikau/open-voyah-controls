package org.openvoyah.controls

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.InstrumentationTestCase
import java.io.File
import java.io.IOException
import org.openvoyah.controls.data.apps.RootAppStopper
import org.openvoyah.controls.data.apps.RootPermissionWriter
import org.openvoyah.controls.platform.apps.AndroidAppAccess
import org.openvoyah.controls.platform.apps.InstalledAppPermissions
import org.openvoyah.controls.platform.apps.InstalledAppStopper
import org.openvoyah.controls.platform.apps.PermissionChange

/**
 * Runs the scripts Controls sends to its root shell through adb's shell on an isolated emulator. The shell user may
 * grant permissions, set app-ops and force-stop apps, so only the scripts' root check is replaced. The test package is
 * an app installed on top of the system image, which Controls manages, and it has no process of its own while the
 * instrumentation runs inside Controls.
 */
class ManagedAppPermissionsTest : InstrumentationTestCase() {
    private val target get() = instrumentation.targetContext
    private val managed get() = instrumentation.context.packageName

    override fun setUp() {
        super.setUp()
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
    }

    fun testRuntimePermissionAndSpecialAccessChangesRunTheRealCommandsAndAreReadBack() {
        val camera = Manifest.permission.CAMERA
        command("pm revoke $managed $camera")
        command("appops set $managed SYSTEM_ALERT_WINDOW default")
        val uid = target.packageManager.getApplicationInfo(managed, 0).uid
        command("appops set $uid SYSTEM_ALERT_WINDOW default")
        val scripts = mutableListOf<String>()
        val permissions = InstalledAppPermissions(target, RootPermissionWriter { scripts += it; rootScript(it) })
        try {
            val details = checkNotNull(permissions.read(managed))
            assertNull("Controls manages an app installed over the system image", details.refusal)
            val identity = checkNotNull(details.identity)
            val cameraRow = details.permissions.single { it.name == camera }
            assertTrue(cameraRow.changeable)
            assertFalse(cameraRow.granted)
            val overlay = details.specialAccess.single { it.access == AndroidAppAccess.OVERLAY }
            assertTrue("The root read of the overlay app-op must succeed", overlay.changeable)
            assertEquals(false, overlay.allowed)

            assertEquals(PermissionChange.VERIFIED, permissions.set(identity, camera, true))
            assertEquals(PackageManager.PERMISSION_GRANTED, target.packageManager.checkPermission(camera, managed))
            assertEquals(PermissionChange.VERIFIED, permissions.setSpecialAccess(identity, AndroidAppAccess.OVERLAY, true))
            assertTrue(command("appops get $managed SYSTEM_ALERT_WINDOW").contains("allow"))
            assertEquals(true, checkNotNull(permissions.read(managed)).specialAccess.single { it.access == AndroidAppAccess.OVERLAY }.allowed)

            assertEquals(PermissionChange.VERIFIED, permissions.setSpecialAccess(identity, AndroidAppAccess.OVERLAY, false))
            assertEquals(PermissionChange.VERIFIED, permissions.set(identity, camera, false))
            assertEquals(PackageManager.PERMISSION_DENIED, target.packageManager.checkPermission(camera, managed))
            // A change that is already in place sends nothing.
            val sent = scripts.size
            assertEquals(PermissionChange.VERIFIED, permissions.set(identity, camera, false))
            assertTrue("Only the read of special access may run", scripts.drop(sent).none { "pm " in it || "appops set" in it })
        } finally {
            command("pm revoke $managed $camera")
            command("appops set $managed SYSTEM_ALERT_WINDOW default")
            command("appops set $uid SYSTEM_ALERT_WINDOW default")
        }
    }

    fun testCloseIsNotOfferedForAnAppAndroidHoldsStopped() {
        // The test package never runs on its own, so Android keeps it in the stopped state.
        val stopper = InstalledAppStopper(target, RootAppStopper { error("Unexpected root command") })
        assertFalse(stopper.canStop(managed))
    }

    fun testTheForceStopScriptWaitsUntilTheProcessIsGone() {
        val settings = "com.android.settings"
        command("am start -W -a android.settings.SETTINGS")
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (command("pidof $settings").isBlank() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertFalse("The fixture app did not start", command("pidof $settings").isBlank())
        // The data layer alone: Controls' policy never closes a firmware app such as Settings.
        assertEquals(true, RootAppStopper(::rootScript).stop(0, settings))
        assertTrue(command("pidof $settings").isBlank())
        assertEquals("A package without processes is reported as stopped", true,
            RootAppStopper(::rootScript).stop(0, "org.openvoyah.fixture.absent"))
    }

    /** Runs one script as the shell user and fails like the root channel does on a non-zero status. */
    private fun rootScript(script: String): String {
        val directory = checkNotNull(target.getExternalFilesDir(null))
        val inner = File(directory, "ovc-root-script.sh")
        val wrapper = File(directory, "ovc-root-wrapper.sh")
        inner.writeText(script.replace("test \"\$(id -u)\" = 0", "true"))
        wrapper.writeText("sh '${inner.absolutePath}'\necho \"OVC_STATUS=\$?\"\n")
        try {
            val output = command("sh ${wrapper.absolutePath}")
            val status = output.lineSequence().lastOrNull { it.startsWith("OVC_STATUS=") }?.removePrefix("OVC_STATUS=")?.trim()
            if (status != "0") throw IOException("Fixture shell exited with $status")
            return output.lineSequence().filterNot { it.startsWith("OVC_STATUS=") }.joinToString("\n")
        } finally {
            inner.delete()
            wrapper.delete()
        }
    }

    private fun command(line: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(line)).use {
            it.readBytes().decodeToString()
        }
}
