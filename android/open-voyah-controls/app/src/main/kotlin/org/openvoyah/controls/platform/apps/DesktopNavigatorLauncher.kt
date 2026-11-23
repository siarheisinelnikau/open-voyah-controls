package org.openvoyah.controls.platform.apps

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import androidx.annotation.MainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openvoyah.controls.InstalledApps
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.diagnostics.ControlsLog

@MainThread
internal suspend fun openDesktopNavigator(activity: Activity, stillActive: () -> Boolean): Boolean {
    val app = activity.applicationContext
    val entry = withContext(Dispatchers.IO) {
        try {
            val target = ComponentName.unflattenFromString(VoiceSettings(app).app("Navigator"))
            target?.takeUnless { it.packageName == app.packageName }?.let { component ->
                InstalledApps.list(app, component.packageName).firstOrNull { it.component() == component }
            }
        } catch (_: RuntimeException) {
            ControlsLog.info("desktop_navigator_launch", "outcome", "launcher_lookup_failed")
            null
        }
    } ?: return false
    if (!stillActive()) return false
    return try {
        AppsPlatform(activity).launch(entry)
        true
    } catch (_: ActivityNotFoundException) {
        ControlsLog.info("desktop_navigator_launch", "outcome", "activity_unavailable")
        false
    } catch (_: SecurityException) {
        ControlsLog.info("desktop_navigator_launch", "outcome", "launch_denied")
        false
    }
}
