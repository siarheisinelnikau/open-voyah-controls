package org.openvoyah.controls.data.vehicle.automation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.AndroidTestCase
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.openvoyah.controls.data.climate.LocalSeasonalClimatePreferences
import org.openvoyah.controls.data.routines.RoutinePreferences
import org.openvoyah.controls.data.rules.ConditionalRulesPreferences
import org.openvoyah.controls.data.settings.LocalSettingsArchive
import org.openvoyah.controls.domain.rules.ConditionalRule
import org.openvoyah.controls.domain.rules.ConditionalRuleLibrary
import org.openvoyah.controls.domain.rules.DoorRequirement
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.routines.RoutineLibrary
import org.openvoyah.controls.domain.routines.RoutinePlan
import org.openvoyah.controls.domain.routines.RoutineTarget
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear

/** Full-archive behavior is exercised independently of any OEM transport or vehicle. */
class VehicleWorkflowArchiveTest : AndroidTestCase() {
    private val sections = listOf("vehicleAutomation", "seasonalClimate", "conditionalRules")
    private val newStores = listOf(LocalVehicleAutomationPreferences.STORE, LocalSeasonalClimatePreferences.STORE,
        ConditionalRulesPreferences.STORE)

    fun testAllThreeWorkflowSectionsRoundTripTogether() = runBlocking {
        val app = context.applicationContext
        val archive = LocalSettingsArchive(app)
        val original = archive.prepare(archive.exportJson())
        try {
            configureFixture(app)
            val exported = JSONObject(archive.exportJson())
            sections.forEach { assertEquals(1, exported.getJSONObject(it).getInt("schema")) }
            assertEquals("low_beam", exported.getJSONObject("vehicleAutomation").getJSONObject("headlights").getString("onDrive"))
            assertEquals(27, exported.getJSONObject("seasonalClimate").getJSONObject("summer").getInt("outsideThresholdCelsius"))
            assertEquals("Park desktop", exported.getJSONObject("conditionalRules").getJSONArray("entries")
                .getJSONObject(0).getJSONObject("routine").getString("name"))
            val changed = changedWorkflows(exported)
            archive.apply(archive.prepare(changed.toString()))
            assertFalse(workflowSections(exported) == workflowSections(JSONObject(archive.exportJson())))
            val reopened = LocalSettingsArchive(app)
            reopened.apply(reopened.prepare(exported.toString()))
            assertEquals(workflowSections(exported), workflowSections(JSONObject(reopened.exportJson())))
            assertFalse(journal(app).exists())
        } finally {
            archive.recoverInterruptedRestore()
            archive.apply(original)
        }
    }

    fun testOlderArchivesWithoutWorkflowSectionsPreserveAllCurrentWorkflowChoices() = runBlocking {
        val app = context.applicationContext
        val archive = LocalSettingsArchive(app)
        val original = archive.prepare(archive.exportJson())
        try {
            configureFixture(app)
            val exported = JSONObject(archive.exportJson())
            val older = JSONObject(exported.toString()).apply { sections.forEach { remove(it) } }
            archive.apply(archive.prepare(changedWorkflows(exported).toString()))
            val before = snapshot(app, newStores)
            val expected = workflowSections(JSONObject(archive.exportJson()))
            assertTrue(archive.prepare(older.toString()).complete)
            archive.apply(archive.prepare(older.toString()))
            assertEquals(before, snapshot(app, newStores))
            assertEquals(expected, workflowSections(JSONObject(archive.exportJson())))
        } finally {
            archive.recoverInterruptedRestore()
            archive.apply(original)
        }
    }

    fun testAnyMalformedWorkflowSectionRejectsTheEntireImportBeforeWritingOtherStores() = runBlocking {
        val app = context.applicationContext
        val archive = LocalSettingsArchive(app)
        val original = archive.prepare(archive.exportJson())
        try {
            configureFixture(app)
            val before = snapshot(app, journalStores)
            val valid = changedWorkflows(JSONObject(archive.exportJson())).apply {
                getJSONObject("buttons").put("holdMs", if (getJSONObject("buttons").getInt("holdMs") == 800) 900 else 800)
            }
            val invalid = listOf<(JSONObject) -> Unit>(
                { it.getJSONObject("vehicleAutomation").getJSONObject("headlights").put("onDrive", "off") },
                { it.getJSONObject("seasonalClimate").getJSONObject("summer").put("driverCelsius", 99) },
                { it.getJSONObject("conditionalRules").getJSONArray("entries").getJSONObject(0).put("enabled", "true") },
            )
            invalid.forEach { damage ->
                val incoming = JSONObject(valid.toString()).also(damage)
                try {
                    archive.apply(archive.prepare(incoming.toString()))
                    fail("A malformed workflow section must abort before any settings write")
                } catch (expected: Exception) {
                    assertNotNull(expected)
                }
                assertEquals(before, snapshot(app, journalStores))
                assertFalse(journal(app).exists())
            }
        } finally {
            archive.recoverInterruptedRestore()
            archive.apply(original)
        }
    }

