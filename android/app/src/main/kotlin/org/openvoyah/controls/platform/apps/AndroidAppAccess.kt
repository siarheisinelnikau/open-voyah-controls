package org.openvoyah.controls.platform.apps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings

internal enum class AndroidAppAccess {
    OVERLAY, WRITE_SETTINGS, UNKNOWN_APPS, ALL_FILES, USAGE, NOTIFICATION_LISTENER, ACCESSIBILITY;

    fun settings(packageName: String): Intent = when (this) {
        OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            if (Build.VERSION.SDK_INT < 30) data = packageUri(packageName)
        }
        WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri(packageName))
        UNKNOWN_APPS -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri(packageName))
        ALL_FILES -> Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        USAGE -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        NOTIFICATION_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    private fun packageUri(packageName: String): Uri = Uri.fromParts("package", packageName, null)

    companion object {
        fun declaredBy(info: PackageInfo): List<AndroidAppAccess> {
            val requested = info.requestedPermissions.orEmpty()
            val services = info.services.orEmpty()
            return buildList {
                if (Manifest.permission.SYSTEM_ALERT_WINDOW in requested) add(OVERLAY)
                if (Manifest.permission.WRITE_SETTINGS in requested) add(WRITE_SETTINGS)
                if (Manifest.permission.REQUEST_INSTALL_PACKAGES in requested) add(UNKNOWN_APPS)
                if (Build.VERSION.SDK_INT >= 30 && Manifest.permission.MANAGE_EXTERNAL_STORAGE in requested) add(ALL_FILES)
                if (Manifest.permission.PACKAGE_USAGE_STATS in requested) add(USAGE)
                if (services.any { it.permission == Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE }) add(NOTIFICATION_LISTENER)
                if (services.any { it.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE }) add(ACCESSIBILITY)
            }
        }
    }
}
