package org.openvoyah.controls

import android.content.Context
import android.test.AndroidTestCase
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.domain.settings.AppAccent
import org.openvoyah.controls.domain.settings.AppAppearance
import org.openvoyah.controls.domain.settings.AppCorners
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.domain.settings.AppSpacing
import org.openvoyah.controls.domain.settings.AppTextSize
import org.openvoyah.controls.domain.settings.LauncherEntry
import org.openvoyah.controls.domain.settings.RouteTransitionSpeed
import org.openvoyah.controls.domain.settings.WallpaperDimming

class AppearancePreferencesTest : AndroidTestCase() {
    fun testDefaultsPersistAndLiveRestoreMergesLegacyFields() = runBlocking {
        val app = context.applicationContext
        val store = app.getSharedPreferences(AppearancePreferences.STORE, Context.MODE_PRIVATE)
        val keys = listOf("animate_routes", "high_contrast", "launcher_entry", "text_size", "spacing", "corners",
            "palette", "transition_speed", "accent", "wallpaper_dimming", "font_family", "background_treatment", "bold_text",
            "limit_media_volume_on_launch", "launch_media_volume_percent")
        val original = store.all
        val reset = store.edit()
        keys.forEach { reset.remove(it) }
        assertTrue(reset.commit())
        val reader = AppearancePreferences(app)
        val writer = AppearancePreferences(app)
        val changes = Channel<AppAppearance>(Channel.UNLIMITED)
        val observer = launch { reader.observeSettings().collect { changes.send(it) } }
        try {
            val defaults = AppAppearance()
            assertEquals(defaults, withTimeout(5_000) { changes.receive() })
            val updated = defaults.copy(animateRoutes = false, highContrast = true, launcherEntry = LauncherEntry.VEHICLE)
            writer.setAnimateRoutes(false)
            assertEquals(defaults.copy(animateRoutes = false), withTimeout(5_000) { changes.receive() })
            writer.setHighContrast(true)
            assertEquals(updated.copy(launcherEntry = LauncherEntry.APP_GRID), withTimeout(5_000) { changes.receive() })
            writer.setLauncherEntry(LauncherEntry.VEHICLE)
            assertEquals(updated, withTimeout(5_000) { changes.receive() })
            val larger = updated.copy(textSize = AppTextSize.EXTRA_LARGE)
            writer.setTextSize(larger.textSize)
            assertEquals(larger, withTimeout(5_000) { changes.receive() })
            val compact = larger.copy(spacing = AppSpacing.COMPACT)
            writer.setSpacing(compact.spacing)
            assertEquals(compact, withTimeout(5_000) { changes.receive() })
            val rounded = compact.copy(corners = AppCorners.ROUNDED)
            writer.setCorners(rounded.corners)
            assertEquals(rounded, withTimeout(5_000) { changes.receive() })
            val day = rounded.copy(palette = AppPalette.DAY)
            writer.setPalette(day.palette)
            assertEquals(day, withTimeout(5_000) { changes.receive() })
            val smooth = day.copy(transitionSpeed = RouteTransitionSpeed.SMOOTH)
            writer.setTransitionSpeed(smooth.transitionSpeed)
            assertEquals(smooth, withTimeout(5_000) { changes.receive() })
            val teal = smooth.copy(accent = AppAccent.TEAL)
            writer.setAccent(teal.accent)
            assertEquals(teal, withTimeout(5_000) { changes.receive() })
            val personalized = teal.copy(wallpaperDimming = WallpaperDimming.STRONG)
            writer.setWallpaperDimming(personalized.wallpaperDimming)
            assertEquals(personalized, withTimeout(5_000) { changes.receive() })
            assertEquals(personalized, AppearancePreferences(app).load())
            val exported = reader.exportSettings()
            assertFalse(exported.getBoolean("animateRoutes"))
            assertTrue(exported.getBoolean("highContrast"))
            assertEquals("vehicle", exported.getString("launcherEntry"))
            assertEquals("extra_large", exported.getString("textSize"))
            assertEquals("compact", exported.getString("spacing"))
            assertEquals("rounded", exported.getString("corners"))
            assertEquals("day", exported.getString("palette"))
            assertEquals("smooth", exported.getString("transitionSpeed"))
            assertEquals("teal", exported.getString("accent"))
            assertEquals("strong", exported.getString("wallpaperDimming"))

            val partial = AppearancePreferences.validateSettings(JSONObject().put("animateRoutes", true))
            assertEquals(1, partial.length())
            writer.restoreSettings(partial)
            assertEquals(personalized.copy(animateRoutes = true), withTimeout(5_000) { changes.receive() })

            val spacingOnly = AppearancePreferences.validateSettings(JSONObject().put("spacing", "spacious"))
            assertEquals(1, spacingOnly.length())
            writer.restoreSettings(spacingOnly)
            val spacious = personalized.copy(animateRoutes = true, spacing = AppSpacing.SPACIOUS)
            assertEquals(spacious, withTimeout(5_000) { changes.receive() })

            val speedOnly = AppearancePreferences.validateSettings(JSONObject().put("transitionSpeed", "short"))
            assertEquals(1, speedOnly.length())
            writer.restoreSettings(speedOnly)
            val short = spacious.copy(transitionSpeed = RouteTransitionSpeed.SHORT)
            assertEquals(short, withTimeout(5_000) { changes.receive() })

            val dimmingOnly = AppearancePreferences.validateSettings(JSONObject().put("wallpaperDimming", "light"))
            assertFalse(dimmingOnly.has("accent"))
            writer.restoreSettings(dimmingOnly)
            assertEquals(short.copy(wallpaperDimming = WallpaperDimming.LIGHT), withTimeout(5_000) { changes.receive() })
            writer.restoreSettings(AppearancePreferences.validateSettings(exported))
            assertEquals(personalized, withTimeout(5_000) { changes.receive() })
        } finally {
            observer.cancelAndJoin()
            val cleanup = store.edit()
            keys.forEach { key ->
                when (val value = original[key]) {
                    is Boolean -> cleanup.putBoolean(key, value)
                    is Int -> cleanup.putInt(key, value)
                    is String -> cleanup.putString(key, value)
                    else -> cleanup.remove(key)
                }
            }
            assertTrue(cleanup.commit())
        }
    }

