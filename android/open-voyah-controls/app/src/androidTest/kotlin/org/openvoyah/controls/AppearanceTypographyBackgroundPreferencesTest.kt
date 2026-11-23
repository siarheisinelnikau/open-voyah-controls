package org.openvoyah.controls

import android.content.Context
import android.test.AndroidTestCase
import org.json.JSONObject
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.domain.settings.AppBackgroundTreatment
import org.openvoyah.controls.domain.settings.AppFontFamily
import org.openvoyah.controls.domain.settings.AppRoleTextSize

class AppearanceTypographyBackgroundPreferencesTest : AndroidTestCase() {
    fun testNavigationAndPageSizesPersistIndependentlyAndOldArchivesPreserveThem() {
        val preferences = AppearancePreferences(context)
        val store = context.getSharedPreferences(AppearancePreferences.STORE, Context.MODE_PRIVATE)
        val keys = listOf("navigation_label_size", "page_title_size")
        val original = store.all
        try {
            assertTrue(store.edit().apply { keys.forEach { remove(it) } }.commit())
            val defaults = preferences.load()
            assertEquals(AppRoleTextSize.FOLLOW, defaults.navigationLabelSize)
            assertEquals(AppRoleTextSize.FOLLOW, defaults.pageTitleSize)

            preferences.setNavigationLabelSize(AppRoleTextSize.EXTRA_LARGE)
            assertEquals(defaults.copy(navigationLabelSize = AppRoleTextSize.EXTRA_LARGE), preferences.load())
            preferences.setPageTitleSize(AppRoleTextSize.STANDARD)
            val custom = defaults.copy(navigationLabelSize = AppRoleTextSize.EXTRA_LARGE, pageTitleSize = AppRoleTextSize.STANDARD)
            assertEquals(custom, AppearancePreferences(context).load())
            val backup = preferences.exportSettings()
            assertEquals("extra_large", backup.getString("navigationLabelSize"))
            assertEquals("standard", backup.getString("pageTitleSize"))

            val earlier = AppearancePreferences.validateSettings(JSONObject().put("textSize", defaults.textSize.preferenceId))
            assertEquals(1, earlier.length())
            preferences.restoreSettings(earlier)
            assertEquals(custom, preferences.load())
            preferences.restoreSettings(AppearancePreferences.validateSettings(JSONObject().put("pageTitleSize", "follow")))
            assertEquals(custom.copy(pageTitleSize = AppRoleTextSize.FOLLOW), preferences.load())
            preferences.restoreSettings(AppearancePreferences.validateSettings(backup))
            assertEquals(custom, preferences.load())
        } finally {
            val cleanup = store.edit()
            for (key in keys) {
                val value = original[key] as? String
                if (value == null) cleanup.remove(key) else cleanup.putString(key, value)
            }
            assertTrue(cleanup.commit())
        }
    }

    fun testNewAppearanceOptionsAreIndependentAndPortable() {
        val preferences = AppearancePreferences(context)
        val store = context.getSharedPreferences(AppearancePreferences.STORE, Context.MODE_PRIVATE)
        val keys = listOf("font_family", "background_treatment", "bold_text")
        val original = store.all
        try {
            assertTrue(store.edit().apply { keys.forEach { remove(it) } }.commit())
            val defaults = preferences.load()
            assertEquals(AppFontFamily.SYSTEM, defaults.fontFamily)
            assertEquals(AppBackgroundTreatment.AMBIENT, defaults.backgroundTreatment)
            assertFalse(defaults.boldText)

            val customFont = defaults.copy(fontFamily = AppFontFamily.GOLOS_TEXT)
            preferences.setFontFamily(customFont.fontFamily)
            assertEquals(customFont, AppearancePreferences(context).load())
            val solid = customFont.copy(backgroundTreatment = AppBackgroundTreatment.SOLID)
            preferences.setBackgroundTreatment(solid.backgroundTreatment)
            assertEquals(solid, preferences.load())
            preferences.setBoldText(true)
            val custom = solid.copy(boldText = true)
            assertEquals(custom, preferences.load())
            val exported = preferences.exportSettings()
            assertEquals("golosText", exported.getString("fontFamily"))
            assertEquals("solid", exported.getString("backgroundTreatment"))
            assertTrue(exported.getBoolean("boldText"))

            val old = AppearancePreferences.validateSettings(JSONObject().put("palette", defaults.palette.preferenceId))
            assertEquals(1, old.length())
            preferences.restoreSettings(old)
            assertEquals(custom, preferences.load())
            preferences.restoreSettings(AppearancePreferences.validateSettings(JSONObject().put("boldText", false)))
            assertEquals(solid, preferences.load())
            preferences.restoreSettings(AppearancePreferences.validateSettings(exported))
            assertEquals(custom, AppearancePreferences(context).load())
        } finally {
            val cleanup = store.edit()
            for (key in keys) {
                when (val value = original[key]) {
                    is String -> cleanup.putString(key, value)
                    is Boolean -> cleanup.putBoolean(key, value)
                    else -> cleanup.remove(key)
                }
            }
            assertTrue(cleanup.commit())
        }
    }

    fun testTypographyAndBackgroundArchiveRejectUnknownOptions() {
        for ((key, value) in listOf("fontFamily" to "arbitraryFont", "fontFamily" to 4,
            "backgroundTreatment" to "image", "backgroundTreatment" to JSONObject.NULL, "boldText" to "true",
            "navigationLabelSize" to "oversized", "pageTitleSize" to 2, "pageTitleSize" to JSONObject.NULL)) {
            assertTrue(runCatching { AppearancePreferences.validateSettings(JSONObject().put(key, value)) }.isFailure)
        }
    }
}
