package org.openvoyah.controls.data.climate

import android.test.AndroidTestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject
import org.openvoyah.controls.domain.climate.ClimateGearCondition
import org.openvoyah.controls.domain.climate.SeasonalClimateChanged
import org.openvoyah.controls.domain.climate.SeasonalClimateSettings

class SeasonalClimatePreferencesTest : AndroidTestCase() {
    fun testPresetsSurviveRecreationAndValidatedImport() = runBlocking {
        val store = LocalSeasonalClimatePreferences(context)
        val before = store.exportSettings()
        try {
            val original = store.observe().first().getOrThrow()
            val configured = SeasonalClimateSettings().let { defaults -> defaults.copy(
                summer = defaults.summer.copy(enabled = true, outsideThresholdCelsius = 28,
                    driverCelsius = 20, passengerCelsius = 21, fanLevel = 5, gearCondition = ClimateGearCondition.DRIVE,
                    driverSeatLevel = 3, passengerSeatLevel = 2),
                winter = defaults.winter.copy(enabled = true, outsideThresholdCelsius = -5,
                    driverCelsius = 25, passengerCelsius = 26, requireDoorsClosed = false),
            ) }
            store.update(original, configured)
            assertEquals(configured, LocalSeasonalClimatePreferences(context).observe().first().getOrThrow())
            val exported = store.exportSettings()
            store.restoreSettings(LocalSeasonalClimatePreferences(context).exportSettings().apply {
                getJSONObject("summer").put("enabled", false)
            })
            store.restoreSettings(LocalSeasonalClimatePreferences.validateSettings(exported))
            assertEquals(configured, store.observe().first().getOrThrow())
            try {
                store.update(original, SeasonalClimateSettings())
                fail("An old editor must not overwrite a newer preset")
            } catch (_: SeasonalClimateChanged) { }
            assertEquals(configured, store.observe().first().getOrThrow())
        } finally {
            store.restoreSettings(before)
        }
    }

    fun testInvalidImportsAreRejectedBeforeChangingEitherPreset() {
        val store = LocalSeasonalClimatePreferences(context)
        val original = store.exportSettings().toString()
        val invalid = listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("summer").put("driverCelsius", 15) },
            { it.getJSONObject("summer").put("driverCelsius", "22") },
            { it.getJSONObject("winter").put("fanLevel", 0) },
            { it.getJSONObject("winter").put("enabled", "true") },
            { it.getJSONObject("summer").put("outsideThresholdCelsius", 5) },
            { it.put("schema", 2) },
        )
        for (change in invalid) {
            try {
                store.restoreSettings(JSONObject(original).also(change))
                fail("Invalid climate settings were accepted")
            } catch (_: JSONException) { }
            assertEquals(original, store.exportSettings().toString())
        }
    }

    fun testACompleteValidatedImportCanRecoverADamagedPreferenceType() = runBlocking {
        val store = LocalSeasonalClimatePreferences(context)
        val original = store.exportSettings()
        val preferences = context.getSharedPreferences(LocalSeasonalClimatePreferences.STORE, 0)
        try {
            assertTrue(preferences.edit().putBoolean("settings", true).commit())
            assertTrue(store.observe().first().isFailure)
            try {
                store.restoreSettings(JSONObject())
                fail("An incomplete import must not erase a damaged record")
            } catch (_: JSONException) { }
            assertEquals(true, preferences.all["settings"])
            store.restoreSettings(LocalSeasonalClimatePreferences.validateSettings(original))
            assertEquals(original.toString(), store.exportSettings().toString())
            assertTrue(store.observe().first().isSuccess)
        } finally {
            store.restoreSettings(original)
        }
    }
}
