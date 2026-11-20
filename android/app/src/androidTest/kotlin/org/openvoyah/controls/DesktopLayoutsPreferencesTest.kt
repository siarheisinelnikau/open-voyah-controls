package org.openvoyah.controls

import android.test.AndroidTestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.data.apps.DesktopInfoPreferences
import org.openvoyah.controls.data.apps.DesktopLayoutsPreferences
import org.openvoyah.controls.data.apps.DesktopWallpaperPreferences
import org.openvoyah.controls.data.settings.LocalSettingsArchive
import org.openvoyah.controls.domain.apps.AppGridColumns
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.DesktopClockFormat
import org.openvoyah.controls.domain.apps.DesktopInfoCard
import org.openvoyah.controls.domain.apps.DesktopInfoSize
import org.openvoyah.controls.domain.apps.DesktopLayoutException
import org.openvoyah.controls.domain.apps.DesktopLayoutPreset
import org.openvoyah.controls.domain.apps.DesktopLayoutProblem
import org.openvoyah.controls.domain.apps.DesktopRouteOpenTarget

class DesktopLayoutsPreferencesTest : AndroidTestCase() {
    fun testLayoutsSnapshotAllDesktopsAndRoundTripThroughSettingsArchive() = runBlocking {
        val archive = LocalSettingsArchive(context)
        val previous = archive.prepare(archive.exportJson())
        val layouts = DesktopLayoutsPreferences(context)
        val grid = AppGridPreferences(context)
        val cards = DesktopInfoPreferences(context)
        val wallpapers = DesktopWallpaperPreferences(context)
        val main = AppGridDesktop.MAIN
        val first = AppGridDesktop.FAVORITES_ONE
        val second = AppGridDesktop.FAVORITES_TWO
        val photo = wallpaperFixture()
        try {
            layouts.restoreSettings(JSONObject())
            archive.applyDesktopLayout(JSONObject().put("pinnedApps", JSONArray()), JSONObject(), JSONObject())
            grid.setColumns(AppGridColumns.FIVE)
            grid.setDesktopsEnabled(true)
            grid.setDesktopNames("City", "Trip")
            grid.setDefaultDesktop(first)
            grid.changePin("org.example.maps/org.example.maps.MainScreen", true)
            grid.changePin("org.example.music/org.example.music.MainScreen", true, first)
            cards.set(main, DesktopInfoCard.CLOCK, DesktopInfoSize.WIDE)
            cards.setClockFormat(main, DesktopClockFormat.HOUR_24)
            cards.set(second, DesktopInfoCard.MEDIA, DesktopInfoSize.COMPACT)
            cards.setMediaPackage(second, "org.example.music")
            wallpapers.set(main, photo)
            wallpapers.set(first, photo)
            val original = archive.captureDesktopLayout().toString()
            layouts.save("  Поездка 🚗  ")
            assertEquals(original, archive.captureDesktopLayout().toString())
            val trip = layouts.observe().first().getOrThrow().single()
            assertEquals("Поездка 🚗", trip.name)

            grid.setColumns(AppGridColumns.SIX)
            cards.set(main, DesktopInfoCard.CLOCK, null)
            wallpapers.set(main, null)
            wallpapers.set(first, null)
            wallpapers.set(second, photo)
            val city = archive.captureDesktopLayout().toString()
            layouts.save("City")
            layouts.duplicate(trip.id, "  Road copy  ")
            val duplicate = layouts.observe().first().getOrThrow().last()
            assertEquals("Road copy", duplicate.name)
            assertFalse(trip.id == duplicate.id)
            assertEquals(city, archive.captureDesktopLayout().toString())
            val library = layouts.exportSettings()
            assertEquals(1, library.getJSONObject("images").length())
            val presets = library.getJSONArray("presets")
            val sharedPhoto = presets.getJSONObject(0).getJSONObject("desktopWallpapers").getString("main")
            assertEquals(sharedPhoto, presets.getJSONObject(0).getJSONObject("desktopWallpapers").getString("favorites1"))
            assertEquals(sharedPhoto, presets.getJSONObject(1).getJSONObject("desktopWallpapers").getString("favorites2"))
            assertEquals(sharedPhoto, presets.getJSONObject(2).getJSONObject("desktopWallpapers").getString("main"))
            val full = archive.exportJson()
            val old = JSONObject(full).apply { remove("desktopLayouts") }
            archive.apply(archive.prepare(old.toString()))
            assertEquals(library.toString(), layouts.exportSettings().toString())

            layouts.restoreSettings(JSONObject())
            assertTrue(layouts.observe().first().getOrThrow().isEmpty())
            archive.apply(archive.prepare(full))
            assertEquals(library.toString(), DesktopLayoutsPreferences(context).exportSettings().toString())
            val unaffected = unaffectedSections(full)
            layouts.apply(trip.id)
            assertEquals(original, archive.captureDesktopLayout().toString())
            assertEquals(unaffected, unaffectedSections(archive.exportJson()))

            layouts.rename(trip.id, "Highway")
            val renamed = layouts.observe().first().getOrThrow().first()
            assertEquals(trip.id, renamed.id)
            assertEquals("Highway", renamed.name)
            assertEquals(original, archive.captureDesktopLayout().toString())
            layouts.delete(trip.id)
            assertEquals(1, layouts.exportSettings().getJSONObject("images").length())
            layouts.apply(duplicate.id)
            assertEquals(original, archive.captureDesktopLayout().toString())
            layouts.delete(duplicate.id)
            assertEquals(1, layouts.exportSettings().getJSONObject("images").length())
            val remaining = layouts.observe().first().getOrThrow().single()
            layouts.apply(remaining.id)
            assertEquals(city, archive.captureDesktopLayout().toString())
            layouts.delete(remaining.id)
            assertEquals(0, layouts.exportSettings().getJSONObject("images").length())
            assertEquals(city, archive.captureDesktopLayout().toString())
        } finally {
            archive.apply(previous)
        }
    }