    fun testLauncherMediaLimitArchivesIndependentlyAndMergesOlderSettings() {
        val app = context.applicationContext
        val store = app.getSharedPreferences(AppearancePreferences.STORE, Context.MODE_PRIVATE)
        val enabledKey = "limit_media_volume_on_launch"
        val percentKey = "launch_media_volume_percent"
        val original = store.all
        try {
            assertTrue(store.edit().remove(enabledKey).remove(percentKey).commit())
            val preferences = AppearancePreferences(app)
            assertFalse(preferences.load().limitMediaVolumeOnLaunch)
            assertEquals(40, preferences.load().launchMediaVolumePercent)

            preferences.setLaunchMediaVolumePercent(70)
            val archive = AppearancePreferences.validateSettings(preferences.exportSettings())
            assertFalse(archive.getBoolean("limitMediaVolumeOnLaunch"))
            assertEquals(70, archive.getInt("launchMediaVolumePercent"))

            preferences.setLimitMediaVolumeOnLaunch(true)
            preferences.restoreSettings(AppearancePreferences.validateSettings(JSONObject()))
            assertTrue(preferences.load().limitMediaVolumeOnLaunch)
            assertEquals(70, preferences.load().launchMediaVolumePercent)

            preferences.setLaunchMediaVolumePercent(30)
            preferences.restoreSettings(AppearancePreferences.validateSettings(JSONObject().put("limitMediaVolumeOnLaunch", false)))
            assertEquals(30, AppearancePreferences(app).load().launchMediaVolumePercent)
            preferences.restoreSettings(archive)
            assertFalse(AppearancePreferences(app).load().limitMediaVolumeOnLaunch)
            assertEquals(70, AppearancePreferences(app).load().launchMediaVolumePercent)
        } finally {
            val cleanup = store.edit().remove(enabledKey).remove(percentKey)
            (original[enabledKey] as? Boolean)?.let { cleanup.putBoolean(enabledKey, it) }
            (original[percentKey] as? Int)?.let { cleanup.putInt(percentKey, it) }
            assertTrue(cleanup.commit())
        }
    }

    fun testArchiveRejectsUnknownAppearanceChoices() {
        val invalid = listOf("textSize" to "huge", "spacing" to 2, "corners" to JSONObject.NULL, "palette" to "system",
            "transitionSpeed" to 0.5, "accent" to "#ff00ff", "accent" to JSONObject.NULL,
            "wallpaperDimming" to "transparent", "wallpaperDimming" to 0.3,
            "limitMediaVolumeOnLaunch" to "true", "launchMediaVolumePercent" to "40",
            "launchMediaVolumePercent" to 45, "launchMediaVolumePercent" to 90, "launchMediaVolumePercent" to JSONObject.NULL)
        for ((key, value) in invalid) {
            try {
                AppearancePreferences.validateSettings(JSONObject().put(key, value))
                fail("Invalid $key was accepted")
            } catch (_: IllegalArgumentException) {
                // Malformed imported IDs cannot reach the renderer.
            }
        }
    }
}
