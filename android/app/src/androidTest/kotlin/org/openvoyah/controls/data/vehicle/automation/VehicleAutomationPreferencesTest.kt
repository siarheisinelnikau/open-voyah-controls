package org.openvoyah.controls.data.vehicle.automation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.AndroidTestCase
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.openvoyah.controls.domain.vehicle.automation.DrivingMode
import org.openvoyah.controls.domain.vehicle.automation.DrivingModeMemorySettings
import org.openvoyah.controls.domain.vehicle.automation.DrivingModeRecord
import org.openvoyah.controls.domain.vehicle.automation.HeadlightGearAction
import org.openvoyah.controls.domain.vehicle.automation.HeadlightGearSettings
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationSettings
import org.openvoyah.controls.domain.vehicle.automation.VehicleIdentity

class VehicleAutomationPreferencesTest : AndroidTestCase() {
    private val configured = VehicleAutomationSettings(
        HeadlightGearSettings(true, HeadlightGearAction.LOW_BEAM, HeadlightGearAction.OFF),
        DrivingModeMemorySettings(true),
    )

    fun testDurableRoundTripAndStaleEditorProtection() = withStore { app, preferences -> runBlocking {
        val store = LocalVehicleAutomationPreferences(app)
        store.update(VehicleAutomationSettings(), configured)
        assertEquals(configured, withTimeout(2_000) { LocalVehicleAutomationPreferences(app).observe().first().getOrThrow() })
        val exported = store.exportSettings()
        assertTrue(runCatching { store.restoreSettings(JSONObject()) }.isFailure)
        assertEquals(exported.toString(), store.exportSettings().toString())
        assertTrue(runCatching { store.update(VehicleAutomationSettings(), VehicleAutomationSettings()) }.isFailure)
        assertEquals(configured, withTimeout(2_000) { store.observe().first().getOrThrow() })
        val before = preferences.all
        val invalid = JSONObject(exported.toString()).apply {
            getJSONObject("headlights").put("onDrive", "off")
        }
        assertTrue(runCatching { store.restoreSettings(invalid) }.isFailure)
        assertEquals(before, preferences.all)
    } }

    fun testUnknownFieldsMalformedTypesAndCorruptSettingsRemainIntact() = withStore { app, preferences -> runBlocking {
        val store = LocalVehicleAutomationPreferences(app)
        val raw = store.exportSettings()
        listOf(
            JSONObject(raw.toString()).put("schema", "1"),
            JSONObject(raw.toString()).put("unexpected", true),
            JSONObject(raw.toString()).apply { getJSONObject("headlights").put("enabled", "true") },
        ).forEach { assertTrue(runCatching { LocalVehicleAutomationPreferences.validateSettings(it) }.isFailure) }
        val corrupt = "{\"schema\":1,\"headlights\":null}"
        assertTrue(preferences.edit().putString("settings", corrupt).commit())
        assertTrue(withTimeout(2_000) { store.observe().first() }.isFailure)
        assertTrue(runCatching { store.exportSettings() }.isFailure)
        assertEquals(corrupt, preferences.getString("settings", null))
        // An explicit complete, validated import can recover a damaged settings section.
        store.restoreSettings(raw)
        assertEquals(VehicleAutomationSettings(), withTimeout(2_000) { store.observe().first().getOrThrow() })
    } }

    fun testFailedSettingsCommitRestoresPreviousBytes() = withStore { app, preferences -> runBlocking {
        val normal = LocalVehicleAutomationPreferences(app)
        normal.update(VehicleAutomationSettings(), configured)
        val before = preferences.all
        val failed = LocalVehicleAutomationPreferences(contextFor(app, FirstCommitFails(preferences)))
        assertTrue(runCatching { failed.update(configured, VehicleAutomationSettings()) }.isFailure)
        assertEquals(before, preferences.all)
    } }

    fun testActualModeHistoryPersistsIdentityAndRejectsOlderSamples() = withStore { app, preferences -> runBlocking {
        val history = LocalDrivingModeHistory(app)
        val record = DrivingModeRecord(DrivingMode.SPORT, VehicleIdentity("fixture", "fixture/build:1", 30, "x86_64"),
            "vcu_feedback", "trip", 2_000)
        history.save(record)
        assertEquals(record, withTimeout(2_000) { LocalDrivingModeHistory(app).observe().first().getOrThrow() })
        assertTrue(runCatching { history.save(record.copy(mode = DrivingMode.COMFORT, confirmedAtMillis = 1_000)) }.isFailure)
        val before = preferences.all
        val failed = LocalDrivingModeHistory(contextFor(app, FirstCommitFails(preferences)))
        assertTrue(runCatching { failed.save(record.copy(mode = DrivingMode.ECO, confirmedAtMillis = 3_000)) }.isFailure)
        assertEquals(before, preferences.all)
        assertEquals(record, withTimeout(2_000) { history.observe().first().getOrThrow() })
    } }

    private fun withStore(test: (Context, SharedPreferences) -> Unit) {
        val app = context.applicationContext
        val name = "vehicle_automation_test_${UUID.randomUUID()}"
        val preferences = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        try { test(contextFor(app, preferences), preferences) }
        finally { assertTrue(app.deleteSharedPreferences(name)) }
    }

    private fun contextFor(app: Context, preferences: SharedPreferences): Context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(name in setOf(LocalVehicleAutomationPreferences.STORE, LocalDrivingModeHistory.STORE) && mode == Context.MODE_PRIVATE)
            return preferences
        }
    }

    /** Reproduces Android updating memory before reporting that disk commit failed. */
    private class FirstCommitFails(private val preferences: SharedPreferences) : SharedPreferences by preferences {
        private var failNext = true
        override fun edit(): SharedPreferences.Editor {
            val delegate = preferences.edit()
            return object : SharedPreferences.Editor by delegate {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { delegate.putString(key, value) }
                override fun remove(key: String?): SharedPreferences.Editor = apply { delegate.remove(key) }
                override fun commit(): Boolean {
                    val result = delegate.commit()
                    if (!failNext) return result
                    failNext = false
                    return false
                }
            }
        }
    }
}
