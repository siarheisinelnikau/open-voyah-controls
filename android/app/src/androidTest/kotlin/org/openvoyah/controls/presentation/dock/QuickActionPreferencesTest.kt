package org.openvoyah.controls.presentation.dock

import android.content.Context
import android.test.AndroidTestCase
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.data.dock.QuickActionPreferences
import org.openvoyah.controls.data.settings.LocalSettingsArchive
import org.openvoyah.controls.domain.dock.QuickAction
import org.openvoyah.controls.domain.dock.QuickActionArea
import org.openvoyah.controls.domain.dock.QuickActionCompactEdge
import org.openvoyah.controls.domain.dock.QuickActionCompactHandleSide
import org.openvoyah.controls.domain.dock.QuickActionHandleSize
import org.openvoyah.controls.domain.dock.QuickActionHeadlightTap
import org.openvoyah.controls.domain.dock.QuickActionRailAlignment
import org.openvoyah.controls.domain.dock.QuickActionRailWidth
import org.openvoyah.controls.domain.dock.QuickActionSettings
import org.openvoyah.controls.domain.dock.QuickActionSide

class QuickActionPreferencesTest : AndroidTestCase() {
    fun testOrderedActionsSurviveReopenAndPartialRestoreNotifiesActiveReaders() = runBlocking {
        val raw = context.getSharedPreferences(QuickActionPreferences.STORE, Context.MODE_PRIVATE)
        val original = raw.getString("settings", null)
        assertTrue(raw.edit().remove("settings").commit())
        val preferences = QuickActionPreferences(context)
        val observed = Channel<QuickActionSettings>(Channel.UNLIMITED)
        val reader = launch { preferences.observe().collect { observed.send(it) } }
        try {
            val defaults = QuickActionSettings()
            assertEquals(defaults, withTimeout(5_000) { observed.receive() })
            assertEquals(listOf(QuickAction.APPS, QuickAction.BUTTONS, QuickAction.SETTINGS), defaults.actions)
            val changed = defaults.copy(enabled = true, side = QuickActionSide.LEFT, showLabels = false,
                area = QuickActionArea.APPS, actions = listOf(QuickAction.ROUTINES, QuickAction.VOICE_SETTINGS, QuickAction.SEAT_MEMORY),
                collapseAfterUse = true, compactEdge = QuickActionCompactEdge.TOP, highContrastHandle = true, showCompactHandleLabel = false,
                railWidth = QuickActionRailWidth.COMPACT, handleSize = QuickActionHandleSize.LARGE,
                compactHandleSide = QuickActionCompactHandleSide.RIGHT, keepSideInset = true,
                headlightTap = QuickActionHeadlightTap.SOURCES, collapsedHandleAlignment = QuickActionRailAlignment.CENTER)
            preferences.update { changed }
            assertEquals(changed, withTimeout(5_000) { observed.receive() })
            val reopened = QuickActionPreferences(context)
            val exported = reopened.exportSettings()
            assertEquals(listOf("routines", "voiceSettings", "seatMemory"), List(3) { exported.getJSONArray("actions").getString(it) })
            assertEquals("left", exported.getString("side"))
            assertFalse(exported.getBoolean("showLabels"))
            assertTrue(exported.getBoolean("collapseAfterUse"))
            assertEquals("top", exported.getString("compactEdge"))
            assertTrue(exported.getBoolean("highContrastHandle"))
            assertFalse(exported.getBoolean("showCompactHandleLabel"))
            assertEquals("compact", exported.getString("railWidth"))
            assertEquals("large", exported.getString("handleSize"))
            assertEquals("right", exported.getString("compactHandleSide"))
            assertTrue(exported.getBoolean("keepSideInset"))
            assertEquals("sources", exported.getString("headlightTap"))
            assertEquals("center", exported.getString("collapsedHandleAlignment"))
            val partial = QuickActionPreferences.validateSettings(JSONObject().put("enabled", false))
            assertEquals(1, partial.length())
            reopened.restoreSettings(partial)
            assertEquals(changed.copy(enabled = false), withTimeout(5_000) { observed.receive() })
            reopened.restoreSettings(exported)
            assertEquals(changed, withTimeout(5_000) { observed.receive() })
        } finally {
            reader.cancelAndJoin()
            assertTrue(raw.edit().putString("settings", original).commit())
        }
    }

