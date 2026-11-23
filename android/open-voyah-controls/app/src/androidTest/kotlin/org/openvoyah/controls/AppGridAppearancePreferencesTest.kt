package org.openvoyah.controls

import android.test.AndroidTestCase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.domain.apps.AppGridIconStyle
import org.openvoyah.controls.domain.apps.AppGridSortOrder

class AppGridAppearancePreferencesTest : AndroidTestCase() {
    fun testChoicesSaveAcrossInstancesAndRoundTripWithTheAppGridArchive() {
        val preferences = AppGridPreferences(context)
        val before = preferences.exportSettings()
        try {
            runBlocking {
                preferences.setIconStyle(AppGridIconStyle.MONOCHROME)
                preferences.setShowAppLabels(false)
                preferences.setSortOrder(AppGridSortOrder.RECENTLY_INSTALLED)
            }
            val saved = AppGridPreferences(context).exportSettings()
            assertEquals("monochrome", saved.getString("iconStyle"))
            assertFalse(saved.getBoolean("showAppLabels"))
            assertEquals("recentlyInstalled", saved.getString("sortOrder"))

            preferences.restoreSettings(grid().put("iconStyle", "native").put("showAppLabels", true).put("sortOrder", "name"))
            preferences.restoreSettings(AppGridPreferences.validateSettings(saved))
            assertEquals(saved.toString(), preferences.exportSettings().toString())
        } finally {
            preferences.restoreSettings(before)
        }
    }

    fun testOlderAndPartialGridArchivesLeaveOmittedChoicesInPlace() {
        val preferences = AppGridPreferences(context)
        val before = preferences.exportSettings()
        try {
            preferences.restoreSettings(grid().put("iconStyle", "monochrome").put("showAppLabels", false)
                .put("sortOrder", "recentlyInstalled"))
            preferences.restoreSettings(AppGridPreferences.validateSettings(grid()))
            preferences.restoreSettings(AppGridPreferences.validateSettings(grid().put("showAppLabels", true)))

            val restored = preferences.exportSettings()
            assertEquals("monochrome", restored.getString("iconStyle"))
            assertTrue(restored.getBoolean("showAppLabels"))
            assertEquals("recentlyInstalled", restored.getString("sortOrder"))
        } finally {
            preferences.restoreSettings(before)
        }
    }

    fun testInvalidIconLabelAndSortFieldsAreRejected() {
        val invalid = listOf(
            grid().put("iconStyle", "sepia"),
            grid().put("showAppLabels", "false"),
            grid().put("showAppLabels", JSONObject.NULL),
            grid().put("sortOrder", "name-descending"),
        )
        invalid.forEach { value ->
            assertTrue(runCatching { AppGridPreferences.validateSettings(value) }.isFailure)
        }
    }

    private fun grid() = JSONObject().put("pinnedApps", JSONArray())
}
