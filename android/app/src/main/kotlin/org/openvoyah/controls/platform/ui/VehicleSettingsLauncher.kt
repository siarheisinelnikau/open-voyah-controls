package org.openvoyah.controls.platform.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

internal enum class VehicleSettingsDestination(val packageName: String) {
    VEHICLE("com.qinggan.app.vehicle"), INFOTAINMENT("com.qinggan.app.setting"),
}

/** Uses only the launcher entry offered by the built-in OEM app. */
internal class VehicleSettingsLauncher(private val context: Context) {
    fun intent(destination: VehicleSettingsDestination): Intent? {
        val packages = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(destination.packageName)
        val matches = packages.queryIntentActivities(query, 0).mapNotNull { it.activityInfo }.filter { activity ->
            val app = activity.applicationInfo
            activity.packageName == destination.packageName && activity.exported && activity.enabled && app.enabled &&
                app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 &&
                (activity.permission.isNullOrEmpty() ||
                    packages.checkPermission(activity.permission, context.packageName) == PackageManager.PERMISSION_GRANTED)
        }
        val target = matches.distinctBy { it.name }.singleOrNull() ?: return null
        return query.setComponent(ComponentName(target.packageName, target.name))
    }
}
