package org.openvoyah.controls.platform.apps

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.annotation.MainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openvoyah.controls.InstalledApps
import org.openvoyah.controls.diagnostics.ControlsLog

/** Resolves the same launcher entry type as the app grid, scoped to the displayed media session's package. */
@MainThread
internal suspend fun openDesktopMediaPlayer(activity: Activity, packageName: String, stillActive: () -> Boolean): Boolean {
    if (packageName.isEmpty()) return false
    val entry = withContext(Dispatchers.IO) {
        try {
            InstalledApps.list(activity.applicationContext, packageName).firstOrNull { it.component().packageName == packageName }
        } catch (_: RuntimeException) {
            ControlsLog.info("desktop_media_launch", "outcome", "launcher_lookup_failed")
            null
        }
    } ?: return false
    if (!stillActive()) return false
    return try {
        AppsPlatform(activity).launch(entry)
        true
    } catch (_: ActivityNotFoundException) {
        ControlsLog.info("desktop_media_launch", "outcome", "activity_unavailable")
        false
    } catch (_: SecurityException) {
        ControlsLog.info("desktop_media_launch", "outcome", "launch_denied")
        false
    }
}
