package org.openvoyah.controls.data.rules

import android.content.Context
import android.test.AndroidTestCase
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.domain.rules.ConditionalRule
import org.openvoyah.controls.domain.rules.ConditionalRuleLibrary
import org.openvoyah.controls.domain.rules.DoorRequirement
import org.openvoyah.controls.domain.rules.RuleComparison
import org.openvoyah.controls.domain.rules.RuleConditions
import org.openvoyah.controls.domain.rules.RuleThreshold
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.routines.RoutinePlan
import org.openvoyah.controls.domain.routines.RoutineTarget
import org.openvoyah.controls.domain.routines.RoutineTimeWindow
import org.openvoyah.controls.domain.vehicle.automation.DrivingMode
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear

class ConditionalRulesPreferencesTest : AndroidTestCase() {
    private fun example() = ConditionalRule("00000000-0000-0000-0000-000000000001", "When parked",
        NamedRoutine("00000000-0000-0000-0000-000000000002", "Park desktop", RoutinePlan(RoutineTarget.SETTINGS)),
        VehicleGear.PARK, DoorRequirement.ALL_CLOSED, enabled = true, cooldownSeconds = 60)

    fun testRulesAndSelectedRoutineReceiptSurviveReopenAndExportImport() = runBlocking {
        val raw = context.getSharedPreferences(ConditionalRulesPreferences.STORE, Context.MODE_PRIVATE)
        val original = raw.getString("settings", null)
        assertTrue(raw.edit().remove("settings").commit())
        val store = ConditionalRulesPreferences(context)
        val reports = Channel<Result<ConditionalRuleLibrary>>(Channel.UNLIMITED)
        val reader = launch { store.observe().collect { reports.send(it) } }
        try {
            assertEquals(ConditionalRuleLibrary(), withTimeout(5_000) { reports.receive() }.getOrThrow())
            val library = ConditionalRuleLibrary(listOf(example()))
            store.update { library }
            assertEquals(library, withTimeout(5_000) { reports.receive() }.getOrThrow())
            val reopened = ConditionalRulesPreferences(context)
            assertEquals(library, reopened.current())
            val archive = reopened.exportSettings()
            assertEquals(1, archive.getInt("schema"))
            assertEquals("park", archive.getJSONArray("entries").getJSONObject(0).getString("gear"))
            assertEquals("all_closed", archive.getJSONArray("entries").getJSONObject(0).getString("doors"))
            store.update { ConditionalRuleLibrary() }
            withTimeout(5_000) { reports.receive() }
            reopened.restoreSettings(ConditionalRulesPreferences.validateSettings(archive))
            assertEquals(library, withTimeout(5_000) { reports.receive() }.getOrThrow())
            expectInvalid { reopened.restoreSettings(JSONObject()) }
            assertEquals(library, reopened.current())
        } finally {
            reader.cancelAndJoin()
            assertTrue(raw.edit().putString("settings", original).commit())
        }
    }

    fun testInvalidImportsNeverAlterSavedRules() = runBlocking {
        val raw = context.getSharedPreferences(ConditionalRulesPreferences.STORE, Context.MODE_PRIVATE)
        val original = raw.getString("settings", null)
        assertTrue(raw.edit().remove("settings").commit())
        val store = ConditionalRulesPreferences(context)
        try {
            store.update { ConditionalRuleLibrary(listOf(example())) }
            val exported = store.exportSettings()
            val recorded = raw.getString("settings", null)
            val malformed = listOf(
                JSONObject().put("entries", JSONArray()),
                JSONObject(exported.toString()).put("schema", "1"),
                JSONObject(exported.toString()).put("unexpected", true),
                JSONObject(exported.toString()).apply { getJSONArray("entries").getJSONObject(0).put("gear", "guessed-ordinal") },
                JSONObject(exported.toString()).apply { getJSONArray("entries").getJSONObject(0).put("enabled", "true") },
                JSONObject(exported.toString()).apply { getJSONArray("entries").getJSONObject(0).put("cooldownSeconds", 0) },
                JSONObject(exported.toString()).apply {
                    getJSONArray("entries").getJSONObject(0).put("gear", JSONObject.NULL).put("doors", JSONObject.NULL)
                },
                JSONObject(exported.toString()).apply { getJSONArray("entries").put(getJSONArray("entries").getJSONObject(0)) },
            )
            malformed.forEach { data ->
                expectInvalid { store.restoreSettings(data) }
                assertEquals(recorded, raw.getString("settings", null))
            }
        } finally {
            assertTrue(raw.edit().putString("settings", original).commit())
        }
    }

    fun testExtendedConditionsUseValidatedPortableValues() = runBlocking {
        val raw = context.getSharedPreferences(ConditionalRulesPreferences.STORE, Context.MODE_PRIVATE)
        val original = raw.getString("settings", null)
        val store = ConditionalRulesPreferences(context)
        try {
            val conditions = RuleConditions(RuleThreshold(200, RuleComparison.AT_MOST),
                RuleThreshold(30, RuleComparison.AT_LEAST), RuleThreshold(-5, RuleComparison.AT_MOST),
                DrivingMode.ECO, RoutineTimeWindow(1_260, 300))
            val extended = ConditionalRuleLibrary(listOf(example().copy(gear = null, doors = null, conditions = conditions)))
            check(raw.edit().remove("settings").commit())
            store.update { extended }
            val archive = store.exportSettings()
            store.update { ConditionalRuleLibrary() }
            store.restoreSettings(ConditionalRulesPreferences.validateSettings(archive))
            assertEquals(extended, store.current())
            val invalid = JSONObject(archive.toString())
            invalid.getJSONArray("entries").getJSONObject(0).getJSONObject("conditions").getJSONObject("chargePercent").put("amount", 103)
            expectInvalid { store.restoreSettings(invalid) }
            assertEquals(extended, store.current())
        } finally {
            check(raw.edit().putString("settings", original).commit())
        }
    }

    fun testDamagedStoreIsPreservedAndAFullValidatedBackupCanRecoverIt() = runBlocking {
        val raw = context.getSharedPreferences(ConditionalRulesPreferences.STORE, Context.MODE_PRIVATE)
        val original = raw.getString("settings", null)
        assertTrue(raw.edit().remove("settings").commit())
        val store = ConditionalRulesPreferences(context)
        try {
            val library = ConditionalRuleLibrary(listOf(example()))
            store.update { library }
            val archive = store.exportSettings()
            assertTrue(raw.edit().putString("settings", "{ damaged data").commit())
            expectInvalid { store.exportSettings() }
            expectInvalid { store.update { ConditionalRuleLibrary() } }
            assertEquals("{ damaged data", raw.getString("settings", null))
            expectInvalid { store.restoreSettings(JSONObject()) }
            assertEquals("{ damaged data", raw.getString("settings", null))
            store.restoreSettings(archive)
            assertEquals(library, store.current())
            assertTrue(raw.edit().putBoolean("settings", true).commit())
            expectInvalid { store.update { ConditionalRuleLibrary() } }
            assertTrue(raw.getBoolean("settings", false))
            store.restoreSettings(archive)
            assertEquals(library, store.current())
        } finally {
            assertTrue(raw.edit().putString("settings", original).commit())
        }
    }

    private suspend fun expectInvalid(action: suspend () -> Unit) {
        try {
            action()
            fail("Invalid rules were accepted")
        } catch (expected: Exception) {
            assertNotNull(expected)
        }
    }
}