    fun testOldFiveFieldStoreAndArchivePreserveTheExpectedDock() {
        val raw = context.getSharedPreferences(QuickActionPreferences.STORE, Context.MODE_PRIVATE)
        val original = raw.getString("settings", null)
        val legacy = JSONObject().put("enabled", true).put("side", "left").put("showLabels", false)
            .put("area", "apps").put("actions", JSONArray(listOf("buttons", "apps")))
        try {
            assertTrue(raw.edit().putString("settings", legacy.toString()).commit())
            val preferences = QuickActionPreferences(context)
            val upgraded = preferences.exportSettings()
            assertEquals("bottom", upgraded.getString("compactEdge"))
            assertFalse(upgraded.getBoolean("collapseAfterUse"))
            assertEquals("left", upgraded.getString("side"))
            assertFalse(upgraded.getBoolean("highContrastHandle"))
            assertTrue(upgraded.getBoolean("showCompactHandleLabel"))
            assertEquals("standard", upgraded.getString("railWidth"))
            assertEquals("default", upgraded.getString("startState"))
            assertEquals("default", upgraded.getString("compactLabels"))
            assertFalse(upgraded.getBoolean("showHeadlightMode"))
            assertEquals("switch", upgraded.getString("headlightContent"))
            assertEquals("center", upgraded.getString("sideRailAlignment"))
            assertFalse(upgraded.getBoolean("hideOnVirtualDisplay"))
            assertEquals("standard", upgraded.getString("handleSize"))
            assertEquals("default", upgraded.getString("compactHandleSide"))
            assertFalse(upgraded.getBoolean("keepSideInset"))
            assertEquals("vehicle", upgraded.getString("headlightTap"))
            assertEquals("top", upgraded.getString("collapsedHandleAlignment"))
            preferences.restoreSettings(JSONObject().put("compactEdge", "top").put("collapseAfterUse", true)
                .put("highContrastHandle", true).put("showCompactHandleLabel", false)
                .put("railWidth", "compact").put("startState", "expanded").put("compactLabels", "labels")
                .put("showHeadlightMode", true).put("headlightContent", "highBeam")
                .put("handleSize", "large").put("compactHandleSide", "right")
                .put("sideRailAlignment", "bottom").put("hideOnVirtualDisplay", true)
                .put("keepSideInset", true).put("headlightTap", "sources"))
            preferences.restoreSettings(QuickActionPreferences.validateSettings(legacy))
            val merged = preferences.exportSettings()
            assertEquals("top", merged.getString("compactEdge"))
            assertTrue(merged.getBoolean("collapseAfterUse"))
            assertTrue(merged.getBoolean("highContrastHandle"))
            assertFalse(merged.getBoolean("showCompactHandleLabel"))
            assertEquals("compact", merged.getString("railWidth"))
            assertEquals("expanded", merged.getString("startState"))
            assertEquals("labels", merged.getString("compactLabels"))
            assertTrue(merged.getBoolean("showHeadlightMode"))
            assertEquals("highBeam", merged.getString("headlightContent"))
            assertEquals("bottom", merged.getString("sideRailAlignment"))
            assertTrue(merged.getBoolean("hideOnVirtualDisplay"))
            assertEquals("large", merged.getString("handleSize"))
            assertEquals("right", merged.getString("compactHandleSide"))
            assertTrue(merged.getBoolean("keepSideInset"))
            assertEquals("sources", merged.getString("headlightTap"))
            assertEquals("buttons", merged.getJSONArray("actions").getString(0))
        } finally {
            assertTrue(raw.edit().putString("settings", original).commit())
        }
    }

