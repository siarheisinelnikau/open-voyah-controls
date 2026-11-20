package org.openvoyah.controls

import android.content.res.Configuration
import android.test.AndroidTestCase
import android.text.format.DateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.data.apps.DesktopInfoPreferences
import org.openvoyah.controls.data.apps.DesktopLayoutsPreferences
import org.openvoyah.controls.data.apps.desktopClockText
import org.openvoyah.controls.data.settings.LocalSettingsArchive
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.DesktopClockFormat
import org.openvoyah.controls.domain.apps.DesktopHeadlightContent
import org.openvoyah.controls.domain.apps.DesktopInfoCard
import org.openvoyah.controls.domain.apps.DesktopInfoSize
import org.openvoyah.controls.domain.apps.DesktopInfoTile
import org.openvoyah.controls.domain.apps.DesktopMediaOpenTarget
import org.openvoyah.controls.domain.apps.DesktopMediaStyle
import org.openvoyah.controls.domain.apps.DesktopRouteContent
import org.openvoyah.controls.domain.apps.DesktopRouteOpenTarget
import org.openvoyah.controls.domain.apps.PhoneFavoriteEdit

class DesktopInfoPreferencesTest : AndroidTestCase() {
    fun testDesktopCardsPersistAndRestoreByStableSlot() {
        val archive = LocalSettingsArchive(context)
        val store = DesktopInfoPreferences(context)
        val previous = store.exportSettings()
        val main = AppGridDesktop.MAIN
        val favorite = AppGridDesktop.FAVORITES_ONE
        val service = DesktopInfoCard.SERVICE
        val clock = DesktopInfoCard.CLOCK
        try {
            store.restoreSettings(JSONObject())
            runBlocking {
                store.set(main, clock, DesktopInfoSize.COMPACT)
                store.set(main, service, DesktopInfoSize.WIDE)
                store.set(favorite, clock, DesktopInfoSize.WIDE)
            }
            val saved = archive.exportJson()
            val restoredStore = DesktopInfoPreferences(context)
            val fresh = runBlocking { withTimeout(5_000) { restoredStore.observe().first().getOrThrow() } }
            assertEquals(listOf(DesktopInfoTile(clock, DesktopInfoSize.COMPACT), DesktopInfoTile(service, DesktopInfoSize.WIDE)),
                fresh.tiles(main))
            assertEquals(DesktopInfoSize.WIDE, fresh.sizeOf(favorite, clock))

            val legacy = JSONObject(saved).apply { remove("desktopInfoCards") }
            archive.apply(archive.prepare(legacy.toString()))
            assertEquals(fresh, runBlocking { restoredStore.observe().first().getOrThrow() })

            val empty = JSONObject(saved).put("desktopInfoCards", JSONObject())
            archive.apply(archive.prepare(empty.toString()))
            val hidden = runBlocking { store.observe().first().getOrThrow() }
            assertTrue(AppGridDesktop.entries.all { hidden.tiles(it).isEmpty() })
            archive.apply(archive.prepare(saved))
            assertEquals(fresh, runBlocking { restoredStore.observe().first().getOrThrow() })
            runBlocking { store.set(main, clock, null) }
            assertEquals(listOf(DesktopInfoTile(service, DesktopInfoSize.WIDE)),
                runBlocking { store.observe().first().getOrThrow() }.tiles(main))
        } finally {
            store.restoreSettings(previous)
        }
    }

    fun testInvalidDesktopCardSettingsAreRejectedByArchivePreparation() {
        val archive = LocalSettingsArchive(context)
        val original = archive.exportJson()
        val incoming = JSONObject(original)
        val invalid = listOf(
            """{"other":{"clock":"wide"}}""",
            """{"main":{"unknown":"compact"}}""",
            """{"main":{"clock":"large"}}""",
            """{"main":{"clock":1}}""",
            """{"main":{"mediaStyle":true}}""",
            """{"main":{"mediaStyle":"art"}}""",
            """{"main":{"preferredMediaPackage":1}}""",
            """{"main":{"preferredMediaPackage":"content://org.example.media"}}""",
            """{"main":{"mediaOpenTarget":true}}""",
            """{"main":{"mediaOpenTarget":"com.example.music"}}""",
            """{"main":{"clockFormat":24}}""",
            """{"main":{"clockFormat":"HH:mm"}}""",
            """{"main":{"headlightContent":false}}""",
            """{"main":{"headlightContent":"auto"}}""",
            """{"main":{"routeContent":true}}""",
            """{"main":{"routeContent":"nextStreet"}}""",
            """{"main":{"routeOpenTarget":true}}""",
            """{"main":{"routeOpenTarget":"com.example.navigation"}}""",
            """{"main":{"phoneFavorites":"contact-1"}}""",
            """{"main":{"phoneFavorites":[7]}}""",
            """{"main":{"phoneFavorites":["first","first"]}}""",
            """{"main":{"phoneFavorites":["1","2","3","4"]}}""",
            """{"main":{"phoneFavorites":[""]}}""",
        )
        for (payload in invalid) {
            incoming.put("desktopInfoCards", JSONObject(payload))
            assertTrue(runCatching { archive.prepare(incoming.toString()) }.isFailure)
            assertEquals(original, archive.exportJson())
        }
    }

