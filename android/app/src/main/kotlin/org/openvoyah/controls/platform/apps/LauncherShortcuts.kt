package org.openvoyah.controls.platform.apps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import org.openvoyah.controls.InstalledApps

internal data class LauncherShortcut(val entry: InstalledApps.Entry, val icon: Bitmap?)

internal class LauncherShortcuts(private val context: Context) {
    fun resolve(components: Set<String>): Map<String, LauncherShortcut> = buildMap {
        for (packageName in components.map { it.substringBefore('/') }.distinct()) {
            for (entry in entries(packageName)) {
                val id = entry.component().flattenToString()
                if (id !in components) continue
                val icon = try {
                    context.appIcon(context.packageManager.getActivityIcon(entry.component()))
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
                put(id, LauncherShortcut(entry, icon))
            }
        }
    }

    fun find(component: String): InstalledApps.Entry? = entries(component.substringBefore('/'))
        .firstOrNull { it.component().flattenToString() == component }

    private fun entries(packageName: String): List<InstalledApps.Entry> = try {
        InstalledApps.list(context, packageName)
    } catch (_: RuntimeException) {
        emptyList()
    }
}