    fun testAppsDestinationRoundTripsInPortableArchiveAndKeepsLegacyOrder() {
        val raw = context.getSharedPreferences(QuickActionPreferences.STORE, Context.MODE_PRIVATE)
        val original = raw.getString("settings", null)
        val dock = QuickActionPreferences(context)
        val archive = LocalSettingsArchive(context)
        val order = JSONArray(listOf("seatMemory", "apps", "routines"))
        try {
            dock.restoreSettings(JSONObject().put("actions", order).put("appsDestination", "allApps"))
            val exported = JSONObject(archive.exportJson())
            val section = exported.getJSONObject("quickActions")
            assertTrue(archive.prepare(exported.toString()).complete)
            assertEquals("allApps", section.getString("appsDestination"))
            assertEquals(order.toString(), section.getJSONArray("actions").toString())

            assertTrue(raw.edit().remove("settings").commit())
            dock.restoreSettings(QuickActionPreferences.validateSettings(section))
            assertEquals("allApps", dock.exportSettings().getString("appsDestination"))
            section.remove("appsDestination")
            assertTrue(archive.prepare(exported.toString()).complete)
            dock.restoreSettings(QuickActionPreferences.validateSettings(section))
            assertEquals("allApps", dock.exportSettings().getString("appsDestination"))

            assertTrue(raw.edit().remove("settings").commit())
            dock.restoreSettings(QuickActionPreferences.validateSettings(section))
            val legacy = dock.exportSettings()
            assertEquals("defaultDesktop", legacy.getString("appsDestination"))
            assertEquals(order.toString(), legacy.getJSONArray("actions").toString())
        } finally { assertTrue(raw.edit().putString("settings", original).commit()) }
    }

    fun testHandleAccentArchivesIndependentlyAndMergesOlderSettings() {
        val raw = context.getSharedPreferences(QuickActionPreferences.STORE, Context.MODE_PRIVATE)
        val original = raw.getString("settings", null)
        val preferences = QuickActionPreferences(context)
        val archive = LocalSettingsArchive(context)
        try {
            assertTrue(raw.edit().remove("settings").commit())
            assertEquals("theme", preferences.exportSettings().getString("handleAccent"))

            preferences.restoreSettings(JSONObject().put("handleAccent", "amber").put("highContrastHandle", false))
            val exported = archive.exportJson()
            val older = JSONObject(exported)
            assertEquals("amber", older.getJSONObject("quickActions").remove("handleAccent"))

            preferences.restoreSettings(JSONObject().put("handleAccent", "teal").put("highContrastHandle", true))
            archive.apply(archive.prepare(older.toString()))
            val reopened = QuickActionPreferences(context)
            assertEquals("teal", reopened.exportSettings().getString("handleAccent"))
            assertFalse(reopened.exportSettings().getBoolean("highContrastHandle"))

            archive.apply(archive.prepare(exported))
            assertEquals("amber", reopened.exportSettings().getString("handleAccent"))
        } finally {
            assertTrue(raw.edit().putString("settings", original).commit())
        }
    }

    fun testCollapsedHandleAlignmentArchivesIndependentlyAndMergesOlderSettings() {
        val raw = context.getSharedPreferences(QuickActionPreferences.STORE, Context.MODE_PRIVATE)
        val original = raw.getString("settings", null)
        val dock = QuickActionPreferences(context)
        val archive = LocalSettingsArchive(context)
        try {
            dock.restoreSettings(JSONObject().put("collapsedHandleAlignment", "bottom").put("sideRailAlignment", "center"))
            val exported = archive.exportJson()
            val older = JSONObject(exported)
            assertEquals("bottom", older.getJSONObject("quickActions").remove("collapsedHandleAlignment"))

            dock.restoreSettings(JSONObject().put("collapsedHandleAlignment", "center").put("sideRailAlignment", "top"))
            archive.apply(archive.prepare(older.toString()))
            val reopened = QuickActionPreferences(context)
            assertEquals("center", reopened.exportSettings().getString("collapsedHandleAlignment"))
            assertEquals("center", reopened.exportSettings().getString("sideRailAlignment"))

            archive.apply(archive.prepare(exported))
            assertEquals("bottom", reopened.exportSettings().getString("collapsedHandleAlignment"))
        } finally {
            assertTrue(raw.edit().putString("settings", original).commit())
        }
    }

