package org.openvoyah.controls

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.AndroidTestCase
import android.util.Base64
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.data.apps.DesktopInfoPreferences
import org.openvoyah.controls.data.apps.DesktopLayoutsPreferences
import org.openvoyah.controls.data.apps.DesktopWallpaperPreferences
import org.openvoyah.controls.data.settings.LocalSettingsArchive
import org.openvoyah.controls.data.settings.SeatMemoryPreferences
import org.openvoyah.controls.data.vehicle.HeadlightMonitoringPreferences

class LocalSettingsArchiveRollbackTest : AndroidTestCase() {
    fun testExportWaitsForAnInProgressRestoreInAnotherArchiveInstance() {
        val app = context.applicationContext
        val originalArchive = LocalSettingsArchive(app)
        val original = originalArchive.exportJson()
        val incoming = JSONObject(original)
        val inputButtons = incoming.getJSONObject("buttons")
        val hold = if (inputButtons.getInt("holdMs") == 900) 800 else 900
        inputButtons.put("holdMs", hold)
        val inputSeats = incoming.getJSONObject("seatMemory")
        val slot = if (inputSeats.getInt("selectedSlot") == 1) 2 else 1
        inputSeats.put("selectedSlot", slot)

        val reachedLastWrite = CountDownLatch(1)
        val release = CountDownLatch(1)
        val archive = LocalSettingsArchive(InterceptPreferenceWriteContext(app, SeatMemoryPreferences.STORE) { commit ->
            reachedLastWrite.countDown()
            check(release.await(8, TimeUnit.SECONDS)) { "Timed out waiting to finish the fixture restore" }
            commit()
        })
        val threads = Executors.newFixedThreadPool(2)
        try {
            val prepared = archive.prepare(incoming.toString())
            val pendingRestore = threads.submit { archive.apply(prepared) }
            assertTrue(reachedLastWrite.await(8, TimeUnit.SECONDS))
            val exporting = CountDownLatch(1)
            val firstStoreRead = CountDownLatch(1)
            val observed = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val actual = super.getSharedPreferences(name, mode)
                    if (name != "controls") return actual
                    return object : SharedPreferences by actual {
                        override fun getString(key: String?, defValue: String?): String? {
                            if (key == "state") firstStoreRead.countDown()
                            return actual.getString(key, defValue)
                        }
                    }
                }
            }
            val export = threads.submit<JSONObject> {
                val exporter = LocalSettingsArchive(observed)
                exporting.countDown()
                JSONObject(exporter.exportJson())
            }
            assertTrue(exporting.await(5, TimeUnit.SECONDS))
            assertFalse("No store may be read during the restore", firstStoreRead.await(500, TimeUnit.MILLISECONDS))

