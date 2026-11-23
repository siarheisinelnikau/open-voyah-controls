package org.openvoyah.controls.presentation.dock

import android.content.Context
import android.test.AndroidTestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.data.dock.QuickActionPreferences
import org.openvoyah.controls.data.settings.LocalSettingsArchive

class QuickActionAppShortcutPreferencesTest : AndroidTestCase() {
    private val primary = "org.example.maps/org.example.maps.Main"
    private val alternate = "org.example.maps/org.example.maps.Driving"
    private val unavailable = "org.example.uninstalled/org.example.uninstalled.Start"

    fun testDockShortcutsSurviveReopenAndPortablePartialRestore() = runBlocking {
        val raw = context.getSharedPreferences(QuickActionPreferences.STORE, Context.MODE_PRIVATE)
        val previous = raw.getString("settings", null)
        try {
            // Pre-shortcut preference files have no appShortcuts field.
            assertTrue(raw.edit().putString("settings", """{"enabled":true,"side":"left","actions":["apps"]}""").commit())
            val dock = QuickActionPreferences(context)
            assertTrue(dock.observe().first().appShortcuts.isEmpty())
            val saved = listOf(alternate, unavailable, primary)
            dock.update { it.copy(appShortcuts = saved) }
            val reopened = QuickActionPreferences(context)
            assertEquals(saved, reopened.observe().first().appShortcuts)
            val archive = LocalSettingsArchive(context)
            val document = JSONObject(archive.exportJson())
            val section = document.getJSONObject("quickActions")
            assertEquals(JSONArray(saved).toString(), section.getJSONArray("appShortcuts").toString())
            assertTrue(archive.prepare(document.toString()).complete)

            section.remove("appShortcuts")
            reopened.restoreSettings(QuickActionPreferences.validateSettings(section))
            assertEquals(saved, reopened.observe().first().appShortcuts)
            section.put("appShortcuts", JSONArray())
            reopened.restoreSettings(QuickActionPreferences.validateSettings(section))
            assertTrue(reopened.observe().first().appShortcuts.isEmpty())
            assertEquals("left", reopened.exportSettings().getString("side"))
            assertEquals("""["apps"]""", reopened.exportSettings().getJSONArray("actions").toString())
        } finally { assertTrue(raw.edit().putString("settings", previous).commit()) }
    }

    fun testMalformedListsRejectTheWholeDockSection() {
        val dock = QuickActionPreferences(context)
        val original = dock.exportSettings().toString()
        val invalid = listOf(JSONObject.NULL, "maps", JSONArray(listOf(9)), JSONArray(listOf(primary, primary)),
            JSONArray(listOf("org.example.maps/.Main")), JSONArray(listOf("org.openvoyah.controls/org.openvoyah.controls.MainActivity")),
            JSONArray(List(5) { "$primary$it" }))
        for (value in invalid) {
            val section = JSONObject().put("enabled", !JSONObject(original).getBoolean("enabled")).put("appShortcuts", value)
            try {
                QuickActionPreferences.validateSettings(section)
                fail("Accepted an invalid shortcut list")
            } catch (_: IllegalArgumentException) { }
            try {
                dock.restoreSettings(section)
                fail("Restored an invalid shortcut list")
            } catch (_: IllegalArgumentException) { }
            assertEquals(original, dock.exportSettings().toString())
        }
    }
}
