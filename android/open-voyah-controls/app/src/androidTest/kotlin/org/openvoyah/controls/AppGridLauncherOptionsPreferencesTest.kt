package org.openvoyah.controls

import android.test.AndroidTestCase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.domain.apps.AppGridColumns
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.AppGridShortcut

class AppGridLauncherOptionsPreferencesTest : AndroidTestCase() {
    fun testColumnAndShortcutChoicesPersistAndRoundTrip() {
        val preferences = AppGridPreferences(context)
        val before = preferences.exportSettings()
        try {
            preferences.restoreSettings(grid().put("columns", "auto")
                .put("visibleShortcuts", JSONArray(AppGridShortcut.entries.map { it.storedValue } + "androidSettings")))
            runBlocking {
                preferences.setColumns(AppGridColumns.SIX)
                preferences.setShortcutVisible(AppGridShortcut.DISPLAY, false)
            }
            val saved = AppGridPreferences(context).exportSettings()
            assertEquals("6", saved.getString("columns"))
            assertEquals(AppGridShortcut.entries.size - 1, saved.getJSONArray("visibleShortcuts").length())
            assertFalse(saved.getJSONArray("visibleShortcuts").toString().contains("\"display\""))

            val legacy = AppGridPreferences.validateSettings(grid())
            assertFalse(legacy.has("columns"))
            assertFalse(legacy.has("visibleShortcuts"))
            preferences.restoreSettings(legacy)
            assertEquals(saved.toString(), preferences.exportSettings().toString())

            preferences.restoreSettings(AppGridPreferences.validateSettings(grid().put("visibleShortcuts", JSONArray())))
            assertEquals(0, preferences.exportSettings().getJSONArray("visibleShortcuts").length())
            assertEquals("6", preferences.exportSettings().getString("columns"))
            preferences.restoreSettings(AppGridPreferences.validateSettings(saved))
            assertEquals(saved.toString(), preferences.exportSettings().toString())
        } finally {
            preferences.restoreSettings(before)
        }
    }

    fun testArchivesRejectInvalidColumnsShortcutsAndDesktopNames() {
        val invalid = listOf(
            grid().put("columns", 6),
            grid().put("columns", "7"),
            grid().put("visibleShortcuts", JSONArray(listOf("display", "display"))),
            grid().put("visibleShortcuts", JSONArray(listOf("settings"))),
            grid().put("visibleShortcuts", JSONArray(listOf("search"))),
            grid().put("desktopNames", JSONArray()),
            grid().put("desktopNames", JSONObject().put("favorites1", 123)),
            grid().put("desktopNames", JSONObject().put("favorites2", "Home\nOffice")),
            grid().put("desktopNames", JSONObject().put("favorites2", "Я".repeat(25))),
        )
        invalid.forEach { value ->
            assertTrue(runCatching { AppGridPreferences.validateSettings(value) }.isFailure)
        }
    }

    fun testPinMovementPersistsOnlyOnItsDesktop() {
        val preferences = AppGridPreferences(context)
        val before = preferences.exportSettings()
        val maps = "com.example.maps/com.example.maps.Main"
        val media = "com.example.media/com.example.media.Main"
        val pins = JSONArray(listOf(maps, media))
        try {
            preferences.restoreSettings(grid().put("pinnedApps", pins)
                .put("desktops", JSONObject().put("enabled", true).put("defaultDesktop", "main")
                    .put("favorites1", pins).put("favorites2", pins)))
            runBlocking { preferences.swapPins(media, maps, AppGridDesktop.FAVORITES_ONE) }
            val saved = AppGridPreferences(context).exportSettings()
            assertEquals(pins.toString(), saved.getJSONArray("pinnedApps").toString())
            val desktops = saved.getJSONObject("desktops")
            assertEquals(JSONArray(listOf(media, maps)).toString(), desktops.getJSONArray("favorites1").toString())
            assertEquals(pins.toString(), desktops.getJSONArray("favorites2").toString())
        } finally {
            preferences.restoreSettings(before)
        }
    }

    fun testDesktopNamesPersistBySlotAndPartialBackupsKeepTheOtherName() {
        val preferences = AppGridPreferences(context)
        val before = preferences.exportSettings()
        val desktops = JSONObject().put("enabled", true).put("defaultDesktop", "favorites2")
            .put("favorites1", JSONArray(listOf("com.example.maps/com.example.maps.Main")))
            .put("favorites2", JSONArray(listOf("com.example.media/com.example.media.Main")))
        try {
            preferences.restoreSettings(grid().put("desktops", desktops))
            runBlocking { preferences.setDesktopNames(" Навигация  ", "Семья") }
            val saved = AppGridPreferences(context).exportSettings()
            val names = saved.getJSONObject("desktopNames")
            assertEquals("Навигация", names.getString("favorites1"))
            assertEquals("Семья", names.getString("favorites2"))
            assertEquals(desktops.toString(), saved.getJSONObject("desktops").toString())

            val legacy = AppGridPreferences.validateSettings(grid())
            assertFalse(legacy.has("desktopNames"))
            preferences.restoreSettings(legacy)
            assertEquals(names.toString(), preferences.exportSettings().getJSONObject("desktopNames").toString())

            val oneReset = AppGridPreferences.validateSettings(grid().put("desktopNames", JSONObject().put("favorites1", "")))
            assertFalse(oneReset.getJSONObject("desktopNames").has("favorites2"))
            preferences.restoreSettings(oneReset)
            val restored = preferences.exportSettings().getJSONObject("desktopNames")
            assertEquals("", restored.getString("favorites1"))
            assertEquals("Семья", restored.getString("favorites2"))
            preferences.restoreSettings(AppGridPreferences.validateSettings(saved))
            assertEquals(saved.toString(), preferences.exportSettings().toString())
        } finally {
            preferences.restoreSettings(before)
        }
    }

    private fun grid() = JSONObject().put("pinnedApps", JSONArray())
}
