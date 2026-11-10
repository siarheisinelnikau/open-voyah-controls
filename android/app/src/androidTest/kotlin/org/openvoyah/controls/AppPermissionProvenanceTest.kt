package org.openvoyah.controls

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.test.AndroidTestCase
import kotlinx.coroutines.runBlocking
import org.openvoyah.controls.data.apps.RootPermissionWriter
import org.openvoyah.controls.platform.apps.AndroidAppAccess
import org.openvoyah.controls.platform.apps.AndroidInstallSource
import org.openvoyah.controls.platform.apps.InstallOrigin
import org.openvoyah.controls.platform.apps.InstalledAppIdentity
import org.openvoyah.controls.platform.apps.InstalledAppPermissions
import org.openvoyah.controls.platform.apps.LocalApkInstaller
import org.openvoyah.controls.platform.apps.PermissionChange
import java.util.UUID

class AppPermissionProvenanceTest : AndroidTestCase() {
    fun testAndroidAttributionAndPrivateReceiptAreBothRequired() {
        val controls = "org.openvoyah.controls"
        assertEquals(InstallOrigin.CONTROLS, AndroidInstallSource(controls, controls).origin(controls, true, true))
        assertEquals(InstallOrigin.UNVERIFIED, AndroidInstallSource(controls, controls).origin(controls, true, false))
        assertEquals(InstallOrigin.UNVERIFIED, AndroidInstallSource(controls, null).origin(controls, true, true))
        assertEquals(InstallOrigin.UNVERIFIED, AndroidInstallSource(controls, "com.android.packageinstaller").origin(controls, true, true))
        assertEquals(InstallOrigin.CONTROLS, AndroidInstallSource(controls, null).origin(controls, false, true))
        assertEquals(InstallOrigin.UNVERIFIED, AndroidInstallSource(controls, null).origin(controls, false, false))
        assertEquals(InstallOrigin.ADB, AndroidInstallSource("com.android.shell", null).origin(controls, true, true))
        assertEquals(InstallOrigin.OTHER, AndroidInstallSource("org.example.store", "org.example.store").origin(controls, true, true))
        assertEquals(InstallOrigin.UNKNOWN, AndroidInstallSource(null, null).origin(controls, true, true))
    }

    fun testInstallIdentityReceiptInvalidatesAfterReplacementOrSignerChange() {
        val original = InstalledAppIdentity("org.example.media", 10123, 100L, 200L, 7L, setOf("a", "b"))
        assertEquals(original.receipt(), original.copy(signingCertificates = setOf("b", "a")).receipt())
        listOf(original.copy(uid = 10124), original.copy(installedAt = 201L), original.copy(updatedAt = 201L),
            original.copy(version = 8L), original.copy(signingCertificates = setOf("a"))).forEach {
            assertFalse(original.receipt() == it.receipt())
        }
    }

    fun testOnlyDeclaredSpecialAccessGetsAndroidSettingsHandoff() {
        val info = PackageInfo().apply {
            requestedPermissions = arrayOf(Manifest.permission.SYSTEM_ALERT_WINDOW, Manifest.permission.MANAGE_EXTERNAL_STORAGE)
            services = arrayOf(ServiceInfo().apply { permission = Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE })
        }
        val expected = buildList {
            add(AndroidAppAccess.OVERLAY)
            if (Build.VERSION.SDK_INT >= 30) add(AndroidAppAccess.ALL_FILES)
            add(AndroidAppAccess.NOTIFICATION_LISTENER)
        }
        assertEquals(expected, AndroidAppAccess.declaredBy(info))
        val overlay = AndroidAppAccess.OVERLAY.settings("org.example.media")
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, overlay.action)
        assertEquals(if (Build.VERSION.SDK_INT < 30) "package:org.example.media" else null, overlay.dataString)
    }

    fun testOwnAppPermissionCannotWriteThroughRuntimeAdapter() {
        val adapter = InstalledAppPermissions(context, RootPermissionWriter { error("Unexpected permission write") })
        val details = checkNotNull(adapter.read(context.packageName))
        assertEquals(InstallOrigin.SYSTEM, details.origin)
        assertTrue(details.permissions.any { it.name == Manifest.permission.RECORD_AUDIO && !it.changeable })
        assertEquals(PermissionChange.ANDROID_ONLY,
            adapter.set(checkNotNull(details.identity), Manifest.permission.RECORD_AUDIO, true))
    }

    fun testCompletedSessionCanBeVerifiedOnReopenWithoutTrustingCallbackAlone() = runBlocking {
        val base = context
        val prefix = "test_${UUID.randomUUID()}_"
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        val session = "apk_install_session"
        val receipts = "verified_apk_installs"
        val preferences = isolated.getSharedPreferences(session, Context.MODE_PRIVATE)
        try {
            assertTrue(preferences.edit().putInt("session", 12).putString("token", "test-proof")
                .putLong("bytes", 1).putString("digest", "0".repeat(64)).commit())
            val result = Intent("org.openvoyah.controls.action.APK_INSTALL_RESULT")
                .setData(Uri.fromParts("ovc-apk-install", "test-proof", null))
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
                .putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, isolated.packageName)
            val first = LocalApkInstaller(isolated)
            assertFalse(first.handleResult(result.putExtra(PackageInstaller.EXTRA_SESSION_ID, 13)))
            assertFalse(preferences.contains("completed_package"))
            assertTrue(first.handleResult(result.putExtra(PackageInstaller.EXTRA_SESSION_ID, 12)))
            assertEquals(isolated.packageName, preferences.getString("completed_package", null))
            assertEquals(R.string.apps_install_verifying, first.state.value.message)

            val reopened = LocalApkInstaller(isolated)
            reopened.recover()
            assertEquals(R.string.apps_install_unverified, reopened.state.value.message)
            assertFalse(preferences.contains("completed_package"))
            assertFalse(isolated.getSharedPreferences(receipts, Context.MODE_PRIVATE).contains(isolated.packageName))
        } finally {
            base.deleteSharedPreferences(prefix + session)
            base.deleteSharedPreferences(prefix + receipts)
        }
    }
}
