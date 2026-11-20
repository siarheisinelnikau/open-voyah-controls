package org.openvoyah.controls

import android.content.Intent
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
                host.startActivity(Intent(app, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            await("launch with the normal launcher intent limits media volume") { manager.getStreamVolume(media) == target }

            manager.setStreamVolume(media, maximum, 0)
            await("manually raised volume") { manager.getStreamVolume(media) == maximum }
            instrumentation.runOnMainSync { host.startActivity(MainActivity.settingsIntent(app)) }
            instrumentation.waitForIdleSync()
            assertEquals(maximum, manager.getStreamVolume(media))
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            manager.setStreamVolume(media, originalVolume, 0)
            appearance.restoreSettings(originalAppearance)
            routines.restoreSettings(originalRoutines)
            check(updates.edit().apply {
                if (originalUpdateCheck == null) remove("check_on_startup")
                else putBoolean("check_on_startup", originalUpdateCheck)
            }.commit())
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
