package org.openvoyah.controls.data.routines

import android.test.AndroidTestCase
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.domain.routines.RoutineLibrary
import org.openvoyah.controls.domain.routines.RoutineOrder
import org.openvoyah.controls.domain.routines.RoutinePlan
import org.openvoyah.controls.domain.routines.RoutineTarget
import org.openvoyah.controls.domain.settings.AppPalette

class RoutinePreferencesTest : AndroidTestCase() {
    private val first = "10000000-0000-4000-8000-000000000001"
    private val other = "20000000-0000-4000-8000-000000000002"
    private fun entry(id: String = first, name: String = "Morning") = JSONObject().put("id", id).put("name", name)
        .put("target", "favorites2").put("palette", "day")
    private fun full() = JSONObject().put("entries", JSONArray().put(entry())).put("sort", "name").put("startup", first)
        .put("startupDays", JSONArray((1..7).toList())).put("startupWindow", JSONObject.NULL).put("pinnedIds", JSONArray())
        .put("startupFrequency", "every").put("startupDelaySeconds", 0).put("startupEnabled", true)

    fun testPartialRestoresAndConcurrentEditsPreserveIndependentIdentityAndOrder() {
        val raw = context.getSharedPreferences(RoutinePreferences.STORE, 0)
        val previous = raw.getString("settings", null)
        val store = RoutinePreferences(context)
        try {
            store.restoreSettings(full())
            assertEquals(RoutinePlan(RoutineTarget.FAVORITES_TWO, AppPalette.DAY), RoutinePreferences(context).startupPlan())
            store.restoreSettings(JSONObject())
            assertEquals(first, store.exportSettings().getString("startup"))
            store.restoreSettings(JSONObject().put("sort", "newest"))
            store.restoreSettings(JSONObject().put("entries", JSONArray().put(entry(name = "New name"))))
            assertEquals(first, store.exportSettings().getString("startup"))
            store.restoreSettings(JSONObject().put("entries", JSONArray().put(entry(other, "Other"))))
            assertTrue(store.exportSettings().isNull("startup"))
            assertEquals("newest", store.exportSettings().getString("sort"))
            assertNull(store.startupPlan())
            val before = store.exportSettings().toString()
            assertTrue(runCatching { store.restoreSettings(JSONObject().put("startup", first)) }.isFailure)
            assertEquals(before, store.exportSettings().toString())
            runBlocking {
                (1..4).map { i -> async(Dispatchers.IO) {
                    RoutinePreferences(context).update {
                        it.save(null, UUID.randomUUID().toString(), "New $i", RoutineTarget.SETTINGS, null)
                    }
                } }.awaitAll()
                val latest = store.observe().first().getOrThrow()
                assertEquals(5, latest.entries.size)
                assertEquals(RoutineOrder.NEWEST, latest.order)
            }
        } finally {
            check(raw.edit().putString("settings", previous).commit())
        }
    }

    fun testInvalidPortableEntriesNeverBecomeRunnable() {
        fun range(start: Any, end: Any) = JSONObject().put("startMinute", start).put("endMinute", end)
        val invalid = listOf(
            full().put("startup", other), full().put("sort", 1), full().put("startup", false),
            full().put("unexpected", true), full().put("entries", JSONArray().put(entry()).put(entry(name = "Duplicate ID"))),
            full().put("entries", JSONArray().put(entry()).put(entry(other, "MORNING"))),
            full().put("entries", JSONArray().put(entry().put("target", "package"))),
            full().put("entries", JSONArray().put(entry().put("palette", false))),
            full().put("entries", JSONArray().put(entry().put("installedApp", false))),
            full().put("entries", JSONArray().put(entry().put("installedApp", "org.example/.Main"))),
            full().put("entries", JSONArray().put(entry().put("installedApp", "org.openvoyah.controls/org.openvoyah.controls.MainActivity"))),
            full().put("entries", JSONArray().put(entry().put("desktopLayoutId", 1))),
            full().put("entries", JSONArray().put(entry().put("desktopLayoutId", "Morning layout"))),
            full().put("entries", JSONArray().put(entry().put("target", "current").apply { remove("palette") })),
            full().put("entries", JSONArray().put(entry().put("extra", "run"))),
            full().put("entries", JSONArray().put(entry().put("name", "x".repeat(41)))),
            full().put("entries", JSONArray().apply { repeat(RoutineLibrary.MAX_ROUTINES + 1) { put(entry()) } }),
            full().put("startupDays", JSONArray()), full().put("startupDays", JSONArray(listOf(1, 1))),
            full().put("startupDays", JSONArray(listOf("1"))), full().put("startupDays", JSONArray(listOf(8))),
            full().put("startupWindow", range(600, 600)), full().put("startupWindow", range(-1, 20)),
            full().put("startupWindow", range(600, 1440)), full().put("startupWindow", range("420", 600)),
            full().put("startupWindow", range(420, 600).put("extra", true)), full().put("pinnedIds", JSONArray(listOf(other))),
            full().put("pinnedIds", JSONArray(listOf(first, first))), full().put("pinnedIds", JSONArray(listOf(1))),
            full().put("startupFrequency", true), full().put("startupFrequency", "weekly"), full().put("startupDelaySeconds", "3"),
            full().put("startupDelaySeconds", 1), full().put("startupDelaySeconds", 3.0), full().put("startupHistory", JSONObject()),
            full().put("startupEnabled", "false"), full().put("startupEnabled", 0), full().put("startupEnabled", JSONObject.NULL),
        )
        invalid.forEach { assertTrue(it.toString(), runCatching { RoutinePreferences.validateSettings(it) }.isFailure) }
        val raw = context.getSharedPreferences(RoutinePreferences.STORE, 0)
        val previous = raw.getString("settings", null)
        try {
            check(raw.edit().putString("settings", "{broken").commit())
            val store = RoutinePreferences(context)
            assertNull(store.startupPlan())
            assertTrue(runBlocking { store.observe().first().isFailure })
            store.restoreSettings(full())
            assertEquals(RoutineTarget.FAVORITES_TWO, store.startupPlan()?.target)
            store.restoreSettings(JSONObject().put("startup", JSONObject.NULL))
            assertNull(store.startupPlan())
        } finally {
            check(raw.edit().putString("settings", previous).commit())
        }
    }