    fun testPresentationChoicesSurviveLegacyAndFullRestores() = runBlocking {
        val archive = LocalSettingsArchive(context)
        val store = DesktopInfoPreferences(context)
        val previous = store.exportSettings()
        val main = AppGridDesktop.MAIN
        val favorite = AppGridDesktop.FAVORITES_ONE
        try {
            store.restoreSettings(JSONObject("""{
                "main":{"media":"wide","clock":"compact","mediaStyle":"text","clockFormat":"system","preferredMediaPackage":""},
                "favorites1":{"preferredMediaPackage":""}
            }"""))
            val defaults = archive.exportJson()
            store.setMediaStyle(main, DesktopMediaStyle.COVER)
            store.setClockFormat(main, DesktopClockFormat.HOUR_24)
            store.setClockFormat(favorite, DesktopClockFormat.HOUR_12)
            store.setMediaPackage(main, "org.example.music")
            store.setMediaPackage(favorite, "org.example.radio")
            val selected = archive.exportJson()

            val oldCards = JSONObject("""{"main":{"clock":"wide"}}""")
            val validated = DesktopInfoPreferences.validateSettings(oldCards)
            assertFalse(validated.getJSONObject("main").has("mediaStyle"))
            assertFalse(validated.getJSONObject("main").has("clockFormat"))
            assertFalse(validated.getJSONObject("main").has("preferredMediaPackage"))
            archive.apply(archive.prepare(JSONObject(selected).put("desktopInfoCards", oldCards).toString()))
            val legacy = store.observe().first().getOrThrow()
            assertEquals(DesktopMediaStyle.COVER, legacy.mediaStyleOf(main))
            assertEquals(DesktopClockFormat.HOUR_24, legacy.clockFormatOf(main))
            assertEquals(DesktopClockFormat.HOUR_12, legacy.clockFormatOf(favorite))
            assertEquals("org.example.music", legacy.mediaPackageOf(main))
            assertEquals("org.example.radio", legacy.mediaPackageOf(favorite))
            assertEquals(DesktopInfoSize.WIDE, legacy.sizeOf(main, DesktopInfoCard.CLOCK))
            assertNull(legacy.sizeOf(main, DesktopInfoCard.MEDIA))

            archive.apply(archive.prepare(defaults))
            val restoredDefaults = store.observe().first().getOrThrow()
            assertEquals(DesktopMediaStyle.TEXT, restoredDefaults.mediaStyleOf(main))
            assertEquals(DesktopClockFormat.SYSTEM, restoredDefaults.clockFormatOf(main))
            assertEquals("", restoredDefaults.mediaPackageOf(main))
            assertEquals("", restoredDefaults.mediaPackageOf(favorite))
            archive.apply(archive.prepare(selected))
            val restored = DesktopInfoPreferences(context).observe().first().getOrThrow()
            assertEquals(DesktopMediaStyle.COVER, restored.mediaStyleOf(main))
            assertEquals(DesktopInfoSize.WIDE, restored.sizeOf(main, DesktopInfoCard.MEDIA))
            assertEquals(DesktopClockFormat.HOUR_24, restored.clockFormatOf(main))
            assertEquals("org.example.music", restored.tiles(main).single { it.card == DesktopInfoCard.MEDIA }.preferredMediaPackage)
            assertEquals("org.example.radio", restored.mediaPackageOf(favorite))
        } finally { store.restoreSettings(previous) }
    }

