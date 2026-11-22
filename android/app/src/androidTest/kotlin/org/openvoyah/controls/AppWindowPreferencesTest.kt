package org.openvoyah.controls

import android.test.AndroidTestCase
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.data.display.AppWindowPreferences
import org.openvoyah.controls.domain.display.AppDisplayDefaults
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
            assertTrue(preferences.load(component).inheritDensity)
        } finally {
            preferences.restoreProfiles(previous)
        }
    }

    fun testLegacySystemDpiAndDisplayInheritanceRemainDistinct() {
        val preferences = AppWindowPreferences(context)
        val previous = preferences.exportProfiles()
        val defaultsBefore = preferences.loadDefaults()
        val component = "org.example.window/.Main"
        try {
            preferences.restoreProfiles(JSONArray(
                """[{"component":"org.example.window/.Main","width":80,"dpi":0,"keepPanel":false,"panelPx":0}]""",
            ))
            assertFalse(preferences.load(component).inheritDensity)
            val chosen = AppDisplayDefaults(190, 220)
            preferences.saveDefaults(chosen)
            assertEquals(chosen, AppWindowPreferences(context).loadDefaults())
            val config = JSONObject(preferences.agentConfig(true))
            assertEquals(190, config.getJSONObject("displayDpi").getInt("central"))
            assertEquals(220, config.getJSONObject("displayDpi").getInt("secondary"))
            assertFalse(config.getJSONArray("apps").getJSONObject(0).getBoolean("inheritDpi"))
            val unreviewed = JSONObject(preferences.agentConfig(true, allowSecondary = false))
            assertTrue(unreviewed.getJSONObject("displayDpi").isNull("secondary"))
            assertFalse(unreviewed.has("passengerDisplayId"))
            val reviewed = JSONObject(preferences.agentConfig(true, allowSecondary = true, passengerDisplayId = 1))
            assertEquals(1, reviewed.getInt("passengerDisplayId"))
            assertEquals(220, reviewed.getJSONObject("displayDpi").getInt("secondary"))

            preferences.save(preferences.load(component).copy(inheritDensity = true))
            assertTrue(AppWindowPreferences(context).load(component).inheritDensity)
            assertTrue(preferences.exportProfiles().getJSONObject(0).getBoolean("inheritDpi"))
        } finally {
            preferences.saveDefaults(defaultsBefore)
            preferences.restoreProfiles(previous)
        }
    }

    fun testTheAgentDocumentCarriesTheExperimentalStripOnlyWhenItIsOn() {
        val preferences = AppWindowPreferences(context)
        val plain = JSONObject(preferences.agentConfig(true))
        assertFalse(plain.has("systemInsets"))
        assertFalse(plain.has("systemInsetsAttempt"))
        val reserved = JSONObject(preferences.agentConfig(true, systemInsetsAttempt = 3))
        assertTrue(reserved.getBoolean("systemInsets"))
        assertEquals(3, reserved.getInt("systemInsetsAttempt"))
        // Everything else in the document is the same with and without the option.
        reserved.remove("systemInsets")
        reserved.remove("systemInsetsAttempt")
        assertEquals(plain.toString(), reserved.toString())
        try {
            preferences.agentConfig(true, systemInsetsAttempt = -1)
            fail("A negative attempt number must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    fun testTheWindowSwipeIsStoredWithTheDefaultsAndSentOnlyWhenReviewed() {
        val preferences = AppWindowPreferences(context)
        val defaultsBefore = preferences.loadDefaults()
        try {
            preferences.saveDefaults(AppDisplayDefaults(moveFingers = 0))
            assertFalse(JSONObject(preferences.agentConfig(true, allowDisplayMove = true)).has("displayMove"))
            // A swipe turned off is written as such, so it does not come back on with the default.
            assertEquals(0, preferences.exportDefaults().getInt("moveFingers"))
            assertEquals(0, AppWindowPreferences(context).loadDefaults().moveFingers)
            // Documents from before the swipe take its default: three fingers.
            assertEquals(3, AppWindowPreferences.validateDefaults(JSONObject("""{"central":0,"secondary":null}""")).moveFingers)

            preferences.saveDefaults(AppDisplayDefaults(moveFingers = 3))
            assertEquals(3, AppWindowPreferences(context).loadDefaults().moveFingers)
            assertEquals(3, preferences.exportDefaults().getInt("moveFingers"))
            assertFalse(JSONObject(preferences.agentConfig(true)).has("displayMove"))
            val config = JSONObject(preferences.agentConfig(true, allowDisplayMove = true))
            assertEquals("""{"fingers":3}""", config.getJSONObject("displayMove").toString())
            // The swipe alone is no density override for the agent.
            assertFalse(config.has("displayDpi"))
            assertEquals(AppDisplayDefaults(moveFingers = 3),
                AppWindowPreferences.validateDefaults(JSONObject("""{"central":0,"secondary":null,"moveFingers":3}""")))
            for (invalid in listOf(""""3"""", "4")) {
                try {
                    AppWindowPreferences.validateDefaults(JSONObject("""{"central":0,"secondary":null,"moveFingers":$invalid}"""))
                    fail("An invalid swipe must be rejected: $invalid")
                } catch (_: IllegalStateException) {
                } catch (_: IllegalArgumentException) { }
            }
        } finally {
            preferences.saveDefaults(defaultsBefore)
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
