package org.openvoyah.controls

import android.test.AndroidTestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.data.apps.DesktopLayoutsPreferences
import org.openvoyah.controls.data.settings.LocalSettingsArchive
import org.openvoyah.controls.domain.apps.AppGridConfiguration

class AppGridHiddenAppsPreferencesTest : AndroidTestCase() {
    private val maps = "org.example.navigation/org.example.navigation.Maps"
    private val places = "org.example.navigation/org.example.navigation.Places"

    fun testVisibilityPersistsAndMergesMissingArchiveAndLayoutFields() = runBlocking {
        val preferences = AppGridPreferences(context)
        val archive = LocalSettingsArchive(context)
        val original = archive.prepare(archive.exportJson())
        val layouts = DesktopLayoutsPreferences(context)
        fun hidden() = preferences.exportSettings().getJSONArray("hiddenApps").toString()
        try {
            layouts.restoreSettings(JSONObject())
            val pins = JSONArray(listOf(maps, places))
            val manualOrder = JSONArray(listOf(places, maps))
            val desktops = JSONObject().put("enabled", true).put("defaultDesktop", "main")
                .put("favorites1", JSONArray(listOf(maps))).put("favorites2", JSONArray(listOf(places)))
            preferences.restoreSettings(JSONObject().put("pinnedApps", pins).put("manualAppOrder", manualOrder)
                .put("desktops", desktops).put("hiddenApps", JSONArray()))
            preferences.setAppHidden(maps, true, hiddenByDefault = false)
            preferences.setAppHidden(maps, true, hiddenByDefault = false)
            val fresh = AppGridPreferences(context).observeSettings().first().getOrThrow()
            assertEquals(setOf(maps), fresh.hiddenApps)
            assertEquals(listOf(maps, places), fresh.pinnedApps)
            assertEquals(listOf(places, maps), fresh.manualAppOrder)
            assertEquals(listOf(maps), fresh.desktops.firstFavorites)
            assertEquals(listOf(places), fresh.desktops.secondFavorites)

            layouts.save("Commute")
            val layout = layouts.observe().first().getOrThrow().single()
            val full = archive.exportJson()
            assertEquals(JSONArray(listOf(maps)).toString(), JSONObject(full).getJSONObject("appGrid").getJSONArray("hiddenApps").toString())
            preferences.setAppHidden(maps, false, hiddenByDefault = false)
            layouts.apply(layout.id)
            assertEquals(JSONArray(listOf(maps)).toString(), hidden())

            preferences.setAppHidden(maps, false, hiddenByDefault = false)
            preferences.setAppHidden(places, true, hiddenByDefault = false)
            val oldArchive = JSONObject(full)
            oldArchive.getJSONObject("appGrid").remove("hiddenApps")
            archive.apply(archive.prepare(oldArchive.toString()))
            assertEquals(JSONArray(listOf(places)).toString(), hidden())
            val oldLayouts = layouts.exportSettings()
            oldLayouts.getJSONArray("presets").getJSONObject(0).getJSONObject("appGrid").remove("hiddenApps")
            layouts.restoreSettings(oldLayouts)
            layouts.apply(layout.id)
            assertEquals(JSONArray(listOf(places)).toString(), hidden())

            archive.apply(archive.prepare(full))
            assertEquals(JSONArray(listOf(maps)).toString(), hidden())
            val empty = JSONObject(full)
            empty.getJSONObject("appGrid").put("hiddenApps", JSONArray())
            archive.apply(archive.prepare(empty.toString()))
            assertEquals("[]", hidden())
            layouts.save("All visible")
            preferences.setAppHidden(places, true, hiddenByDefault = false)
            layouts.apply(layouts.observe().first().getOrThrow().last().id)
            assertEquals("[]", hidden())
            val after = preferences.exportSettings()
            assertEquals(pins.toString(), after.getJSONArray("pinnedApps").toString())
            assertEquals(manualOrder.toString(), after.getJSONArray("manualAppOrder").toString())
            assertEquals(desktops.getJSONArray("favorites1").toString(),
                after.getJSONObject("desktops").getJSONArray("favorites1").toString())
        } finally {
            archive.apply(original)
        }
    }

