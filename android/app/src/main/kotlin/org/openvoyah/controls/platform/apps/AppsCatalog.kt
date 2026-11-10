package org.openvoyah.controls.platform.apps

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import org.openvoyah.controls.InstalledApps

internal data class LocalApp(
    val id: String,
    val packageName: String,
    val label: String,
    val launchEntry: InstalledApps.Entry?,
    val icon: Bitmap?,
    val managed: Boolean,
)

internal class AppsCatalog(
    private val context: Context,
    private val permissions: InstalledAppPermissions,
) {
    @Suppress("DEPRECATION")
    fun load(): List<LocalApp> {
        val packages = context.packageManager
        val launchable = InstalledApps.list(context)
        val launcherPackages = launchable.map { it.component().packageName }.toSet()
        val managed = mutableMapOf<String, Boolean>()
        val rows = launchable.map { entry ->
            val id = entry.component().packageName
            LocalApp(entry.component().flattenToString(), id, entry.label(), entry,
                try { icon(packages.getActivityIcon(entry.component())) } catch (_: PackageManager.NameNotFoundException) { null },
                managed.getOrPut(id) { permissions.managed(id) })
        }
        // Keep Android's package visibility filtering for apps with no launcher entry.
        @SuppressLint("QueryPermissionsNeeded")
        val withoutLauncher = packages.getInstalledApplications(0).mapNotNull { app ->
            val id = app.packageName
            if (id in launcherPackages || !permissions.managed(id)) return@mapNotNull null
            LocalApp(id, id, app.loadLabel(packages).toString(), null, icon(app.loadIcon(packages)), managed = true)
        }
        return (rows + withoutLauncher).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun icon(drawable: Drawable): Bitmap {
        val pixels = (48 * context.resources.displayMetrics.density).toInt().coerceIn(48, 128)
        return createBitmap(pixels, pixels).also { bitmap ->
            drawable.mutate().apply {
                setBounds(0, 0, pixels, pixels)
                draw(Canvas(bitmap))
            }
        }
    }
}
