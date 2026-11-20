package org.openvoyah.controls

import android.test.AndroidTestCase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.domain.apps.AppGridDesktop

class AppGridDesktopPreferencesTest : AndroidTestCase() {
    fun testFavoritesAndDefaultAreDurableAndSeparateFromMainPins() {
        val preferences = AppGridPreferences(context)
        val before = preferences.exportSettings()
        try {
            preferences.restoreSettings(archive().put("desktops", desktops()))
            runBlocking {
                preferences.changePin(maps, true)
                preferences.changePin(player, true, AppGridDesktop.FAVORITES_ONE)
                preferences.changePin(maps, true, AppGridDesktop.FAVORITES_ONE)
                preferences.changePin(maps, true, AppGridDesktop.FAVORITES_TWO)
                preferences.setDefaultDesktop(AppGridDesktop.FAVORITES_TWO)
                preferences.setDesktopsEnabled(false)
            }
            val restored = AppGridPreferences(context).exportSettings()
            assertEquals(JSONArray(listOf(maps)).toString(), restored.getJSONArray("pinnedApps").toString())
            val groups = restored.getJSONObject("desktops")
            assertFalse(groups.getBoolean("enabled"))
            assertEquals("favorites2", groups.getString("defaultDesktop"))
            assertEquals(JSONArray(listOf(player, maps)).toString(), groups.getJSONArray("favorites1").toString())
            assertEquals(JSONArray(listOf(maps)).toString(), groups.getJSONArray("favorites2").toString())
        } finally {
            preferences.restoreSettings(before)
        }
    }

    fun testLegacyGridArchivePreservesLocalDesktops() {
        val preferences = AppGridPreferences(context)
        val before = preferences.exportSettings()
        val savedDesktops = desktops().put("defaultDesktop", "favorites2").put("favorites2", JSONArray(listOf(player)))
        try {
            preferences.restoreSettings(archive().put("desktops", savedDesktops))
            val legacy = AppGridPreferences.validateSettings(archive().put("pinnedApps", JSONArray(listOf(maps))))
            assertFalse(legacy.has("desktops"))
            preferences.restoreSettings(legacy)

            val restored = preferences.exportSettings()
            assertEquals(maps, restored.getJSONArray("pinnedApps").getString(0))
            assertEquals(savedDesktops.toString(), restored.getJSONObject("desktops").toString())
        } finally {
            preferences.restoreSettings(before)
        }
    }

    fun testArchivesRejectMalformedDesktopFields() {
        val invalid = listOf(
            desktops().put("enabled", "true"),
            desktops().put("defaultDesktop", "favorites3"),
            desktops().put("favorites1", JSONArray(listOf(maps, maps))),
            desktops().put("favorites2", JSONArray(listOf(1))),
        )
        invalid.forEach { value ->
            assertTrue(runCatching { AppGridPreferences.validateSettings(archive().put("desktops", value)) }.isFailure)
        }
    }

    private fun archive() = JSONObject().put("pinnedApps", JSONArray())

    private fun desktops() = JSONObject().put("enabled", true).put("defaultDesktop", "main")
        .put("favorites1", JSONArray()).put("favorites2", JSONArray())

    private companion object {
        const val maps = "com.example.maps/com.example.maps.Main"
        const val player = "com.example.player/com.example.player.Main"
    }
}
