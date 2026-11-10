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
import android.view.Display
import org.openvoyah.controls.InstalledApps
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.frida.FridaBridge

internal class AppsPlatform(private val activity: Activity) {
    data class DisplayChoice(val id: Int, val label: String, val canOpenAlongside: Boolean)

    val windowsSupported: Boolean
        get() = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)

    private val displayManager get() = activity.getSystemService(DisplayManager::class.java)

    @Suppress("DEPRECATION")
    private fun currentDisplayId(): Int = activity.windowManager.defaultDisplay.displayId

    fun displays(app: InstalledApps.Entry?): List<DisplayChoice> {
        val intent = app?.intent() ?: return emptyList()
        return displayManager.displays.filter { canLaunch(it, intent) }.sortedBy { it.displayId }.map {
            val label = if (it.displayId == Display.DEFAULT_DISPLAY) UiText.get(R.string.app_display_defaults_central_screen)
                else "${it.name} · ${it.displayId}"
            DisplayChoice(it.displayId, label, canOpenAlongside(it.displayId))
        }
    }

    @Suppress("DEPRECATION")
    fun launch(app: InstalledApps.Entry, displayId: Int? = null, window: Boolean = false, adjacent: Boolean = false) {
        val intent = app.intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (displayId == null) {
            activity.startActivity(intent)
            return
        }
        val display = displayManager.getDisplay(displayId)
        check(display != null && canLaunch(display, intent))
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        if (adjacent) {
            check(!window && canOpenAlongside(displayId))
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        } else if (window && windowsSupported) {
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

    private fun canLaunch(display: Display, intent: Intent): Boolean {
        if (!display.isValid || display.state == Display.STATE_OFF || display.flags and Display.FLAG_PRIVATE != 0) return false
        if (display.displayId != Display.DEFAULT_DISPLAY &&
            !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)) return false
        return Build.VERSION.SDK_INT < 29 || runCatching {
            activity.getSystemService(ActivityManager::class.java)
                .isActivityStartAllowedOnDisplay(activity, display.displayId, intent)
        }.getOrDefault(false)
    }

    private fun canOpenAlongside(displayId: Int): Boolean =
        displayId == currentDisplayId() && activity.isInMultiWindowMode && !activity.isInPictureInPictureMode

    fun canInstall(): Boolean = activity.packageManager.canRequestPackageInstalls()

    fun allowInstalls(): Intent? = trustedSettings(packageSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, activity.packageName))

    fun pickApk(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).setType(APK_TYPE)
        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_LOCAL_ONLY, true)

    fun appDetails(packageName: String): Intent? =
        trustedSettings(packageSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageName))

    fun specialAccess(packageName: String, access: AndroidAppAccess): Intent? = trustedSettings(access.settings(packageName))

    private fun trustedSettings(intent: Intent): Intent? = try {
        val target = activity.packageManager.queryIntentActivities(intent,
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY)
            .firstOrNull { it.activityInfo?.let { handler -> handler.exported && handler.enabled } == true }
            ?.activityInfo
        target?.let { intent.setClassName(it.packageName, it.name) }
    } catch (_: RuntimeException) {
        null
    }

    private fun packageSettings(action: String, packageName: String): Intent =
        Intent(action, Uri.fromParts("package", packageName, null))

    private companion object {
        const val APK_TYPE = "application/vnd.android.package-archive"
    }
}
