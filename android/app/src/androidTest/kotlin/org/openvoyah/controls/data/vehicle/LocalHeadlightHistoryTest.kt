package org.openvoyah.controls.data.vehicle

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.AndroidTestCase
import java.util.UUID
import org.openvoyah.controls.domain.vehicle.ExteriorLamp.HEADLIGHTS
import org.openvoyah.controls.domain.vehicle.ExteriorLamp.HIGH_BEAM
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.HeadlightObservation
import org.openvoyah.controls.domain.vehicle.HeadlightSource
import org.openvoyah.controls.domain.vehicle.LampActivity
import org.openvoyah.controls.domain.vehicle.LampObservation

class LocalHeadlightHistoryTest : AndroidTestCase() {
    private val selector = HeadlightObservation(HeadlightMode.LOW_BEAM, 100L, HeadlightSource.FACTORY)
    private val headlamps = LampObservation(LampActivity.ON, 200L, HeadlightSource.ANDROID_AUTOMOTIVE)
    private val highBeam = LampObservation(LampActivity.OFF, 300L, HeadlightSource.ANDROID_AUTOMOTIVE)

    fun testReadsLegacyAndMalformedSourcesWithoutLosingValues() = withStore { app, preferences ->
        assertTrue(preferences.edit()
            .putString("confirmed_mode", selector.mode.key).putLong("confirmed_at_millis", selector.confirmedAtMillis)
            .putString("lamp_headlights_activity", headlamps.activity.key)
            .putLong("lamp_headlights_confirmed_at_millis", headlamps.confirmedAtMillis)
            .putString("lamp_high_beam_activity", highBeam.activity.key)
            .putLong("lamp_high_beam_confirmed_at_millis", highBeam.confirmedAtMillis)
            .commit())

        val expectedSelector = selector.copy(source = null)
        val expectedLamps = mapOf(HEADLIGHTS to headlamps.copy(source = null), HIGH_BEAM to highBeam.copy(source = null))
        assertEquals(expectedSelector, history(app, preferences).read())
        assertEquals(expectedLamps, history(app, preferences).readLamps())

        assertTrue(preferences.edit().putString("confirmed_source", "future_source")
            .putLong("lamp_headlights_source", 1L).commit())
        assertEquals(expectedSelector, history(app, preferences).read())
        assertEquals(expectedLamps, history(app, preferences).readLamps())
    }

    fun testPersistsSourcesAndClearsOnlyTheReplacedSource() = withStore { app, preferences ->
        val stored = history(app, preferences)
        assertTrue(stored.save(selector))
        assertTrue(stored.saveLamp(HEADLIGHTS, headlamps))
        assertTrue(stored.saveLamp(HIGH_BEAM, highBeam))
        val reopened = history(app, preferences)
        assertEquals(selector, reopened.read())
        assertEquals(mapOf(HEADLIGHTS to headlamps, HIGH_BEAM to highBeam), reopened.readLamps())

        val nextSelector = HeadlightObservation(HeadlightMode.OFF, 400L)
        val nextHeadlamps = LampObservation(LampActivity.OFF, 500L)
        assertTrue(reopened.save(nextSelector))
        assertTrue(reopened.saveLamp(HEADLIGHTS, nextHeadlamps))
        assertEquals(nextSelector, history(app, preferences).read())
        assertEquals(mapOf(HEADLIGHTS to nextHeadlamps, HIGH_BEAM to highBeam), history(app, preferences).readLamps())
        assertFalse(preferences.contains("confirmed_source"))
        assertFalse(preferences.contains("lamp_headlights_source"))
    }

    fun testFailedCommitRollsBackValueTimeAndSourceTogether() = withStore { app, preferences ->
        val stored = history(app, preferences)
        assertTrue(stored.save(selector))
        assertTrue(stored.saveLamp(HEADLIGHTS, headlamps))
        val expected = preferences.all

        val nextSelector = HeadlightObservation(HeadlightMode.AUTOMATIC, 400L, HeadlightSource.ANDROID_AUTOMOTIVE)
        val nextHeadlamps = LampObservation(LampActivity.OFF, 500L)
        assertFalse(history(app, FirstCommitFails(preferences)).save(nextSelector))
        assertFalse(history(app, FirstCommitFails(preferences)).saveLamp(HEADLIGHTS, nextHeadlamps))
        assertFalse(history(app, FirstCommitFails(preferences)).saveLamp(HIGH_BEAM, highBeam))
        assertEquals(expected, preferences.all)
        assertEquals(selector, stored.read())
        assertEquals(mapOf(HEADLIGHTS to headlamps), stored.readLamps())
    }

    private fun withStore(test: (Context, SharedPreferences) -> Unit) {
        val app = context.applicationContext
        val name = "headlight_history_test_${UUID.randomUUID()}"
        val preferences = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            test(app, preferences)
        } finally {
            assertTrue(app.deleteSharedPreferences(name))
        }
    }

    private fun history(app: Context, preferences: SharedPreferences) = LocalHeadlightHistory(object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(name == LocalHeadlightHistory.STORE && mode == Context.MODE_PRIVATE)
            return preferences
        }
    })

    /** Models a failed commit after the new values have reached the preferences map. */
    private class FirstCommitFails(private val preferences: SharedPreferences) : SharedPreferences by preferences {
        private var failNextCommit = true

        override fun edit(): SharedPreferences.Editor {
            val editor = preferences.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                    apply { editor.putString(key, value) }

                override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                    apply { editor.putLong(key, value) }

                override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }

                override fun commit(): Boolean {
                    val saved = editor.commit()
                    if (!failNextCommit) return saved
                    failNextCommit = false
                    return false
                }
            }
        }
    }
}
