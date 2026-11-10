package org.openvoyah.controls

import android.test.AndroidTestCase
import org.json.JSONArray
import org.openvoyah.controls.data.display.AppWindowPreferences
import org.openvoyah.controls.domain.display.AppWindowProfile

class AppWindowPreferencesTest : AndroidTestCase() {
    fun testProfileRoundTripAndReset() {
        val preferences = AppWindowPreferences(context)
        val previous = preferences.exportProfiles()
        val component = "org.example.window/.Main"
        try {
            preferences.restoreProfiles(JSONArray())
            preferences.save(AppWindowProfile(component, 75, 190, 200))
            val stored = AppWindowPreferences(context).load(component)
            assertEquals("org.example.window/org.example.window.Main", stored.component)
            assertEquals(75, stored.widthPercent)
            assertEquals(190, stored.densityDpi)
            assertEquals(200, stored.panelWidthPx)
            assertEquals(1, AppWindowPreferences.validateProfiles(preferences.exportProfiles()).length())
            preferences.reset(component)
            assertEquals(100, preferences.load(component).widthPercent)
            assertEquals(0, preferences.load(component).densityDpi)
        } finally {
            preferences.restoreProfiles(previous)
        }
    }

    fun testLegacyProfileAppliesToOtherLaunchEntriesInThePackage() {
        val preferences = AppWindowPreferences(context)
        val previous = preferences.exportProfiles()
        val alias = "org.example.window/.Alias"
        try {
            preferences.restoreProfiles(JSONArray(
                """[{"component":"org.example.window/.Main","width":80,"dpi":180,"keepPanel":false,"panelPx":0}]""",
            ))
            val profile = preferences.load(alias)
            assertEquals("org.example.window/org.example.window.Alias", profile.component)
            assertEquals(180, profile.densityDpi)
            assertEquals(80, profile.widthPercent)
            assertTrue(preferences.exportProfiles().getJSONObject(0).getBoolean("keepPanel"))
            preferences.save(AppWindowProfile(alias))
            assertEquals(0, preferences.exportProfiles().length())
        } finally {
            preferences.restoreProfiles(previous)
        }
    }

    fun testRejectsSystemComponentsAndUnsafeBounds() {
        assertNull(AppWindowProfile.key("com.android.systemui/.Main"))
        assertNull(AppWindowProfile.key("org.example.app/.Main; id"))
        for (width in listOf(0, 78, 105)) {
            try {
                AppWindowProfile("org.example.app/.Main", widthPercent = width)
                fail("Invalid width was accepted")
            } catch (_: IllegalArgumentException) {
                // The profile is rejected before persistence or dispatch.
            }
        }
    }
}
