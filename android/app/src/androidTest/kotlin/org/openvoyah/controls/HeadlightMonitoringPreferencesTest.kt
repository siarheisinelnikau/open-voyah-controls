package org.openvoyah.controls

import android.content.Context
import android.test.AndroidTestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.openvoyah.controls.data.vehicle.HeadlightMonitoringPreferences

class HeadlightMonitoringPreferencesTest : AndroidTestCase() {
    fun testChoiceDefaultsOffAndRemainsIndependentOfObservationHistory() {
        val prefs = HeadlightMonitoringPreferences(context)
        val store = context.getSharedPreferences(HeadlightMonitoringPreferences.STORE, Context.MODE_PRIVATE)
        val history = context.getSharedPreferences("headlight_history", Context.MODE_PRIVATE)
        val originalHistory = history.all
        val key = "while_controls_open"
        val before = store.all[key] as? Boolean
        try {
            assertTrue(store.edit().remove(key).commit())
            assertFalse(runBlocking { prefs.observeWhileControlsOpen().first() })
            runBlocking { prefs.setWhileControlsOpen(true) }
            assertTrue(runBlocking { HeadlightMonitoringPreferences(context).observeWhileControlsOpen().first() })

            val exported = prefs.exportSettings()
            assertEquals(1, exported.length())
            assertTrue(exported.getBoolean("whileControlsOpen"))
            prefs.restoreSettings(HeadlightMonitoringPreferences.validateSettings(JSONObject()))
            assertTrue(runBlocking { prefs.observeWhileControlsOpen().first() })
            prefs.restoreSettings(HeadlightMonitoringPreferences.validateSettings(JSONObject().put("whileControlsOpen", false)))
            assertFalse(runBlocking { prefs.observeWhileControlsOpen().first() })
            prefs.restoreSettings(HeadlightMonitoringPreferences.validateSettings(exported))
            assertTrue(runBlocking { prefs.observeWhileControlsOpen().first() })
            assertEquals(originalHistory, history.all)
        } finally {
            val restore = store.edit()
            if (before == null) restore.remove(key) else restore.putBoolean(key, before)
            assertTrue(restore.commit())
        }
    }

    fun testValidationRejectsCoercionsAndObservationFields() {
        for (invalid in listOf(JSONObject().put("whileControlsOpen", "true"),
            JSONObject().put("whileControlsOpen", JSONObject.NULL), JSONObject().put("lastConfirmed", true))) {
            assertTrue(runCatching { HeadlightMonitoringPreferences.validateSettings(invalid) }.isFailure)
        }
    }
}