    fun testDimIdleHandleArchivesIndependentlyAndMergesOlderSettings() {
        val raw = context.getSharedPreferences(QuickActionPreferences.STORE, Context.MODE_PRIVATE)
        val original = raw.getString("settings", null)
        val dock = QuickActionPreferences(context)
        val archive = LocalSettingsArchive(context)
        try {
            assertTrue(raw.edit().remove("settings").commit())
            assertFalse(dock.exportSettings().getBoolean("dimIdleHandle"))

            dock.restoreSettings(JSONObject().put("dimIdleHandle", true).put("handleAccent", "amber"))
            val exported = archive.exportJson()
            val older = JSONObject(exported)
            assertEquals(true, older.getJSONObject("quickActions").remove("dimIdleHandle"))

            dock.restoreSettings(JSONObject().put("dimIdleHandle", false).put("handleAccent", "teal"))
            archive.apply(archive.prepare(older.toString()))
            val reopened = QuickActionPreferences(context)
            assertFalse(reopened.exportSettings().getBoolean("dimIdleHandle"))
            assertEquals("amber", reopened.exportSettings().getString("handleAccent"))

            archive.apply(archive.prepare(exported))
            assertTrue(reopened.exportSettings().getBoolean("dimIdleHandle"))
        } finally {
            assertTrue(raw.edit().putString("settings", original).commit())
        }
    }

    fun testOlderArchivePreservesRoutineShortcutAndExplicitNullClearsIt() {
        val raw = context.getSharedPreferences(QuickActionPreferences.STORE, Context.MODE_PRIVATE)
        val original = raw.getString("settings", null)
        val dock = QuickActionPreferences(context)
        val archive = LocalSettingsArchive(context)
        val key = "routineShortcutId"
        val id = "3580a7b1-ba3a-40b2-9ca7-930df405ca71"
        try {
            assertTrue(raw.edit().putString("settings", """{"enabled":true}""").commit())
            val upgraded = dock.exportSettings()
            assertTrue(upgraded.getBoolean("enabled"))
            assertTrue(upgraded.has(key) && upgraded.isNull(key))

            val older = JSONObject(archive.exportJson())
            older.getJSONObject("quickActions").remove(key)
            dock.restoreSettings(JSONObject().put(key, id).put("showLabels", false))
            val withShortcut = archive.exportJson()
            archive.apply(archive.prepare(older.toString()))
            val reopened = QuickActionPreferences(context)
            assertEquals(id, reopened.exportSettings().getString(key))
            assertTrue(reopened.exportSettings().getBoolean("showLabels"))

            older.getJSONObject("quickActions").put(key, JSONObject.NULL)
            archive.apply(archive.prepare(older.toString()))
            val cleared = reopened.exportSettings()
            assertTrue(cleared.has(key) && cleared.isNull(key))

            archive.apply(archive.prepare(withShortcut))
            assertEquals(id, reopened.exportSettings().getString(key))
            assertFalse(reopened.exportSettings().getBoolean("showLabels"))
        } finally {
            assertTrue(raw.edit().putString("settings", original).commit())
        }
    }

