package org.openvoyah.controls

import android.content.Intent
import android.content.pm.ShortcutManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.test.InstrumentationTestCase
import androidx.lifecycle.Lifecycle
import org.json.JSONObject
import org.openvoyah.controls.data.routines.RoutinePreferences
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.domain.settings.launcherMediaVolumeTarget

class LauncherMediaVolumeEntryTest : InstrumentationTestCase() {
    fun testActualLauncherEntryAppliesTheLimitAndOrdinaryRoutesDoNot() {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val app = instrumentation.targetContext
        val manager = checkNotNull(app.getSystemService(AudioManager::class.java))
        val media = AudioManager.STREAM_MUSIC
        check(!manager.isVolumeFixed && !manager.isStreamMute(media))
        val originalVolume = manager.getStreamVolume(media)
        val maximum = manager.getStreamMaxVolume(media)
        val target = checkNotNull(launcherMediaVolumeTarget(manager.getStreamMinVolume(media), maximum, maximum, 40))
        val appearance = AppearancePreferences(app)
        val originalAppearance = appearance.exportSettings()
        val routines = RoutinePreferences(app)
        val originalRoutines = routines.exportSettings()
        val updates = app.getSharedPreferences("updates", 0)
        val originalUpdateCheck = updates.all["check_on_startup"] as? Boolean
        var activity: MainActivity? = null
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)

        try {
            check(updates.edit().putBoolean("check_on_startup", false).commit())
            routines.restoreSettings(JSONObject().put("startupEnabled", false))
            appearance.setLaunchMediaVolumePercent(40)
            appearance.setLimitMediaVolumeOnLaunch(true)
            manager.setStreamVolume(media, maximum, 0)
            await("fixture media level") { manager.getStreamVolume(media) == maximum }

            val host = instrumentation.startActivitySync(MainActivity.settingsIntent(app)) as MainActivity
            activity = host
            await("explicit settings route is foreground") {
                var focused = false
                instrumentation.runOnMainSync {
                    focused = host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && host.hasWindowFocus()
                }
                focused
            }
            instrumentation.waitForIdleSync()
            assertEquals(maximum, manager.getStreamVolume(media))

            instrumentation.runOnMainSync {
                val shortcuts = checkNotNull(app.getSystemService(ShortcutManager::class.java)).manifestShortcuts
                val shortcut = checkNotNull(shortcuts.single { it.id == "open_controls" }.intent)
                host.startActivity(shortcut.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            await("launch from the Open Controls shortcut limits media volume") { manager.getStreamVolume(media) == target }
            // Android adds CLEAR_TASK to a manifest shortcut; it can replace the first host.
            val currentHost = monitor.lastActivity as? MainActivity ?: host
            activity = currentHost

            manager.setStreamVolume(media, maximum, 0)
            await("manually raised volume") { manager.getStreamVolume(media) == maximum }
            instrumentation.runOnMainSync { currentHost.startActivity(MainActivity.settingsIntent(app)) }
            instrumentation.waitForIdleSync()
            assertEquals(maximum, manager.getStreamVolume(media))
        } finally {
            try {
                (monitor.lastActivity as? MainActivity ?: activity)?.let { screen ->
                    instrumentation.runOnMainSync { screen.finish() }
                    await("launcher test host destroyed before another singleTop launch") { screen.isDestroyed }
                }
            } finally {
                instrumentation.removeMonitor(monitor)
                manager.setStreamVolume(media, originalVolume, 0)
                appearance.restoreSettings(originalAppearance)
                routines.restoreSettings(originalRoutines)
                check(updates.edit().apply {
                    if (originalUpdateCheck == null) remove("check_on_startup")
                    else putBoolean("check_on_startup", originalUpdateCheck)
                }.commit())
            }
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(30)
        }
        fail("Timed out waiting for " + description)
    }
}