    fun testInterruptedFullImportJournalRestoresEveryWorkflowStoreAfterRecreation() = runBlocking {
        val app = context.applicationContext
        val archive = LocalSettingsArchive(app)
        val original = archive.prepare(archive.exportJson())
        try {
            configureFixture(app)
            val before = snapshot(app, journalStores)
            val incoming = changedWorkflows(JSONObject(archive.exportJson())).apply {
                getJSONObject("buttons").put("holdMs", if (getJSONObject("buttons").getInt("holdMs") == 800) 900 else 800)
            }
            val interrupted = AtomicBoolean()
            val failing = LocalSettingsArchive(InterceptCommitContext(app, ConditionalRulesPreferences.STORE) { commit ->
                check(commit())
                interrupted.set(true)
                // A process loss bypasses ordinary Exception recovery. Leave the real durable journal intact.
                throw SimulatedInterruption()
            })
            try {
                failing.apply(failing.prepare(incoming.toString()))
                fail("The fixture must interrupt after committing the final workflow store")
            } catch (_: SimulatedInterruption) {
                assertTrue(interrupted.get())
            }
            assertTrue(journal(app).exists())
            val durable = JSONObject(journal(app).readText(Charsets.UTF_8))
            newStores.forEach { assertTrue("Missing workflow rollback record: $it", durable.has(it)) }
            assertFalse(before == snapshot(app, journalStores))
            LocalSettingsArchive(app).recoverInterruptedRestore()
            assertEquals(before, snapshot(app, journalStores))
            assertFalse(journal(app).exists())
        } finally {
            archive.recoverInterruptedRestore()
            archive.apply(original)
        }
    }

    private suspend fun configureFixture(app: Context) {
        LocalVehicleAutomationPreferences(app).restoreSettings(JSONObject("""{
            "schema":1,"headlights":{"enabled":true,"onDrive":"low_beam","onPark":"automatic"},
            "drivingMode":{"enabled":true}
        }"""))
        val climates = LocalSeasonalClimatePreferences(app)
        climates.restoreSettings(climates.exportSettings().apply {
            getJSONObject("summer").put("enabled", true).put("outsideThresholdCelsius", 27).put("driverCelsius", 20)
                .put("passengerCelsius", 21).put("fanLevel", 5)
            getJSONObject("winter").put("enabled", true).put("outsideThresholdCelsius", -4)
        })
        val routine = NamedRoutine("12345678-1234-4234-9234-123456789abc", "Park desktop", RoutinePlan(RoutineTarget.APP_GRID))
        RoutinePreferences(app).update { RoutineLibrary(listOf(routine)) }
        ConditionalRulesPreferences(app).update { ConditionalRuleLibrary(listOf(ConditionalRule(
            "23456789-1234-4234-9234-123456789abc", "After parking", routine, VehicleGear.PARK,
            DoorRequirement.ALL_CLOSED, enabled = true, cooldownSeconds = 60,
        ))) }
    }

    private fun changedWorkflows(source: JSONObject) = JSONObject(source.toString()).apply {
        getJSONObject("vehicleAutomation").getJSONObject("headlights").put("enabled", false).put("onDrive", "automatic")
        getJSONObject("vehicleAutomation").getJSONObject("drivingMode").put("enabled", false)
        getJSONObject("seasonalClimate").getJSONObject("summer").put("enabled", false).put("driverCelsius", 23).put("fanLevel", 3)
        getJSONObject("seasonalClimate").getJSONObject("winter").put("enabled", false)
        getJSONObject("conditionalRules").getJSONArray("entries").getJSONObject(0).put("name", "Changed parked rule").put("enabled", false)
    }

    private fun workflowSections(source: JSONObject): Map<String, String> = sections.associateWith { source.getJSONObject(it).toString() }
    private fun snapshot(app: Context, names: List<String>): Map<String, Map<String, *>> =
        names.associateWith { app.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
    private fun journal(app: Context) = File(app.filesDir, "settings-restore-rollback.json")
    private val journalStores get() = listOf("controls", "voice", "localization", "ui", "display", "routines") + newStores
    private class SimulatedInterruption : Error("Simulated process interruption after a settings commit")

    private class InterceptCommitContext(
        base: Context,
        private val store: String,
        private val firstCommit: (() -> Boolean) -> Boolean,
    ) : ContextWrapper(base) {
        private val intercepted = AtomicBoolean()
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val actual = super.getSharedPreferences(name, mode)
            if (name != store) return actual
            return object : SharedPreferences by actual {
                override fun edit(): SharedPreferences.Editor {
                    val editor = actual.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }
                        override fun commit(): Boolean =
                            if (intercepted.compareAndSet(false, true)) firstCommit(editor::commit) else editor.commit()
                    }
                }
            }
        }
    }
}
