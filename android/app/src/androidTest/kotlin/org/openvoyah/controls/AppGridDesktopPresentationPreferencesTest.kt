package org.openvoyah.controls

import android.content.Context
import android.test.AndroidTestCase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.domain.apps.DesktopCardPlacement
import org.openvoyah.controls.domain.apps.FavoritesBackBehavior

class AppGridDesktopPresentationPreferencesTest : AndroidTestCase() {
    fun testDesktopPresentationSettingsArePortableAndIndependentOfOlderArchives() {
        val preferences = AppGridPreferences(context)
        val raw = context.getSharedPreferences(AppGridPreferences.STORE, Context.MODE_PRIVATE)
        val before = preferences.exportSettings()
        val keys = listOf("desktop_card_outlines", "smooth_desktop_paging", "desktop_card_placement", "favorites_back_behavior",
            "show_search_field")
        val original = raw.all
        try {
            assertTrue(raw.edit().apply { keys.forEach { remove(it) } }.commit())
            val defaults = preferences.exportSettings()
            assertTrue(defaults.getBoolean("showDesktopCardOutlines"))
            assertFalse(defaults.getBoolean("smoothDesktopPaging"))
            assertEquals("beforeApps", defaults.getString("desktopCardPlacement"))
            assertEquals("system", defaults.getString("favoritesBackBehavior"))
            assertFalse(defaults.getBoolean("showSearchField"))

            runBlocking {
                preferences.setDesktopCardOutlines(false)
                preferences.setSmoothDesktopPaging(true)
                preferences.setDesktopCardPlacement(DesktopCardPlacement.AFTER_PINNED_APPS)
                preferences.setFavoritesBackBehavior(FavoritesBackBehavior.MAIN_DESKTOP)
                preferences.setShowSearchField(true)
            }
            val current = AppGridPreferences(context).exportSettings()
            assertFalse(current.getBoolean("showDesktopCardOutlines"))
            assertTrue(current.getBoolean("smoothDesktopPaging"))
            assertEquals("afterPinnedApps", current.getString("desktopCardPlacement"))
            assertEquals("mainDesktop", current.getString("favoritesBackBehavior"))
            assertTrue(current.getBoolean("showSearchField"))

            val legacy = AppGridPreferences.validateSettings(JSONObject().put("pinnedApps", current.getJSONArray("pinnedApps")))
            assertEquals(1, legacy.length())
            assertFalse(AppGridPreferences.validateLayout(legacy).has("showSearchField"))
            preferences.restoreSettings(legacy)
            assertEquals(current.toString(), preferences.exportSettings().toString())

            preferences.restoreSettings(legacy.put("smoothDesktopPaging", false))
            assertFalse(preferences.exportSettings().getBoolean("smoothDesktopPaging"))
            assertFalse(preferences.exportSettings().getBoolean("showDesktopCardOutlines"))
            assertEquals("afterPinnedApps", preferences.exportSettings().getString("desktopCardPlacement"))
            assertEquals("mainDesktop", preferences.exportSettings().getString("favoritesBackBehavior"))
            assertTrue(preferences.exportSettings().getBoolean("showSearchField"))
            preferences.restoreSettings(AppGridPreferences.validateSettings(current))
            assertEquals(current.toString(), preferences.exportSettings().toString())
        } finally {
            preferences.restoreSettings(before)
            val cleanup = raw.edit()
            for (key in keys) {
                when (val value = original[key]) {
                    is Boolean -> cleanup.putBoolean(key, value)
                    is String -> cleanup.putString(key, value)
                    else -> cleanup.remove(key)
                }
            }
            assertTrue(cleanup.commit())
        }
    }

    fun testDesktopPresentationSettingsRejectWrongTypesAndUnknownPlacement() {
        val malformed = listOf(
            "showDesktopCardOutlines" to "false",
            "smoothDesktopPaging" to 1,
            "desktopCardPlacement" to "random",
            "favoritesBackBehavior" to "unavailable",
            "favoritesBackBehavior" to true,
            "showSearchField" to "true",
            "showSearchField" to JSONObject.NULL,
        )
        for ((field, value) in malformed) {
            assertTrue(runCatching {
                AppGridPreferences.validateSettings(JSONObject().put("pinnedApps", JSONArray()).put(field, value))
            }.isFailure)
        }
    }
}