    fun testScheduleAndPinsMergeLegacyBackupsByStableIdAndCanBeCleared() {
        val raw = context.getSharedPreferences(RoutinePreferences.STORE, 0)
        val previous = raw.getString("settings", null)
        val store = RoutinePreferences(context)
        val monday = LocalDateTime.of(2026, 11, 16, 2, 30)
        try {
            val legacy = full().apply {
                listOf("startupDays", "startupWindow", "pinnedIds", "startupFrequency", "startupDelaySeconds").forEach(::remove)
            }
            check(raw.edit().remove("settings").commit())
            store.restoreSettings(legacy)
            assertEquals(7, store.exportSettings().getJSONArray("startupDays").length())
            assertTrue(store.exportSettings().isNull("startupWindow"))
            assertEquals(0, store.exportSettings().getJSONArray("pinnedIds").length())
            store.restoreSettings(JSONObject().put("pinnedIds", JSONArray(listOf(first))).put("startupDays", JSONArray(listOf(7)))
                .put("startupWindow", JSONObject().put("startMinute", 22 * 60).put("endMinute", 6 * 60)))

            store.restoreSettings(legacy.put("entries", JSONArray().put(entry(name = "Renamed"))))
            val exported = store.exportSettings()
            assertEquals(JSONArray(listOf(first)).toString(), exported.getJSONArray("pinnedIds").toString())
            assertEquals(JSONArray(listOf(7)).toString(), exported.getJSONArray("startupDays").toString())
            assertEquals(360, exported.getJSONObject("startupWindow").getInt("endMinute"))
            assertNotNull(RoutinePreferences(context).startupPlan(monday))
            assertNull(store.startupPlan(monday.withHour(6)))
            val snapshot = raw.getString("settings", null)
            assertNull(store.startupPlan(monday.plusDays(1)))
            assertEquals("Evaluating a launcher entry must be read-only", snapshot, raw.getString("settings", null))

            store.restoreSettings(JSONObject().put("startupWindow", JSONObject.NULL))
            assertNull(store.startupPlan(monday))
            assertNotNull(store.startupPlan(monday.minusDays(1)))
            val invalid = runCatching { store.restoreSettings(JSONObject().put("pinnedIds", JSONArray(listOf(other)))) }
            assertTrue(invalid.isFailure)
            store.restoreSettings(JSONObject().put("entries", JSONArray().put(entry(other))))
            assertTrue(store.exportSettings().isNull("startup"))
            assertEquals(0, store.exportSettings().getJSONArray("pinnedIds").length())
            store.restoreSettings(exported)
            assertEquals(exported.toString(), store.exportSettings().toString())
            store.restoreSettings(JSONObject().put("pinnedIds", JSONArray()))
            assertEquals(0, store.exportSettings().getJSONArray("pinnedIds").length())

            check(raw.edit().putString("settings", "broken").commit())
            store.restoreSettings(legacy)
            assertNotNull(store.startupPlan(monday))
            assertEquals(7, store.exportSettings().getJSONArray("startupDays").length())
        } finally {
            check(raw.edit().putString("settings", previous).commit())
        }
    }