    fun testLargerShortcutsRoundTripAndMergeOlderArchives() {
        val raw = context.getSharedPreferences(QuickActionPreferences.STORE, Context.MODE_PRIVATE)
        val original = raw.getString("settings", null)
        val dock = QuickActionPreferences(context)
        val archive = LocalSettingsArchive(context)
        val key = "largeActions"
        val savedSizes = JSONArray(listOf("vehicle", "seatMemory"))
        val order = JSONArray(listOf("settings", "vehicle", "apps"))
        try {
            assertTrue(raw.edit().putString("settings", """{"enabled":true}""").commit())
            assertEquals(0, dock.exportSettings().getJSONArray(key).length())
            val older = JSONObject(archive.exportJson())
            older.getJSONObject("quickActions").apply {
                remove(key)
                put("actions", order)
            }
            dock.restoreSettings(JSONObject().put(key, savedSizes).put("handleSize", "large"))
            val complete = archive.exportJson()
            archive.apply(archive.prepare(older.toString()))
            val reopened = QuickActionPreferences(context)
            val merged = reopened.exportSettings()
            assertEquals(savedSizes.toString(), merged.getJSONArray(key).toString())
            assertEquals(order.toString(), merged.getJSONArray("actions").toString())
            assertEquals("standard", merged.getString("handleSize"))

            older.getJSONObject("quickActions").put(key, JSONArray())
            archive.apply(archive.prepare(older.toString()))
            assertEquals(0, reopened.exportSettings().getJSONArray(key).length())
            archive.apply(archive.prepare(complete))
            assertEquals(savedSizes.toString(), reopened.exportSettings().getJSONArray(key).toString())
            assertEquals("large", reopened.exportSettings().getString("handleSize"))
        } finally {
            assertTrue(raw.edit().putString("settings", original).commit())
        }
    }

    fun testInvalidArchivesDoNotPartiallyChangeCurrentSettings() {
        val preferences = QuickActionPreferences(context)
        val original = preferences.exportSettings().toString()
        val invalid = listOf(
            "actions" to JSONArray(), "actions" to JSONArray(listOf("apps", "apps")),
            "actions" to JSONArray(listOf("apps", "missing")), "actions" to JSONArray(listOf(3)),
            "actions" to "apps", "side" to "top", "area" to "external", "enabled" to 1,
            "showLabels" to JSONObject.NULL, "unknown" to true, "compactEdge" to "left",
            "collapseAfterUse" to "true", "highContrastHandle" to "true", "showCompactHandleLabel" to 0,
            "appsDestination" to "favorites1", "appsDestination" to 1,
            "railWidth" to "wide", "startState" to false, "startState" to "open", "compactLabels" to JSONObject.NULL,
            "showHeadlightMode" to "true", "showHeadlightMode" to 1, "showHeadlightMode" to JSONObject.NULL,
            "headlightContent" to "both", "headlightContent" to false, "headlightContent" to JSONObject.NULL,
            "sideRailAlignment" to "left", "sideRailAlignment" to false, "sideRailAlignment" to JSONObject.NULL,
            "hideOnVirtualDisplay" to "true", "hideOnVirtualDisplay" to 1, "hideOnVirtualDisplay" to JSONObject.NULL,
            "handleSize" to "huge", "handleSize" to 60, "handleSize" to JSONObject.NULL,
            "compactHandleSide" to "top", "compactHandleSide" to true, "compactHandleSide" to JSONObject.NULL,
            "keepSideInset" to "true", "keepSideInset" to JSONObject.NULL,
            "headlightTap" to "external", "headlightTap" to JSONObject.NULL,
            "handleAccent" to "blue", "handleAccent" to 1, "handleAccent" to JSONObject.NULL,
            "collapsedHandleAlignment" to "left", "collapsedHandleAlignment" to true,
            "collapsedHandleAlignment" to JSONObject.NULL,
            "dimIdleHandle" to "true", "dimIdleHandle" to 1, "dimIdleHandle" to JSONObject.NULL,
            "routineShortcutId" to "Morning grid", "routineShortcutId" to 1, "routineShortcutId" to JSONArray(),
            "largeActions" to JSONArray(listOf("apps", "apps")), "largeActions" to JSONArray(listOf("missing")),
            "largeActions" to JSONArray(listOf(1)), "largeActions" to "vehicle", "largeActions" to JSONObject.NULL,
            "largeActions" to JSONArray(List(QuickAction.entries.size + 1) { "apps" }),
        )
        for ((key, value) in invalid) {
            val data = JSONObject().put("enabled", true).put(key, value)
            try {
                QuickActionPreferences.validateSettings(data)
                fail("Accepted invalid field $key")
            } catch (_: IllegalArgumentException) { }
            try {
                preferences.restoreSettings(data)
                fail("Restored invalid field $key")
            } catch (_: IllegalArgumentException) { }
            assertEquals(original, preferences.exportSettings().toString())
        }
    }
}
