package org.openvoyah.controls.platform.apps

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import org.openvoyah.controls.domain.apps.AppControlPolicy
import org.openvoyah.controls.domain.apps.AppControlRefusal
import org.openvoyah.controls.domain.apps.InstalledPackage
import org.openvoyah.controls.domain.apps.PackageRoles

/** Reads what [AppControlPolicy] needs from Android: the package flags and the current car UI roles. */
internal class AppControlFacts(context: Context) {
    private val context = context.applicationContext

    /** Why Controls leaves this app's permissions alone, or null when it manages them. */
    fun refusal(info: PackageInfo): AppControlRefusal? =
        installed(info).let { if (it == null) AppControlRefusal.SYSTEM_IDENTITY else AppControlPolicy.refusal(it, roles()) }

    /** Why Controls does not close this app, or null when it may. */
    fun stopRefusal(info: PackageInfo): AppControlRefusal? =
        installed(info).let { if (it == null) AppControlRefusal.SYSTEM_IDENTITY else AppControlPolicy.stopRefusal(it, roles()) }

    @Suppress("DEPRECATION")
    private fun installed(info: PackageInfo): InstalledPackage? {
        val app = info.applicationInfo ?: return null
        return InstalledPackage(
            packageName = info.packageName,
            uid = app.uid,
            preinstalled = app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
            persistent = app.flags and ApplicationInfo.FLAG_PERSISTENT != 0,
            sharedUser = info.sharedUserId != null,
        )
    }

    /** Each role read is optional: an unreadable role protects nothing extra, and the firmware rule still applies. */
    fun roles(): PackageRoles {
        val packages = context.packageManager
        val home = runCatching {
            packages.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()
        val inputMethod = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')?.takeIf(String::isNotEmpty)
        }.getOrNull()
        val admins = runCatching {
            context.getSystemService(DevicePolicyManager::class.java)?.activeAdmins.orEmpty().mapTo(mutableSetOf()) { it.packageName }
        }.getOrDefault(emptySet())
        val accessibility = runCatching {
            context.getSystemService(AccessibilityManager::class.java)
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty()
                .mapNotNullTo(mutableSetOf()) { it.resolveInfo?.serviceInfo?.packageName }
        }.getOrDefault(emptySet())
        // Android may refuse this setting to an app; then only the accessibility list protects bound services.
        val listeners = runCatching {
            Settings.Secure.getString(context.contentResolver, ENABLED_NOTIFICATION_LISTENERS).orEmpty().split(':')
                .mapNotNullTo(mutableSetOf()) { ComponentName.unflattenFromString(it)?.packageName }
        }.getOrDefault(emptySet())
        return PackageRoles(context.packageName, home, inputMethod, admins, accessibility + listeners)
    }

    private companion object {
        /** `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS`, which the SDK hides. */
        const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
    }
}