    fun testPhoneFavoritesRoundTripAndLegacyRestoresPreserveIndependentLists() = runBlocking {
        val archive = LocalSettingsArchive(context)
        val store = DesktopInfoPreferences(context)
        val layouts = DesktopLayoutsPreferences(context)
        val previous = archive.prepare(archive.exportJson())
        val main = AppGridDesktop.MAIN
        val favorite = AppGridDesktop.FAVORITES_ONE
        val currentOrder = listOf("Legacy.Key", "00000000-0000-4000-8000-000000000002")
        val differentOrder = currentOrder.reversed()
        val favoriteOrder = listOf("office", "unused-contact")

        suspend fun expect(mainIds: List<String>, favoriteIds: List<String>) {
            val fresh = DesktopInfoPreferences(context).observe().first().getOrThrow()
            assertEquals(mainIds, fresh.phoneFavoritesOf(main))
            assertEquals(favoriteIds, fresh.phoneFavoritesOf(favorite))
        }

        try {
            val input = JSONObject()
                .put("main", JSONObject().put("phone", "wide").put("phoneFavorites", JSONArray(currentOrder)))
                .put("favorites1", JSONObject().put("phoneFavorites", JSONArray(favoriteOrder)))
            store.restoreSettings(input)
            expect(currentOrder, favoriteOrder)
            assertEquals(0, store.exportSettings().getJSONObject("favorites2").getJSONArray("phoneFavorites").length())
            layouts.restoreSettings(JSONObject())
            layouts.save("Phone favorites")
            val preset = layouts.observe().first().getOrThrow().single()
            val full = archive.exportJson()
            val newLayout = archive.captureDesktopLayout()
            val oldLayout = JSONObject(newLayout.getJSONObject("desktopInfoCards").toString())
            for (desktop in AppGridDesktop.entries) oldLayout.getJSONObject(desktop.storedValue).remove("phoneFavorites")

            store.editPhoneFavorite(main, currentOrder.last(), PhoneFavoriteEdit.MOVE_UP)
            expect(differentOrder, favoriteOrder)
            val legacy = JSONObject(full).put("desktopInfoCards", oldLayout)
            legacy.getJSONObject("desktopLayouts").getJSONArray("presets").getJSONObject(0).put("desktopInfoCards", oldLayout)
            archive.apply(archive.prepare(legacy.toString()))
            expect(differentOrder, favoriteOrder)
            layouts.apply(preset.id)
            expect(differentOrder, favoriteOrder)

            archive.apply(archive.prepare(full))
            expect(currentOrder, favoriteOrder)

            store.restoreSettings(input.put("main", JSONObject().put("phoneFavorites", JSONArray())))
            expect(emptyList(), favoriteOrder)
            layouts.apply(preset.id)
            expect(currentOrder, favoriteOrder)
            assertEquals(DesktopInfoSize.WIDE, store.observe().first().getOrThrow().sizeOf(main, DesktopInfoCard.PHONE))
        } finally {
            archive.apply(previous)
        }
    }

    fun testHeadlightContentRoundTripsAndLegacyLayoutsPreserveTheCurrentChoice() = runBlocking {
        val archive = LocalSettingsArchive(context)
        val store = DesktopInfoPreferences(context)
        val layouts = DesktopLayoutsPreferences(context)
        val previous = archive.prepare(archive.exportJson())
        val main = AppGridDesktop.MAIN
        val favorite = AppGridDesktop.FAVORITES_ONE
        val lamps = DesktopHeadlightContent.LAMP_ACTIVITY
        val switch = DesktopHeadlightContent.SWITCH_POSITION

        suspend fun expect(mainContent: DesktopHeadlightContent, favoriteContent: DesktopHeadlightContent) {
            val restored = DesktopInfoPreferences(context).observe().first().getOrThrow()
            assertEquals(mainContent, restored.headlightContentOf(main))
            assertEquals(favoriteContent, restored.headlightContentOf(favorite))
        }

        try {
            store.set(main, DesktopInfoCard.HEADLIGHTS, DesktopInfoSize.COMPACT)
            store.setHeadlightContent(main, lamps)
            store.setHeadlightContent(favorite, switch)
            layouts.restoreSettings(JSONObject())
            layouts.save("Exterior lamps")
            val preset = layouts.observe().first().getOrThrow().single()
            val full = archive.exportJson()
            val cards = archive.captureDesktopLayout().getJSONObject("desktopInfoCards")
            assertEquals("lamps", cards.getJSONObject(main.storedValue).getString("headlightContent"))
            assertEquals("switch", cards.getJSONObject(favorite.storedValue).getString("headlightContent"))
            for (desktop in AppGridDesktop.entries) cards.getJSONObject(desktop.storedValue).remove("headlightContent")

            store.setHeadlightContent(main, switch)
            store.setHeadlightContent(favorite, lamps)
            val legacy = JSONObject(full).put("desktopInfoCards", cards)
            legacy.getJSONObject("desktopLayouts").getJSONArray("presets").getJSONObject(0).put("desktopInfoCards", cards)
            archive.apply(archive.prepare(legacy.toString()))
            expect(switch, lamps)
            layouts.apply(preset.id)
            expect(switch, lamps)

            archive.apply(archive.prepare(full))
            expect(lamps, switch)
            store.setHeadlightContent(main, switch)
            store.setHeadlightContent(favorite, lamps)
            layouts.apply(preset.id)
            expect(lamps, switch)
        } finally {
            archive.apply(previous)
        }
    }