    fun testShownAppsAndTheDefaultRuleRoundTripAndAnOlderSystemSwitchIsIgnored() = runBlocking {
        val preferences = AppGridPreferences(context)
        val archive = LocalSettingsArchive(context)
        val original = archive.prepare(archive.exportJson())
        val layouts = DesktopLayoutsPreferences(context)
        val calendar = "com.android.calendar/com.android.calendar.AllInOneActivity"
        suspend fun grid() = AppGridPreferences(context).observeSettings().first().getOrThrow()
        try {
            layouts.restoreSettings(JSONObject())
            // An archive from before the Hide default: its showSystemApps value says nothing about a choice.
            preferences.restoreSettings(JSONObject().put("pinnedApps", JSONArray()).put("showSystemApps", true))
            assertTrue(grid().hideByDefault)
            assertFalse(preferences.exportSettings().has("showSystemApps"))

            preferences.setAppHidden(calendar, false, hiddenByDefault = true)
            preferences.setAppHidden(maps, true, hiddenByDefault = false)
            assertEquals(setOf(calendar), grid().shownApps)
            assertEquals(setOf(maps), grid().hiddenApps)
            preferences.setHideByDefault(false)
            assertFalse(grid().hideByDefault)

            layouts.save("Choices")
            val layout = layouts.observe().first().getOrThrow().single()
            val full = archive.exportJson()
            val exported = JSONObject(full).getJSONObject("appGrid")
            assertEquals(JSONArray(listOf(calendar)).toString(), exported.getJSONArray("shownApps").toString())
            assertFalse(exported.getBoolean("hideByDefault"))

            preferences.resetHiddenApps()
            preferences.setHideByDefault(true)
            assertTrue(grid().shownApps.isEmpty() && grid().hiddenApps.isEmpty())
            layouts.apply(layout.id)
            assertEquals(setOf(calendar), grid().shownApps)
            assertFalse(grid().hideByDefault)

            // Archives and layouts from before these fields keep the current choices.
            preferences.setHideByDefault(true)
            val older = JSONObject(full)
            older.getJSONObject("appGrid").apply { remove("shownApps"); remove("hideByDefault") }
            archive.apply(archive.prepare(older.toString()))
            assertEquals(setOf(calendar), grid().shownApps)
            assertTrue(grid().hideByDefault)
            val oldLayouts = layouts.exportSettings()
            oldLayouts.getJSONArray("presets").getJSONObject(0).getJSONObject("appGrid").apply {
                remove("shownApps"); remove("hideByDefault")
            }
            layouts.restoreSettings(oldLayouts)
            preferences.setAppHidden(calendar, true, hiddenByDefault = true)
            layouts.apply(layout.id)
            assertTrue(grid().shownApps.isEmpty())
            assertTrue(grid().hideByDefault)
        } finally {
            archive.apply(original)
        }
    }

    fun testVisibilityRejectsMalformedArchivesBeforeChangingPreferences() {
        val preferences = AppGridPreferences(context)
        val original = preferences.exportSettings().toString()
        val invalid = listOf(
            JSONObject.NULL, maps, JSONArray().put(12), JSONArray(listOf(maps, maps)),
            JSONArray(listOf("org.example.navigation")), JSONArray(listOf("org.example.navigation/.Maps")),
            JSONArray(listOf("org.openvoyah.controls/org.openvoyah.controls.MainActivity")),
            JSONArray(listOf("org.example/" + "A".repeat(513))),
            JSONArray(List(AppGridConfiguration.MAX_HIDDEN_APPS + 1) { "org.example.apps/org.example.apps.Shortcut$it" }),
        )
        for (field in listOf("hiddenApps", "shownApps")) for (value in invalid) {
            val data = JSONObject().put("pinnedApps", JSONArray()).put(field, value)
            assertTrue(runCatching { AppGridPreferences.validateSettings(data) }.isFailure)
            assertTrue(runCatching { AppGridPreferences.validateLayout(data) }.isFailure)
            assertTrue(runCatching { preferences.restoreSettings(data) }.isFailure)
        }
        assertTrue(runCatching {
            AppGridPreferences.validateSettings(JSONObject().put("pinnedApps", JSONArray()).put("hideByDefault", "yes"))
        }.isFailure)
        assertEquals(original, preferences.exportSettings().toString())
    }
}
