package org.openvoyah.controls.platform.apps

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.core.graphics.createBitmap
import org.openvoyah.controls.InstalledApps

internal data class LocalApp(
    val id: String,
    val packageName: String,
    val label: String,
    val launchEntry: InstalledApps.Entry?,
    val icon: Bitmap?,
    val origin: InstallOrigin,
    val firstInstallTime: Long? = null,
)

internal class AppsCatalog(
    private val context: Context,
    private val permissions: InstalledAppPermissions,
) {
    @Suppress("DEPRECATION")
    fun load(launcherOnly: Boolean = false): List<LocalApp> {
        val packages = context.packageManager
        val settingsPackage = Intent(Settings.ACTION_SETTINGS).resolveActivity(packages)?.packageName
        val excluded = setOfNotNull(context.packageName, "com.android.settings", settingsPackage)
        val launchable = InstalledApps.list(context).filterNot { it.component().packageName in excluded }
        val latestInstallTime = System.currentTimeMillis()
        val installations = mutableMapOf<String, AppInstallationInfo>()
        fun installation(packageName: String) = installations.getOrPut(packageName) {
            val info = permissions.installInfo(packageName)
            info.copy(firstInstallTime = info.firstInstallTime?.takeIf { it <= latestInstallTime })
        }
        val rows = launchable.map { entry ->
            val id = entry.component().packageName
            val installed = installation(id)
            LocalApp(entry.component().flattenToString(), id, entry.label(), entry,
                try { context.appIcon(packages.getActivityIcon(entry.component())) } catch (_: PackageManager.NameNotFoundException) { null },
                installed.origin, installed.firstInstallTime)
        }
        if (launcherOnly) return rows
        val launcherPackages = launchable.map { it.component().packageName }.toSet()
        // Keep Android's package visibility filtering for apps with no launcher entry.
        @SuppressLint("QueryPermissionsNeeded")
        val withoutLauncher = packages.getInstalledApplications(0).mapNotNull { app ->
            val id = app.packageName
            if (id in launcherPackages || id in excluded) return@mapNotNull null
            val installed = installation(id)
            if (installed.origin != InstallOrigin.CONTROLS && installed.origin != InstallOrigin.UNVERIFIED) return@mapNotNull null
            LocalApp(id, id, app.loadLabel(packages).toString(), null, context.appIcon(app.loadIcon(packages)),
                installed.origin, installed.firstInstallTime)
        }
        return (rows + withoutLauncher).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }
}

internal fun Context.appIcon(drawable: Drawable): Bitmap {
    val pixels = (48 * resources.displayMetrics.density).toInt().coerceIn(48, 128)
    return createBitmap(pixels, pixels).also { bitmap ->
        drawable.mutate().apply {
            setBounds(0, 0, pixels, pixels)
            draw(Canvas(bitmap))
        }
    }
}