    fun testMediaOpenTargetRoundTripsAndLegacyLayoutsPreserveTheCurrentChoice() = runBlocking {
        val archive = LocalSettingsArchive(context)
        val store = DesktopInfoPreferences(context)
        val layouts = DesktopLayoutsPreferences(context)
        val previous = archive.prepare(archive.exportJson())
        val desktops = AppGridDesktop.entries
        val stored = listOf(DesktopMediaOpenTarget.PLAYER, DesktopMediaOpenTarget.DISPLAY, DesktopMediaOpenTarget.NONE)
        val changed = listOf(DesktopMediaOpenTarget.DISPLAY, DesktopMediaOpenTarget.NONE, DesktopMediaOpenTarget.PLAYER)

        suspend fun set(targets: List<DesktopMediaOpenTarget>) {
            for ((desktop, target) in desktops.zip(targets)) store.setMediaOpenTarget(desktop, target)
        }

        suspend fun expect(targets: List<DesktopMediaOpenTarget>) {
            val actual = DesktopInfoPreferences(context).observe().first().getOrThrow()
            assertEquals(targets, desktops.map(actual::mediaOpenTargetOf))
        }

        try {
            store.set(AppGridDesktop.MAIN, DesktopInfoCard.MEDIA, DesktopInfoSize.COMPACT)
            set(stored)
            layouts.restoreSettings(JSONObject())
            layouts.save("Playback")
            val preset = layouts.observe().first().getOrThrow().single()
            val full = archive.exportJson()
            val cards = archive.captureDesktopLayout().getJSONObject("desktopInfoCards")
            assertEquals(listOf("player", "display", "none"),
                desktops.map { cards.getJSONObject(it.storedValue).getString("mediaOpenTarget") })
            for (desktop in desktops) cards.getJSONObject(desktop.storedValue).remove("mediaOpenTarget")

            set(changed)
            val legacy = JSONObject(full).put("desktopInfoCards", cards)
            legacy.getJSONObject("desktopLayouts").getJSONArray("presets").getJSONObject(0).put("desktopInfoCards", cards)
            archive.apply(archive.prepare(legacy.toString()))
            expect(changed)
            layouts.apply(preset.id)
            expect(changed)

            archive.apply(archive.prepare(full))
            expect(stored)
            set(changed)
            layouts.apply(preset.id)
            expect(stored)
        } finally {
            archive.apply(previous)
        }
    }