    fun testStartupFrequencyAndDelayArePortableWhileCompletedDaysStayLocal() {
        val raw = context.getSharedPreferences(RoutinePreferences.STORE, 0)
        val history = context.getSharedPreferences(RoutinePreferences.HISTORY_STORE, 0)
        val previous = raw.getString("settings", null)
        val previousDays = history.all
        val at = LocalDateTime.of(2026, 11, 16, 9, 0)
        val store = RoutinePreferences(context)
        try {
            check(history.edit().clear().commit())
            val legacy = full().apply { remove("startupFrequency"); remove("startupDelaySeconds") }
            check(raw.edit().remove("settings").commit())
            store.restoreSettings(legacy)
            assertEquals("every", store.exportSettings().getString("startupFrequency"))
            assertEquals(0, store.exportSettings().getInt("startupDelaySeconds"))
            store.restoreSettings(JSONObject().put("startupFrequency", "daily").put("startupDelaySeconds", 8))
            store.restoreSettings(legacy)
            assertEquals("daily", store.exportSettings().getString("startupFrequency"))
            assertEquals(8, store.exportSettings().getInt("startupDelaySeconds"))
            val pending = checkNotNull(store.startupRequest(at))
            assertTrue(runCatching { store.runStartup(pending, at) { error("Did not apply") } }.isFailure)
            assertTrue(history.all.isEmpty())
            assertEquals(pending.routine.plan, store.planFor(pending.routine))
            assertTrue(history.all.isEmpty())
            store.restoreSettings(JSONObject().put("startupDelaySeconds", 3))
            assertFalse(store.runStartup(pending, at) { fail("Stale delayed selection") })
            val expected = checkNotNull(store.startupRequest(at))
            val archive = store.exportSettings().toString()
            var calls = 0
            runBlocking { (1..3).map { async(Dispatchers.IO) { RoutinePreferences(context).runStartup(expected, at) { calls++ } } }.awaitAll() }
            assertEquals(1, calls)
            assertFalse(history.all.isEmpty())
            assertEquals(archive, store.exportSettings().toString())
            store.restoreSettings(JSONObject(archive))
            assertNull(RoutinePreferences(context).startupRequest(at.plusHours(1)))
            assertNotNull(store.startupRequest(at.plusDays(1)))
            assertTrue(store.runStartup(expected, at.minusDays(1)) { })
            assertNull(store.startupRequest(at.minusDays(1)))
            assertNull(store.startupRequest(at))
            store.restoreSettings(JSONObject().put("entries", JSONArray().put(entry(other, "Replacement"))).put("startup", other))
            assertTrue(store.runStartup(checkNotNull(store.startupRequest(at)), at) { })
            store.restoreSettings(JSONObject(archive))
            assertNull(store.startupRequest(at))
            store.restoreSettings(JSONObject().put("startupFrequency", "every"))
            assertNotNull(store.startupRequest(at))
        } finally {
            check(raw.edit().putString("settings", previous).commit())
            val restore = history.edit().clear()
            previousDays.forEach { (id, day) -> restore.putLong(id, day as Long) }
            check(restore.commit())
        }
    }

    fun testStartupPauseIsPortableAndRejectsPendingLaunches() {
        val raw = context.getSharedPreferences(RoutinePreferences.STORE, 0)
        val history = context.getSharedPreferences(RoutinePreferences.HISTORY_STORE, 0)
        val previous = raw.getString("settings", null)
        val store = RoutinePreferences(context)
        val at = LocalDateTime.of(2026, 11, 16, 9, 0)
        val legacy = full().put("startupDays", JSONArray(listOf(1)))
            .put("startupWindow", JSONObject().put("startMinute", 420).put("endMinute", 600))
            .put("startupDelaySeconds", 8).apply { remove("startupEnabled") }
        try {
            check(raw.edit().putString("settings", legacy.toString()).commit())
            val pending = checkNotNull(store.startupRequest(at))
            assertTrue(store.exportSettings().getBoolean("startupEnabled"))
            val beforeHistory = history.all
            runBlocking { store.update { it.setStartupEnabled(first, false) } }

            val reloaded = RoutinePreferences(context)
            val paused = reloaded.exportSettings()
            assertFalse(paused.getBoolean("startupEnabled"))
            assertEquals(first, paused.getString("startup"))
            assertEquals(pending.routine.plan, reloaded.planFor(pending.routine))
            assertNull(reloaded.startupRequest(at))
            assertFalse(reloaded.runStartup(pending, at) { fail("Paused startup was applied") })
            assertEquals(beforeHistory, history.all)

            store.restoreSettings(legacy)
            assertFalse(store.exportSettings().getBoolean("startupEnabled"))
            store.restoreSettings(JSONObject().put("startupEnabled", true))
            assertEquals(pending, RoutinePreferences(context).startupRequest(at))
            store.restoreSettings(RoutinePreferences.validateSettings(paused))
            assertFalse(store.exportSettings().getBoolean("startupEnabled"))
            assertNull(store.startupRequest(at))
        } finally {
            check(raw.edit().putString("settings", previous).commit())
        }
    }

