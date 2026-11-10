package org.openvoyah.controls.platform.apps

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import org.openvoyah.controls.InstalledApps
import org.openvoyah.controls.data.frida.FridaBridge

internal class AppsPlatform(private val activity: Activity) {
    data class DisplayChoice(val id: Int, val label: String)

    val windowsSupported: Boolean
        get() = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)

    fun displays(): List<DisplayChoice> = activity.getSystemService(DisplayManager::class.java).displays.map {
        DisplayChoice(it.displayId, "${it.name} · ${it.displayId}")
    }

    @Suppress("DEPRECATION")
    fun launch(app: InstalledApps.Entry, displayId: Int? = null, window: Boolean = false) {
        val intent = app.intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (displayId == null) {
            activity.startActivity(intent)
            return
        }
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        check(display != null && display.isValid)
        if (Build.VERSION.SDK_INT >= 29) {
            check(activity.getSystemService(ActivityManager::class.java)
                .isActivityStartAllowedOnDisplay(activity, displayId, intent))
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        if (window && windowsSupported) {
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            val marginX = metrics.widthPixels / 8
            val marginY = metrics.heightPixels / 8
            options.launchBounds = Rect(marginX, marginY, metrics.widthPixels - marginX, metrics.heightPixels - marginY)
            if (FridaBridge.windowHookEnabled(activity)) {
                intent.putExtra("org.openvoyah.controls.extra.FLOATING_WINDOW", true)
            }
        }
        activity.startActivity(intent, options.toBundle())
    }

    fun canInstall(): Boolean = activity.packageManager.canRequestPackageInstalls()

    fun allowInstalls(): Intent = packageSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, activity.packageName)

    fun pickApk(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).setType(APK_TYPE)
        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_LOCAL_ONLY, true)

    fun installer(uri: Uri?): Intent? {
        if (uri?.scheme != "content") return null
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, activity.packageName)
        val installer = activity.packageManager.resolveActivity(intent,
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY)?.activityInfo ?: return null
        return intent.setClassName(installer.packageName, installer.name)
    }

    fun appDetails(packageName: String): Intent = packageSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageName)

    private fun packageSettings(action: String, packageName: String): Intent =
        Intent(action, Uri.fromParts("package", packageName, null))

    private companion object {
        const val APK_TYPE = "application/vnd.android.package-archive"
    }
}
