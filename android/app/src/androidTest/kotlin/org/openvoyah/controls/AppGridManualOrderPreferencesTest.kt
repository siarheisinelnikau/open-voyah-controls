package org.openvoyah.controls

import android.test.AndroidTestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.data.apps.DesktopLayoutsPreferences
import org.openvoyah.controls.data.settings.LocalSettingsArchive
import org.openvoyah.controls.domain.apps.AppGridManualOrder
import org.openvoyah.controls.domain.apps.AppGridOrderApp
import org.openvoyah.controls.domain.apps.AppGridSortOrder

class AppGridManualOrderPreferencesTest : AndroidTestCase() {
    fun testManualOrderSurvivesSortChangesPartialArchivesAndNamedLayouts() = runBlocking {
        val archive = LocalSettingsArchive(context)
        val original = archive.prepare(archive.exportJson())
        val preferences = AppGridPreferences(context)
        val layouts = DesktopLayoutsPreferences(context)
        val first = app("first")
        val second = app("second")
        val hidden = app("hidden")
        val missing = app("missing")
        val originalOrder = listOf(first, hidden, missing, second)
        val swapped = listOf(second, hidden, missing, first)
        fun order() = preferences.exportSettings().getJSONArray("manualAppOrder").toString()
        try {
            layouts.restoreSettings(JSONObject())
            preferences.restoreSettings(grid().put("manualAppOrder", JSONArray(originalOrder))
                .put("sortOrder", "manual").put("hideByDefault", true))
            preferences.moveManualApp(listOf(AppGridOrderApp(first, false), AppGridOrderApp(hidden, true), AppGridOrderApp(second, false)),
                second, first, earlier = true)
            assertEquals(JSONArray(swapped).toString(), order())
            preferences.setSortOrder(AppGridSortOrder.RECENTLY_INSTALLED)
            assertEquals(swapped, AppGridPreferences(context).observeSettings().first().getOrThrow().manualAppOrder)
            preferences.setSortOrder(AppGridSortOrder.MANUAL)

            layouts.save("Trip")
            val layout = layouts.observe().first().getOrThrow().single()
            val full = archive.exportJson()
            val oldGrid = grid().put("sortOrder", "name")
            val oldArchive = JSONObject(full).put("appGrid", oldGrid)
            archive.apply(archive.prepare(oldArchive.toString()))
            assertEquals("name", preferences.exportSettings().getString("sortOrder"))
            assertEquals(JSONArray(swapped).toString(), order())

            preferences.resetManualAppOrder()
            assertEquals("[]", order())
            layouts.apply(layout.id)
            assertEquals("manual", preferences.exportSettings().getString("sortOrder"))
            assertEquals(JSONArray(swapped).toString(), order())
            preferences.resetManualAppOrder()
            archive.apply(archive.prepare(full))
            assertEquals(JSONArray(swapped).toString(), order())

            val legacyLayouts = layouts.exportSettings().apply {
                val legacyGrid = getJSONArray("presets").getJSONObject(0).getJSONObject("appGrid")
                legacyGrid.remove("manualAppOrder")
                legacyGrid.put("sortOrder", "recentlyInstalled")
            }
            layouts.restoreSettings(legacyLayouts)
            layouts.apply(layout.id)
            assertEquals("recentlyInstalled", preferences.exportSettings().getString("sortOrder"))
            assertEquals(JSONArray(swapped).toString(), order())

            preferences.restoreSettings(grid().put("manualAppOrder", JSONArray()))
            layouts.save("Default")
            preferences.restoreSettings(grid().put("manualAppOrder", JSONArray(originalOrder)))
            layouts.apply(layouts.observe().first().getOrThrow().last().id)
            assertEquals("[]", order())
        } finally {
            archive.apply(original)
        }
    }

    fun testManualOrderRejectsDuplicateInvalidAndOversizedEntries() {
        val first = app("first")
        val invalid = listOf(
            JSONObject.NULL,
            "not an array",
            JSONArray().put(123),
            JSONArray(listOf(first, first)),
            JSONArray(listOf("content://org.example")),
            JSONArray(listOf("org.openvoyah.controls/org.openvoyah.controls.MainActivity")),
            JSONArray(List(AppGridManualOrder.MAX_APPS + 1) { app("a$it") }),
        )
        for (value in invalid) {
            val grid = grid().put("manualAppOrder", value)
            assertTrue(runCatching { AppGridPreferences.validateSettings(grid) }.isFailure)
            assertTrue(runCatching { AppGridPreferences.validateLayout(grid) }.isFailure)
        }
    }

    private fun grid() = JSONObject().put("pinnedApps", JSONArray())
    private fun app(name: String) = "org.example.$name/org.example.$name.Main"
}