    fun testPresetAndArchiveValidationDoNotReplaceTheCurrentLibrary() = runBlocking {
        val archive = LocalSettingsArchive(context)
        val previous = archive.prepare(archive.exportJson())
        val layouts = DesktopLayoutsPreferences(context)
        try {
            archive.applyDesktopLayout(JSONObject().put("pinnedApps", JSONArray()), JSONObject(), JSONObject())
            layouts.restoreSettings(JSONObject())
            layouts.save("Café")
            val preset = layouts.observe().first().getOrThrow().single()
            assertProblem(DesktopLayoutProblem.DUPLICATE_NAME) { layouts.save("cafe\u0301") }
            assertProblem(DesktopLayoutProblem.DUPLICATE_NAME) { layouts.duplicate(preset.id, "cafe\u0301") }
            assertProblem(DesktopLayoutProblem.INVALID_NAME) { layouts.duplicate(preset.id, " ") }
            assertProblem(DesktopLayoutProblem.MISSING) {
                layouts.duplicate("00000000-0000-4000-8000-000000000000", "No source")
            }
            for (index in 2..DesktopLayoutPreset.MAX_PRESETS) layouts.save("Trip $index")
            assertProblem(DesktopLayoutProblem.FULL) { layouts.save("Overflow") }
            assertProblem(DesktopLayoutProblem.FULL) { layouts.duplicate(preset.id, "Overflow") }
            val original = archive.exportJson()

            fun reject(change: (JSONObject) -> Unit) {
                val root = JSONObject(original)
                change(root.getJSONObject("desktopLayouts"))
                assertTrue(runCatching { archive.prepare(root.toString()) }.isFailure)
                assertEquals(original, archive.exportJson())
            }
            reject { data -> data.getJSONArray("presets").getJSONObject(0).put("id", "invalid-id") }
            reject { data -> data.getJSONArray("presets").getJSONObject(1).put("name", "CAFE\u0301") }
            reject { data -> data.getJSONArray("presets").getJSONObject(0).getJSONObject("appGrid").put("columns", 10) }
            reject { data -> data.getJSONArray("presets").getJSONObject(0).getJSONObject("desktopWallpapers")
                .put("main", "a".repeat(64)) }
            reject { data -> data.getJSONObject("images").put("a".repeat(64), "AAAA") }
            assertProblem(DesktopLayoutProblem.SIZE_LIMIT) {
                DesktopLayoutsPreferences.validateSettings(JSONObject().put("oversized", "a".repeat(DesktopLayoutsPreferences.MAX_BYTES)))
            }
        } finally {
            archive.apply(previous)
        }
    }