            release.countDown()
            pendingRestore.get(8, TimeUnit.SECONDS)
            val exported = export.get(5, TimeUnit.SECONDS)
            assertEquals(0L, firstStoreRead.count)
            assertEquals(hold, exported.getJSONObject("buttons").getInt("holdMs"))
            assertEquals(slot, exported.getJSONObject("seatMemory").getInt("selectedSlot"))
            assertFalse(File(app.filesDir, "settings-restore-rollback.json").exists())
        } finally {
            release.countDown()
            threads.shutdown()
            assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS))
            originalArchive.apply(originalArchive.prepare(original))
        }
    }

    fun testLateSeatPreferenceWriteFailureRestoresEveryStoreExactly() {
        val app = context.applicationContext
        val originalArchive = LocalSettingsArchive(app)
        val incoming = JSONObject(originalArchive.exportJson())
        val before = snapshots(app)
        incoming.getJSONObject("buttons").apply { put("holdMs", if (getInt("holdMs") == 800) 900 else 800) }
        incoming.getJSONObject("display").apply { put("density", if (getInt("density") == 240) 320 else 240) }
        incoming.getJSONObject("updates").apply { put("checkOnStartup", !getBoolean("checkOnStartup")) }
        incoming.getJSONObject("appGrid").apply {
            put("pinnedApps", JSONArray().put("com.example.maps/com.example.maps.Main"))
            put("tileSize", if (getString("tileSize") == "large") "compact" else "large")
            put("showSystemApps", !getBoolean("showSystemApps"))
            put("iconStyle", if (getString("iconStyle") == "native") "monochrome" else "native")
            put("showAppLabels", !getBoolean("showAppLabels"))
            put("sortOrder", if (getString("sortOrder") == "name") "recentlyInstalled" else "name")
            getJSONObject("desktops").apply {
                put("enabled", !getBoolean("enabled"))
                put("defaultDesktop", if (getString("defaultDesktop") == "favorites1") "favorites2" else "favorites1")
                put("favorites1", JSONArray().put("com.example.maps/com.example.maps.Main"))
            }
        }
        incoming.getJSONObject("desktopWallpapers").put("main", Base64.encodeToString(wallpaperFixture(), Base64.NO_WRAP))
        incoming.put("desktopInfoCards", JSONObject("""{"main":{"clock":"wide","service":"compact"}}"""))
        incoming.getJSONObject("appearance").apply {
            put("animateRoutes", !getBoolean("animateRoutes"))
            put("highContrast", !getBoolean("highContrast"))
            put("launcherEntry", if (getString("launcherEntry") == "vehicle") "settings" else "vehicle")
            put("transitionSpeed", if (getString("transitionSpeed") == "smooth") "short" else "smooth")
        }
        incoming.getJSONObject("displayPreview").apply {
            put("showMedia", !getBoolean("showMedia"))
            put("showNavigation", !getBoolean("showNavigation"))
            put("showPlaybackProgress", !getBoolean("showPlaybackProgress"))
            put("showAlbumArtwork", !getBoolean("showAlbumArtwork"))
            put("showTransportControls", !getBoolean("showTransportControls"))
            put("preferredMediaPackage", if (getString("preferredMediaPackage").isEmpty()) "com.example.player" else "")
            put("arrangement", "stacked")
            put("paneOrder", "routeFirst")
            put("seekStepSeconds", 30)
        }
        incoming.getJSONObject("serviceReminder").apply {
            put("enabled", !getBoolean("enabled"))
            put("intervalKm", 15_000)
            put("currentKm", 85_000)
            put("lastServiceKm", 70_000)
            put("leadDistanceKm", 1_000)
        }
        incoming.put("quickActions", JSONObject("""{
            "enabled":true,"side":"left","showLabels":false,"area":"otherScreens",
            "actions":["vehicle","voiceSettings","apps"]
        }"""))
        incoming.put("routines", JSONObject("""{
            "entries":[{"id":"12345678-1234-4234-9234-123456789abc","name":"Evening","target":"favorites2","palette":"dark"}],
            "sort":"name","startup":"12345678-1234-4234-9234-123456789abc"
        }"""))
        incoming.getJSONObject("headlightMonitoring").apply {
            put("whileControlsOpen", !getBoolean("whileControlsOpen"))
        }
        incoming.getJSONObject("seatMemory").put("selectedSlot", 2)
            .put("slotNames", JSONArray().put("").put("Evening drive").put(""))
        val failed = AtomicBoolean()
        val archive = LocalSettingsArchive(InterceptPreferenceWriteContext(app, SeatMemoryPreferences.STORE) { commit ->
            // SharedPreferences can update memory before reporting a failed disk commit.
            check(commit())
            failed.set(true)
            false
        })

        try {
            val prepared = archive.prepare(incoming.toString())
            try {
                archive.apply(prepared)
                fail("The last preference write must report failure")
            } catch (_: Exception) {
                assertTrue("The restore must reach the optional seat store", failed.get())
            }
            assertEquals(before, snapshots(app))
            assertFalse(File(app.filesDir, "settings-restore-rollback.json").exists())
        } finally {
            originalArchive.recoverInterruptedRestore()
        }
    }

    fun testDesktopLayoutWriteFailureRestoresOnlyItsThreeStores() {
        val app = context.applicationContext
        val originalArchive = LocalSettingsArchive(app)
        val before = originalArchive.captureDesktopLayout()
        val pending = JSONObject(before.toString())
        val grid = pending.getJSONObject("appGrid").apply {
            put("tileSize", if (getString("tileSize") == "large") "compact" else "large")
        }
        val wallpapers = pending.getJSONObject("desktopWallpapers").apply {
            put("main", if (isNull("main")) Base64.encodeToString(wallpaperFixture(), Base64.NO_WRAP) else JSONObject.NULL)
        }
        val cards = pending.getJSONObject("desktopInfoCards").apply {
            getJSONObject("main").apply { put("clock", if (optString("clock") == "wide") "compact" else "wide") }
        }
        val updates = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val key = "check_on_startup"
        val updateChoice = updates.all[key] as? Boolean
        val concurrentChoice = updateChoice != true
        val reachedLastWrite = AtomicBoolean()
        val failingArchive = LocalSettingsArchive(InterceptPreferenceWriteContext(app, DesktopInfoPreferences.STORE) { commit ->
            assertEquals(grid.toString(), AppGridPreferences(app).exportSettings().toString())
            assertEquals(wallpapers.toString(), DesktopWallpaperPreferences(app).exportSettings().toString())
            check(updates.edit().putBoolean(key, concurrentChoice).commit())
            check(commit())
            reachedLastWrite.set(true)
            false
        })

        try {
            try {
                failingArchive.applyDesktopLayout(grid, wallpapers, cards)
                fail("The last desktop store must report failure")
            } catch (_: Exception) {
                assertTrue("The restore must reach the desktop cards store", reachedLastWrite.get())
            }
            assertEquals(before.toString(), originalArchive.captureDesktopLayout().toString())
            assertEquals(concurrentChoice, updates.getBoolean(key, !concurrentChoice))
            assertFalse(File(app.filesDir, "settings-restore-rollback.json").exists())
        } finally {
            originalArchive.recoverInterruptedRestore()
            val editor = updates.edit()
            if (updateChoice == null) editor.remove(key) else editor.putBoolean(key, updateChoice)
            check(editor.commit())
        }
    }

    fun testCancelDuringSavedLayoutWriteRollsBackBeforeArchiveAndLibraryReadersProceed() = runBlocking {
        val app = context.applicationContext
        val archive = LocalSettingsArchive(app)
        val previous = archive.prepare(archive.exportJson())
        val layouts = DesktopLayoutsPreferences(app)
        val releaseWrite = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(3)
        try {
            layouts.restoreSettings(JSONObject())
            archive.applyDesktopLayout(JSONObject().put("pinnedApps", JSONArray()).put("columns", "5"),
                JSONObject().put("main", Base64.encodeToString(wallpaperFixture(), Base64.NO_WRAP)),
                JSONObject().put("main", JSONObject().put("clock", "wide")))
            layouts.save("Commute")
            val expected = layouts.revision(layouts.observe().first().getOrThrow().single().id)
            archive.applyDesktopLayout(JSONObject().put("pinnedApps", JSONArray()).put("columns", "4"),
                JSONObject(), JSONObject().put("main", JSONObject().put("clock", "compact")))
            val before = archive.exportJson()

            val finalStoreWritten = CountDownLatch(1)
            val commitAllowed = AtomicBoolean(true)
            val returnedFromWrite = AtomicBoolean()
            val intercepted = LocalSettingsArchive(InterceptPreferenceWriteContext(app, DesktopInfoPreferences.STORE) { commit ->
                check(commit())
                finalStoreWritten.countDown()
                check(releaseWrite.await(8, TimeUnit.SECONDS))
                true
            })
            val applying = threads.submit<Boolean> {
                intercepted.applySavedDesktopLayout(expected, commitAllowed::get) { write ->
                    write.run()
                    returnedFromWrite.set(true)
                    true
                }
            }
            assertTrue(finalStoreWritten.await(8, TimeUnit.SECONDS))
            val readersStarted = CountDownLatch(2)
            val firstReaderFinished = CountDownLatch(1)
            val exporting = threads.submit<String> {
                readersStarted.countDown()
                try { archive.exportJson() } finally { firstReaderFinished.countDown() }
            }
            val readingLibrary = threads.submit<String> {
                readersStarted.countDown()
                try { layouts.exportSettings().toString() } finally { firstReaderFinished.countDown() }
            }
            assertTrue(readersStarted.await(5, TimeUnit.SECONDS))
            assertFalse(firstReaderFinished.await(250, TimeUnit.MILLISECONDS))
            commitAllowed.set(false)
            releaseWrite.countDown()
            val failure = runCatching { applying.get(8, TimeUnit.SECONDS) }.exceptionOrNull()
            assertTrue("The final guard must cancel the write", failure?.cause is CancellationException)
            assertFalse(returnedFromWrite.get())
            assertEquals(before, exporting.get(5, TimeUnit.SECONDS))
            assertEquals(JSONObject(before).getJSONObject("desktopLayouts").toString(), readingLibrary.get(5, TimeUnit.SECONDS))
            assertEquals(before, archive.exportJson())
            assertFalse(File(app.filesDir, "settings-restore-rollback.json").exists())
        } finally {
            releaseWrite.countDown()
            threads.shutdown()
            assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS))
            archive.apply(previous)
        }
    }

    private fun snapshots(context: Context): Map<String, Map<String, *>> =
        listOf("controls", "voice", "localization", "ui", "display", "app_windows", "updates", "app_grid", "appearance",
            "display_preview", "desktop_wallpapers", "desktop_info_cards", "service_reminder", "quick_actions", "routines",
            SeatMemoryPreferences.STORE, DesktopLayoutsPreferences.STORE, HeadlightMonitoringPreferences.STORE)
            .associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all }

    private class InterceptPreferenceWriteContext(
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
