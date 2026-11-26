package org.openvoyah.controls.platform.display

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.PassengerWorkspaceActivity
import org.openvoyah.controls.data.display.PassengerWorkspacePreferences
import org.openvoyah.controls.data.frida.FridaBridge
import java.util.concurrent.atomic.AtomicBoolean

internal object PassengerWorkspaceLauncher {
    const val DISPLAY_ID = 1
    const val TOP_INSET = "org.openvoyah.controls.extra.PASSENGER_TOP_INSET"

    fun supported() = Build.VERSION.SDK_INT == 30 && FridaBridge.windowHookSupported()

    @Suppress("DEPRECATION")
    fun onPassengerDisplay(activity: Activity) = activity.windowManager.defaultDisplay.displayId == DISPLAY_ID

    suspend fun open(activity: ComponentActivity): Boolean {
        if (!supported() || !canSend(activity)) return false
        val intent = Intent(activity, PassengerWorkspaceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (!available(activity, intent)) return false
        val top = topInset(activity)
        if (top < 0 || !canSend(activity) || !available(activity, intent)) return false
        intent.putExtra(TOP_INSET, top)
        return runCatching {
            activity.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(DISPLAY_ID).toBundle())
            true
        }.getOrDefault(false)
    }

    suspend fun topInset(activity: Activity): Int {
        val app = activity.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching { FridaBridge(app).passengerDesktopTopInset() }.getOrDefault(-1)
        }
    }

    private fun available(activity: Activity, intent: Intent): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            val display = activity.getSystemService(DisplayManager::class.java).getDisplay(DISPLAY_ID)
            display != null && display.isValid && display.state == Display.STATE_ON &&
                display.flags and Display.FLAG_PRIVATE == 0 &&
                activity.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS) &&
                activity.getSystemService(ActivityManager::class.java)
                    .isActivityStartAllowedOnDisplay(activity, DISPLAY_ID, intent)
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun canSend(activity: ComponentActivity): Boolean =
        activity.lifecycle.currentState == Lifecycle.State.RESUMED && !activity.isFinishing &&
            activity.windowManager.defaultDisplay.displayId == Display.DEFAULT_DISPLAY
}

private val attemptedStartup = AtomicBoolean()

/** Auto-open belongs to the first foreground Controls session and never runs from a boot or background receiver. */
internal fun ComponentActivity.openPassengerWorkspaceOnStartup() {
    if (!PassengerWorkspaceLauncher.supported() || !attemptedStartup.compareAndSet(false, true)) return
    val app = applicationContext
    lifecycleScope.launch {
        val enabled = withContext(Dispatchers.IO) {
            runCatching { PassengerWorkspacePreferences(app).load().openOnStartup }.getOrDefault(false)
        }
        if (enabled && lifecycle.currentState == Lifecycle.State.RESUMED) PassengerWorkspaceLauncher.open(this@openPassengerWorkspaceOnStartup)
    }
}