    fun testPreparedLayoutKeepsItsIdentityAndRejectsAReplacedOrDeletedSelection() = runBlocking {
        val archive = LocalSettingsArchive(context)
        val previous = archive.prepare(archive.exportJson())
        val layouts = DesktopLayoutsPreferences(context)
        val grid = AppGridPreferences(context)
        val cards = DesktopInfoPreferences(context)
        val dispatch = LocalSettingsArchive.LayoutDispatch { write ->
            write.run()
            true
        }
        try {
            layouts.restoreSettings(JSONObject())
            grid.setDesktopNames("Луг", "")
            grid.setColumns(AppGridColumns.FIVE)
            layouts.save("Trip")
            val id = layouts.observe().first().getOrThrow().single().id
            val expected = layouts.revision(id)
            val captured = archive.captureDesktopLayout().toString()
            layouts.rename(id, "City")
            layouts.duplicate(id, "Trip")
            assertEquals(expected, layouts.revision(id))
            grid.setColumns(AppGridColumns.FOUR)
            val before = archive.captureDesktopLayout().toString()
            assertFalse(archive.applySavedDesktopLayout(expected, { error("Skipped dispatch committed") }) { false })
            assertEquals(before, archive.captureDesktopLayout().toString())
            assertTrue(archive.applySavedDesktopLayout(expected, { true }, dispatch))
            assertEquals(captured, archive.captureDesktopLayout().toString())

            val replacement = JSONObject(archive.exportJson())
            val preset = replacement.getJSONObject("desktopLayouts").getJSONArray("presets").getJSONObject(0)
            preset.getJSONObject("appGrid").getJSONObject("desktopNames").put("favorites1", "Сад")
            archive.apply(archive.prepare(replacement.toString()))
            assertFalse(expected == layouts.revision(id))
            assertProblem(DesktopLayoutProblem.MISSING) {
                archive.applySavedDesktopLayout(expected, { true }) { error("Replacement dispatched") }
            }
            assertEquals(captured, archive.captureDesktopLayout().toString())

            // An older saved layout carries only the options present when it was captured.
            preset.getJSONObject("appGrid").remove("showSearchField")
            preset.getJSONObject("desktopInfoCards").getJSONObject("main").remove("routeOpenTarget")
            layouts.restoreSettings(replacement.getJSONObject("desktopLayouts"))
            grid.setShowSearchField(true)
            cards.setRouteOpenTarget(AppGridDesktop.MAIN, DesktopRouteOpenTarget.NAVIGATOR)
            val oldFormat = layouts.revision(id)
            assertTrue(archive.applySavedDesktopLayout(oldFormat, { true }, dispatch))
            val applied = archive.captureDesktopLayout()
            assertEquals("Сад", applied.getJSONObject("appGrid").getJSONObject("desktopNames").getString("favorites1"))
            assertTrue(applied.getJSONObject("appGrid").getBoolean("showSearchField"))
            assertEquals("navigator", applied.getJSONObject("desktopInfoCards").getJSONObject("main").getString("routeOpenTarget"))

            layouts.delete(id)
            layouts.save("City")
            assertProblem(DesktopLayoutProblem.MISSING) {
                archive.applySavedDesktopLayout(oldFormat, { true }) { error("Recreated name dispatched") }
            }
            assertEquals(applied.toString(), archive.captureDesktopLayout().toString())
        } finally {
            archive.apply(previous)
        }
    }

    private suspend fun assertProblem(expected: DesktopLayoutProblem, operation: suspend () -> Unit) {
        val failure = runCatching { operation() }.exceptionOrNull()
        assertEquals(expected, (failure as? DesktopLayoutException)?.problem)
    }

    private fun unaffectedSections(json: String): String = JSONObject(json).apply {
        for (section in listOf("appGrid", "desktopWallpapers", "desktopInfoCards")) remove(section)
    }.toString()
}
