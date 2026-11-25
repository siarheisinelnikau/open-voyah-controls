package org.openvoyah.controls.platform.voice

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.test.InstrumentationTestCase
import org.openvoyah.controls.MainActivity
import org.openvoyah.controls.core.display.DisplayEventBus

/**
 * Voice opens other apps the way the launcher panel does: from the application, on the screen's display. Started by
 * the voice popup itself, an app whose process already ran kept Android's freeform caption bar beside the panel.
 */
class LaunchVoiceActionsTest : InstrumentationTestCase() {
    fun testAppsStartFromTheApplicationOnTheScreensDisplay() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val app = instrumentation.targetContext
        // Restarting needs an exported activity of another package; any settings screen will do.
        val settings = checkNotNull(app.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)).activityInfo
        val installed = ComponentName(settings.packageName, settings.name)
        // Not installed: a start through the screen itself would throw instead of reaching the recorder.
        val missing = ComponentName("org.openvoyah.controls.missing", "org.openvoyah.controls.missing.Main")
        val starts = mutableListOf<Pair<Intent, Bundle?>>()
        val origin = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent, options: Bundle?) { starts += intent to options }
        }
        val selected = mapOf("Music" to installed.flattenToString(), "Radio" to missing.flattenToString())
        val actions = LaunchVoiceActions({ role -> selected[role].orEmpty() }, { false }, DisplayEventBus()) { origin }
        val host = instrumentation.startActivitySync(MainActivity.settingsIntent(app)) as MainActivity
        try {
            var display = -1
            var failure: Throwable? = null
            instrumentation.runOnMainSync {
                try {
                    @Suppress("DEPRECATION")
                    display = host.windowManager.defaultDisplay.displayId
                    assertTrue(actions.execute(host, "Radio").requested())
                    actions.restart(host, "Music")
                } catch (thrown: Throwable) {
                    failure = thrown
                }
            }
            failure?.let { throw it }

            assertEquals(listOf(missing, installed), starts.map { it.first.component })
            val (open, restart) = starts.map { it.first }
            assertEquals(Intent.ACTION_MAIN, open.action)
            assertTrue(open.hasCategory(Intent.CATEGORY_LAUNCHER))
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED, open.flags)
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK, restart.flags)
            val expected = ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()
            for ((_, options) in starts) {
                val actual = checkNotNull(options) { "An app was started without launch options" }
                assertEquals(expected.keySet(), actual.keySet())
                @Suppress("DEPRECATION")
                for (key in expected.keySet()) assertEquals(key, expected.get(key), actual.get(key))
            }
        } finally {
            host.finish()
        }
    }
}