    fun testNavigationContentPersistsPerDesktopAndSurvivesLegacyArchives() = runBlocking {
        val archive = LocalSettingsArchive(context)
        val store = DesktopInfoPreferences(context)
        val layouts = DesktopLayoutsPreferences(context)
        val backup = archive.prepare(archive.exportJson())
        val main = AppGridDesktop.MAIN
        val favorites = AppGridDesktop.FAVORITES_ONE
        val next = DesktopRouteContent.NEXT_TURN
        val both = DesktopRouteContent.CURRENT_AND_NEXT
        val display = DesktopRouteOpenTarget.DISPLAY
        val navigator = DesktopRouteOpenTarget.NAVIGATOR
        val none = DesktopRouteOpenTarget.NONE

        suspend fun expect(
            mainContent: DesktopRouteContent,
            favoriteContent: DesktopRouteContent,
            mainAction: DesktopRouteOpenTarget,
            favoriteAction: DesktopRouteOpenTarget,
        ) {
            val restored = DesktopInfoPreferences(context).observe().first().getOrThrow()
            val mainTile = restored.tiles(main).first { it.card == DesktopInfoCard.ROUTE }
            assertEquals(mainContent, restored.routeContentOf(main))
            assertEquals(favoriteContent, restored.routeContentOf(favorites))
            assertEquals(mainContent, mainTile.routeContent)
            assertEquals(next, restored.routeContentOf(AppGridDesktop.FAVORITES_TWO))
            assertEquals(mainAction, restored.routeOpenTargetOf(main))
            assertEquals(favoriteAction, restored.routeOpenTargetOf(favorites))
            assertEquals(mainAction, mainTile.routeOpenTarget)
            assertEquals(display, restored.routeOpenTargetOf(AppGridDesktop.FAVORITES_TWO))
        }

        try {
            store.set(main, DesktopInfoCard.ROUTE, DesktopInfoSize.WIDE)
            store.setRouteContent(main, both)
            store.setRouteContent(favorites, next)
            store.setRouteContent(AppGridDesktop.FAVORITES_TWO, next)
            store.setRouteOpenTarget(main, navigator)
            store.setRouteOpenTarget(favorites, none)
            store.setRouteOpenTarget(AppGridDesktop.FAVORITES_TWO, display)
            layouts.restoreSettings(JSONObject())
            layouts.save("Route details")
            val preset = layouts.observe().first().getOrThrow().single()
            val full = archive.exportJson()
            val cards = archive.captureDesktopLayout().getJSONObject("desktopInfoCards")
            assertEquals("wide", cards.getJSONObject(main.storedValue).getString("route"))
            assertEquals("currentAndNext", cards.getJSONObject(main.storedValue).getString("routeContent"))
            assertEquals("nextTurn", cards.getJSONObject(favorites.storedValue).getString("routeContent"))
            assertEquals("navigator", cards.getJSONObject(main.storedValue).getString("routeOpenTarget"))
            assertEquals("none", cards.getJSONObject(favorites.storedValue).getString("routeOpenTarget"))
            assertEquals("display", cards.getJSONObject(AppGridDesktop.FAVORITES_TWO.storedValue).getString("routeOpenTarget"))

            for (desktop in AppGridDesktop.entries) {
                val options = cards.getJSONObject(desktop.storedValue)
                options.remove("routeContent")
                options.remove("routeOpenTarget")
            }
            val legacy = JSONObject(full).put("desktopInfoCards", cards)
            legacy.getJSONObject("desktopLayouts").getJSONArray("presets").getJSONObject(0).put("desktopInfoCards", cards)
            store.setRouteContent(main, next)
            store.setRouteContent(favorites, both)
            store.setRouteOpenTarget(main, none)
            store.setRouteOpenTarget(favorites, display)
            archive.apply(archive.prepare(legacy.toString()))
            expect(next, both, none, display)
            layouts.apply(preset.id)
            expect(next, both, none, display)

            archive.apply(archive.prepare(full))
            expect(both, next, navigator, none)
            store.setRouteContent(main, next)
            store.setRouteContent(favorites, both)
            store.setRouteOpenTarget(main, display)
            store.setRouteOpenTarget(favorites, navigator)
            layouts.apply(preset.id)
            expect(both, next, navigator, none)
        } finally {
            archive.apply(backup)
        }
    }

    fun testForcedClockFormatsHonorTheLocalTimeAndLanguage() {
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.US) })
        val russian = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale("ru")) })
        val afternoon = Calendar.getInstance().apply { set(2025, Calendar.FEBRUARY, 19, 13, 5, 0) }.time
        val twelveHour = desktopClockText(english, DesktopClockFormat.HOUR_12, afternoon)
        assertTrue(twelveHour, twelveHour.startsWith("1:05") && twelveHour.endsWith("PM"))
        assertEquals("13:05", desktopClockText(english, DesktopClockFormat.HOUR_24, afternoon))
        assertEquals("13:05", desktopClockText(russian, DesktopClockFormat.HOUR_24, afternoon))
        assertEquals(DateFormat.getTimeFormat(english).format(afternoon), desktopClockText(english, DesktopClockFormat.SYSTEM, afternoon))
    }
}