    fun testInstalledAppIsIndependentAndPortableWithLegacyImports() {
        val raw = context.getSharedPreferences(RoutinePreferences.STORE, 0)
        val history = context.getSharedPreferences(RoutinePreferences.HISTORY_STORE, 0)
        val previous = raw.getString("settings", null)
        val beforeHistory = history.all
        val store = RoutinePreferences(context)
        val app = "org.example.navigation/org.example.navigation.MainActivity"
        val replacement = "org.example.navigation/org.example.navigation.AlternateActivity"
        val at = LocalDateTime.of(2026, 11, 16, 9, 0)
        try {
            store.restoreSettings(full().put("entries", JSONArray().put(entry().put("target", "current")
                .put("installedApp", app).apply { remove("palette") })))
            val pending = checkNotNull(store.startupRequest(at))
            assertEquals(RoutinePlan(RoutineTarget.CURRENT, installedApp = app), pending.routine.plan)
            val backup = RoutinePreferences.validateSettings(store.exportSettings())
            assertEquals(app, backup.getJSONArray("entries").getJSONObject(0).getString("installedApp"))

            store.restoreSettings(RoutinePreferences.validateSettings(full()))
            assertEquals(RoutinePlan(RoutineTarget.FAVORITES_TWO, AppPalette.DAY, app), RoutinePreferences(context).startupPlan(at))
            store.restoreSettings(JSONObject().put("entries", JSONArray().put(entry().put("installedApp", replacement))))
            assertFalse(store.runStartup(pending, at) { fail("Stale app was accepted") })
            assertEquals(beforeHistory, history.all)
            assertEquals(replacement, store.startupPlan(at)?.installedApp)

            store.restoreSettings(JSONObject().put("entries", JSONArray().put(entry().put("installedApp", JSONObject.NULL))))
            val cleared = store.exportSettings().getJSONArray("entries").getJSONObject(0)
            assertTrue(cleared.has("installedApp") && cleared.isNull("installedApp"))
            assertNull(store.startupPlan(at)?.installedApp)
            store.restoreSettings(backup)
            assertEquals(pending.routine.plan, RoutinePreferences(context).startupPlan(at))
        } finally {
            check(raw.edit().putString("settings", previous).commit())
        }
    }

    fun testDesktopLayoutByItselfCanRunAndItsOptionalFieldMergesIndependently() {
        val raw = context.getSharedPreferences(RoutinePreferences.STORE, 0)
        val previous = raw.getString("settings", null)
        val store = RoutinePreferences(context)
        val at = LocalDateTime.of(2026, 11, 16, 9, 0)
        val layout = "30000000-0000-4000-8000-000000000003"
        val app = "org.example.navigation/org.example.navigation.MainActivity"
        try {
            store.restoreSettings(full().put("entries", JSONArray().put(entry().put("target", "current")
                .put("desktopLayoutId", layout).apply { remove("palette") })))
            val backup = RoutinePreferences.validateSettings(store.exportSettings())
            assertEquals(RoutinePlan(RoutineTarget.CURRENT, desktopLayoutId = layout), store.startupPlan(at))

            store.restoreSettings(RoutinePreferences.validateSettings(full().put("entries",
                JSONArray().put(entry(name = "Old routine").put("installedApp", app)))))
            val changed = checkNotNull(store.startupPlan(at))
            assertEquals(RoutinePlan(RoutineTarget.FAVORITES_TWO, AppPalette.DAY, app, layout), changed)
            store.restoreSettings(JSONObject().put("entries",
                JSONArray().put(entry().put("desktopLayoutId", JSONObject.NULL))))
            assertEquals(changed.copy(desktopLayoutId = null), store.startupPlan(at))
            assertTrue(store.exportSettings().getJSONArray("entries").getJSONObject(0).isNull("desktopLayoutId"))

            store.restoreSettings(backup)
            assertEquals(RoutinePlan(RoutineTarget.CURRENT, desktopLayoutId = layout), store.startupPlan(at))
        } finally {
            check(raw.edit().putString("settings", previous).commit())
        }
    }
}
