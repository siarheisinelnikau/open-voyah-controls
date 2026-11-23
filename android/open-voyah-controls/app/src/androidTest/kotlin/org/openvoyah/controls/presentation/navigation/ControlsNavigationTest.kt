package org.openvoyah.controls.presentation.navigation

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.os.Build
import android.test.InstrumentationTestCase
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.LaunchBrandActivity
import org.openvoyah.controls.MainActivity
import org.openvoyah.controls.QuickVoiceActivity
import org.openvoyah.controls.SetupActivity
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.seatmemory.SeatMemoryAction
import org.openvoyah.controls.domain.seatmemory.SeatMemorySlot
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.domain.settings.LauncherEntry
import org.openvoyah.controls.presentation.seatmemory.SeatMemoryPrompt

/** Explicit entries retain one normal host, protected native entries and a saved back stack. */
class ControlsNavigationTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext
    private var activity: MainActivity? = null
    private var startupPreference: Boolean? = null

    override fun setUp() {
        super.setUp()
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val updates = app.getSharedPreferences("updates", 0)
        startupPreference = if (updates.contains("check_on_startup")) updates.getBoolean("check_on_startup", true) else null
        check(updates.edit().putBoolean("check_on_startup", false).commit())
    }

    override fun tearDown() {
        try {
            activity?.let { current -> onMain { current.finish() } }
            instrumentation.waitForIdleSync()
        } finally {
            check(app.getSharedPreferences("updates", 0).edit().apply {
                startupPreference?.let { putBoolean("check_on_startup", it) } ?: remove("check_on_startup")
            }.commit())
            super.tearDown()
        }
    }

    fun testActivitiesRetainTheHostAndProtectedEntryBoundaries() {
        val packages = app.packageManager
        val activities = checkNotNull(packages.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES).activities)
        val expected = setOf(MainActivity::class.java.name, QuickVoiceActivity::class.java.name,
            SetupActivity::class.java.name, LaunchBrandActivity::class.java.name)
        assertEquals(expected, activities.map { it.name }.toSet())
        for (info in activities) {
            assertNull("${info.name} must be a real activity", info.targetActivity)
        }
        val direct = packages.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertTrue(direct.exported)
        assertNull(direct.targetActivity)
        assertNull(direct.permission)

        val launcher = checkNotNull(packages.getLaunchIntentForPackage(app.packageName))
        assertEquals(ComponentName(app, MainActivity::class.java), launcher.component)

        val microphone = packages.getActivityInfo(ComponentName(app, QuickVoiceActivity::class.java), 0)
        assertNull(microphone.targetActivity)
        assertTrue(microphone.exported)
        assertEquals(Manifest.permission.WRITE_SECURE_SETTINGS, microphone.permission)
        val setup = packages.getActivityInfo(ComponentName(app, SetupActivity::class.java), 0)
        assertNull(setup.targetActivity)
        assertTrue(setup.exported)
        assertEquals(Manifest.permission.WRITE_SECURE_SETTINGS, setup.permission)
    }

    fun testAppsAndSettingsIntentsSelectTheHostAndReuseItsTask() {
        for ((intent, route) in listOf(MainActivity.appsIntent(app) to ControlsRoute.APPS,
                MainActivity.settingsIntent(app) to ControlsRoute.SETTINGS)) {
            assertEquals(ComponentName(app, MainActivity::class.java), intent.component)
            assertEquals(route, ControlsNavigation.route(intent))
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            assertEquals(flags, intent.flags and flags)
        }
    }

    fun testDriverMemoryIntentValidatesNumber() {
        for (slot in SeatMemorySlot.entries) {
            val intent = ControlsNavigation.driverMemoryIntent(app, slot)
            assertEquals(ControlsRoute.SEAT_MEMORY, ControlsNavigation.route(intent))
            assertEquals(slot, ControlsNavigation.driverMemorySlot(intent))
        }
        val invalid = ControlsNavigation.driverMemoryIntent(app, SeatMemorySlot.ONE)
            .putExtra("org.openvoyah.controls.extra.DRIVER_MEMORY_SLOT", 4)
        assertNull(ControlsNavigation.driverMemorySlot(invalid))
        val apps = ControlsNavigation.driverMemoryIntent(app, SeatMemorySlot.TWO).setAction(ControlsNavigation.OPEN_APPS)
        assertNull(ControlsNavigation.driverMemorySlot(apps))
    }

    fun testDriverMemoryGuideBelongsToTheExactLiveEntry() = onMain {
        val saved = SavedStateHandle()
        val model = ControlsNavigationViewModel(saved)
        model.open(ControlsRoute.SETTINGS)
        model.open(ControlsRoute.VEHICLE)
        val backStack = model.state.value.stack
        model.enter(ControlsRoute.SEAT_MEMORY, SeatMemorySlot.ONE)
        val firstEntry = model.state.value
        val first = checkNotNull(model.state.value.driverMemoryGuide)
        assertEquals(SeatMemorySlot.ONE, first.slot)
        assertEquals(SeatMemoryAction.RESTORE, first.action)
        assertFalse(first.launchFailed)
        assertEquals(backStack + ControlsRoute.SEAT_MEMORY, model.state.value.stack)
        assertNull(ControlsNavigationViewModel(saved).state.value.driverMemoryGuide)

        model.enter(ControlsRoute.SEAT_MEMORY, SeatMemorySlot.TWO)
        val secondEntry = model.state.value
        val second = checkNotNull(model.state.value.driverMemoryGuide)
        assertFalse(model.isSeatMemoryEntryCurrent(firstEntry))
        assertFalse(model.isDriverMemoryGuideCurrent(first))
        assertTrue(model.isSeatMemoryEntryCurrent(secondEntry))
        assertTrue(model.isDriverMemoryGuideCurrent(second))
        model.finishDriverMemoryGuide(first, launchFailed = false)
        assertSame(second, model.state.value.driverMemoryGuide)
        model.finishDriverMemoryGuide(second, launchFailed = true)
        val failed = checkNotNull(model.state.value.driverMemoryGuide)
        assertEquals(SeatMemorySlot.TWO, failed.slot)
        assertEquals(SeatMemoryAction.RESTORE, failed.action)
        assertTrue(failed.launchFailed)
        model.finishDriverMemoryGuide(second, launchFailed = false)
        assertSame(failed, model.state.value.driverMemoryGuide)
        model.finishDriverMemoryGuide(failed, launchFailed = false)
        assertNull(model.state.value.driverMemoryGuide)

        model.enter(ControlsRoute.SEAT_MEMORY, SeatMemorySlot.THREE)
        model.open(ControlsRoute.SERVICE_REMINDER)
        model.enter(ControlsRoute.SEAT_MEMORY, SeatMemorySlot.THREE)
        assertEquals(backStack + listOf(ControlsRoute.SERVICE_REMINDER, ControlsRoute.SEAT_MEMORY), model.state.value.stack)
        model.back()
        assertEquals(ControlsRoute.SERVICE_REMINDER, model.state.value.route)
        assertNull(model.state.value.driverMemoryGuide)
        model.open(ControlsRoute.SEAT_MEMORY)
        assertNull(model.state.value.driverMemoryGuide)
    }

    fun testAManualDriverGuideCannotBeReusedOnAnotherVisit() = onMain {
        val saved = SavedStateHandle()
        val model = ControlsNavigationViewModel(saved)
        model.open(ControlsRoute.VEHICLE)
        model.open(ControlsRoute.SEAT_MEMORY)
        val first = model.state.value
        val guide = SeatMemoryPrompt.Factory(SeatMemorySlot.TWO, SeatMemoryAction.SAVE)
        assertTrue(model.showDriverMemoryGuide(first, guide))
        assertFalse(model.showDriverMemoryGuide(first, SeatMemoryPrompt.Factory(guide.slot, guide.action)))
        assertSame(guide, model.state.value.driverMemoryGuide)
        assertNull(ControlsNavigationViewModel(saved).state.value.driverMemoryGuide)
        model.finishDriverMemoryGuide(guide, launchFailed = false)
        assertTrue(model.isSeatMemoryEntryCurrent(first))

        model.back()
        model.open(ControlsRoute.SEAT_MEMORY)
        val second = model.state.value
        assertEquals(first.entry, second.entry)
        assertNotSame(first.seatMemoryVisit, second.seatMemoryVisit)
        assertNull(second.driverMemoryGuide)
        assertFalse(model.showDriverMemoryGuide(first, guide))

        val reopened = SeatMemoryPrompt.Factory(guide.slot, guide.action)
        assertTrue(model.showDriverMemoryGuide(second, reopened))
        model.finishDriverMemoryGuide(guide, launchFailed = false)
        assertSame(reopened, model.state.value.driverMemoryGuide)
        model.finishDriverMemoryGuide(reopened, launchFailed = false)
        assertNull(model.state.value.driverMemoryGuide)
    }

    fun testExplicitAppsActionOverridesConflictingRouteExtras() {
        for (route in ControlsRoute.entries) {
            val intent = ControlsNavigation.intent(app, route).setAction(ControlsNavigation.OPEN_APPS)
            assertEquals("OPEN_APPS must override the caller's $route extra", ControlsRoute.APPS, ControlsNavigation.route(intent))
        }
    }

    fun testLauncherAndMalformedSettingsEntriesSelectSafeRoots() {
        assertEquals(ControlsRoute.APPS, ControlsNavigation.route(Intent(Intent.ACTION_MAIN)))
        assertEquals(ControlsRoute.SETTINGS, ControlsNavigation.route(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
        assertEquals(ControlsRoute.APPS, ControlsNavigation.route(Intent("unrecognized.action")))
        assertEquals(ControlsRoute.SETTINGS, ControlsNavigation.route(Intent(ControlsNavigation.OPEN_SETTINGS)))
        assertEquals(ControlsRoute.SETTINGS, ControlsNavigation.route(Intent(ControlsNavigation.OPEN_SETTINGS)
            .putExtra("org.openvoyah.controls.extra.ROUTE", "REMOVED_ROUTE")))
        for (route in ControlsRoute.entries) {
            assertEquals(route, ControlsNavigation.route(ControlsNavigation.intent(app, route)))
        }
    }

    fun testOpenControlsShortcutUsesSavedEntryAndExplicitIntentsKeepTheirRoute() {
        val appearance = AppearancePreferences(app)
        val original = appearance.load().launcherEntry
        val shortcut = checkNotNull(app.getSystemService(ShortcutManager::class.java)).manifestShortcuts.single { it.id == "open_controls" }
        val launcherIntent = checkNotNull(shortcut.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(ControlsNavigation.START_CONTROLS, launcherIntent.action)
        assertEquals(ComponentName(app, MainActivity::class.java), launcherIntent.component)
        try {
            appearance.setLauncherEntry(LauncherEntry.VEHICLE)
            val launched = instrumentation.startActivitySync(launcherIntent) as MainActivity
            activity = launched
            onMain {
                val navigation = ViewModelProvider(launched)[ControlsNavigationViewModel::class.java]
                assertEquals(listOf(ControlsRoute.APPS, ControlsRoute.VEHICLE), navigation.state.value.stack)
                navigation.back()
                assertEquals(ControlsRoute.APPS, navigation.state.value.route)
                instrumentation.callActivityOnNewIntent(launched, MainActivity.settingsIntent(app))
                assertEquals(ControlsRoute.SETTINGS, navigation.state.value.route)
                instrumentation.callActivityOnNewIntent(launched, MainActivity.appsIntent(app))
                assertEquals(listOf(ControlsRoute.APPS), navigation.state.value.stack)
            }
            appearance.setLauncherEntry(LauncherEntry.SETTINGS)
            onMain {
                instrumentation.callActivityOnNewIntent(launched, launcherIntent)
                val navigation = ViewModelProvider(launched)[ControlsNavigationViewModel::class.java]
                assertEquals(listOf(ControlsRoute.APPS, ControlsRoute.SETTINGS), navigation.state.value.stack)
            }
        } finally {
            appearance.setLauncherEntry(original)
        }
    }

    fun testBackAndRepeatedOpenReuseExistingRoutes() = onMain {
        val model = ControlsNavigationViewModel(SavedStateHandle())
        assertEquals(listOf(ControlsRoute.APPS), model.state.value.stack)
        model.open(ControlsRoute.SETTINGS)
        model.open(ControlsRoute.BUTTONS)
        val opened = model.state.value
        model.open(ControlsRoute.BUTTONS)
        assertSame(opened, model.state.value)
        assertEquals(listOf(ControlsRoute.APPS, ControlsRoute.SETTINGS, ControlsRoute.BUTTONS), opened.stack)
        assertEquals(RouteDirection.FORWARD, opened.direction)
        model.back()
        assertEquals(listOf(ControlsRoute.APPS, ControlsRoute.SETTINGS), model.state.value.stack)
        assertEquals(RouteDirection.BACK, model.state.value.direction)
        model.open(ControlsRoute.VOICE)
        model.open(ControlsRoute.SETTINGS)
        assertEquals(listOf(ControlsRoute.APPS, ControlsRoute.SETTINGS), model.state.value.stack)
        assertEquals(RouteDirection.BACK, model.state.value.direction)
        model.back()
        model.back()
        assertEquals(listOf(ControlsRoute.APPS), model.state.value.stack)
    }

    fun testExplicitEntryResetsTheStackAndNotifiesAnAlreadyOpenRoot() = onMain {
        val model = ControlsNavigationViewModel(SavedStateHandle())
        model.enter(ControlsRoute.SETTINGS)
        model.open(ControlsRoute.VOICE)
        assertEquals(1L, model.state.value.entry)
        model.enter(ControlsRoute.UPDATES)
        assertEquals(ControlsNavigationState(listOf(ControlsRoute.APPS, ControlsRoute.UPDATES), 2L, RouteDirection.REPLACE),
            model.state.value)
        model.enter(ControlsRoute.APPS)
        assertEquals(ControlsNavigationState(listOf(ControlsRoute.APPS), 3L, RouteDirection.REPLACE), model.state.value)
        model.enter(ControlsRoute.APPS)
        assertEquals(4L, model.state.value.entry)
        assertEquals(ControlsRoute.APPS, model.state.value.route)
        model.enter(ControlsRoute.UPDATES)
        assertEquals(ControlsNavigationState(listOf(ControlsRoute.APPS, ControlsRoute.UPDATES), 5L, RouteDirection.REPLACE),
            model.state.value)
    }

    fun testRoutineDesktopAndPaletteAreScopedToTheCurrentEntry() = onMain {
        val saved = SavedStateHandle()
        val model = ControlsNavigationViewModel(saved)
        model.enterFromLauncher(ControlsRoute.APPS, AppGridDesktop.FAVORITES_TWO, AppPalette.DAY)
        assertEquals(AppGridDesktop.FAVORITES_TWO, model.state.value.entryDesktop)
        assertEquals(AppPalette.DAY, model.state.value.temporaryPalette)
        model.open(ControlsRoute.VEHICLE)
        model.back()
        assertEquals(AppGridDesktop.FAVORITES_TWO, model.state.value.entryDesktop)

        val restored = ControlsNavigationViewModel(saved)
        assertEquals(AppGridDesktop.FAVORITES_TWO, restored.state.value.entryDesktop)
        assertEquals(AppPalette.DAY, restored.state.value.temporaryPalette)
        restored.enter(ControlsRoute.SETTINGS)
        assertNull(restored.state.value.entryDesktop)
        assertEquals(AppPalette.DAY, restored.state.value.temporaryPalette)
        restored.runRoutine(ControlsRoute.APPS, AppGridDesktop.FAVORITES_ONE, null)
        assertEquals(AppGridDesktop.FAVORITES_ONE, restored.state.value.entryDesktop)
        assertEquals(AppPalette.DAY, restored.state.value.temporaryPalette)
        restored.clearTemporaryPalette()
        assertNull(restored.state.value.temporaryPalette)

        restored.runRoutine(null, null, AppPalette.DARK)
        assertEquals(AppPalette.DARK, restored.state.value.temporaryPalette)
        restored.enterFromLauncher(ControlsRoute.SETTINGS, null, null)
        assertEquals(ControlsRoute.SETTINGS, restored.state.value.route)
        assertNull(restored.state.value.entryDesktop)
        assertNull(restored.state.value.temporaryPalette)
    }

    fun testSavedRoutesRestoreWithoutDuplicateOrRemovedDestinations() = onMain {
        val saved = SavedStateHandle(mapOf("routes" to arrayListOf("APPS", "SETTINGS", "VOICE", "VOICE", "REMOVED_ROUTE")))
        val model = ControlsNavigationViewModel(saved)
        assertEquals(listOf(ControlsRoute.APPS, ControlsRoute.SETTINGS, ControlsRoute.VOICE), model.state.value.stack)
        model.back()
        assertEquals(arrayListOf("APPS", "SETTINGS"), saved.get<ArrayList<String>>("routes"))
        assertEquals(model.state.value.copy(direction = RouteDirection.REPLACE), ControlsNavigationViewModel(saved).state.value)

        for (invalid in listOf(emptyList(), listOf("SETTINGS", "VOICE"), listOf("REMOVED_ROUTE"))) {
            assertEquals(listOf(ControlsRoute.APPS), ControlsNavigationViewModel(
                SavedStateHandle(mapOf("routes" to ArrayList(invalid)))).state.value.stack)
        }
    }

    fun testNewIntentsReplaceTheEntryInTheSameHostAndRecreationKeepsTheSavedRoute() {
        val launched = instrumentation.startActivitySync(MainActivity.appsIntent(app)) as MainActivity
        activity = launched
        val model = onMain { ViewModelProvider(launched)[ControlsNavigationViewModel::class.java] }
        onMain {
            instrumentation.callActivityOnNewIntent(launched, MainActivity.settingsIntent(app))
            instrumentation.callActivityOnNewIntent(launched, MainActivity.settingsIntent(app))
            assertEquals(listOf(ControlsRoute.APPS, ControlsRoute.SETTINGS), model.state.value.stack)
            model.open(ControlsRoute.BUTTONS)
        }

        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        val recreated = try {
            onMain { launched.recreate() }
            checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5_000)) as MainActivity
        } finally { instrumentation.removeMonitor(monitor) }
        activity = recreated
        instrumentation.waitForIdleSync()
        onMain {
            val retained = ViewModelProvider(recreated)[ControlsNavigationViewModel::class.java]
            assertSame(model, retained)
            assertEquals(listOf(ControlsRoute.APPS, ControlsRoute.SETTINGS, ControlsRoute.BUTTONS), retained.state.value.stack)
            instrumentation.callActivityOnNewIntent(recreated, MainActivity.appsIntent(app))
            assertEquals(listOf(ControlsRoute.APPS), retained.state.value.stack)
            assertEquals(ControlsRoute.APPS, ControlsNavigation.route(recreated.intent))
            assertFalse(recreated.isFinishing)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

}
