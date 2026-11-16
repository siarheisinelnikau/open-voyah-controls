package org.openvoyah.controls.presentation.navigation

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.test.InstrumentationTestCase
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.MainActivity

/** Installed aliases and entry intents retain one normal host and an explicit saved back stack. */
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

    fun testCompatibilityAliasesRetainTheHostAndExportBoundaries() {
        val packages = app.packageManager
        for ((alias, _) in aliases) {
            val info = packages.getActivityInfo(component(alias), 0)
            assertEquals("Alias $alias has a different host", MainActivity::class.java.name, info.targetActivity)
            assertEquals("Alias $alias changed its caller boundary", alias == "VoiceActivity", info.exported)
        }
        val direct = packages.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertTrue(direct.exported)
        assertNull(direct.targetActivity)

        val microphone = packages.getActivityInfo(component("PanelVoiceActivity"), 0)
        assertEquals("${app.packageName}.QuickVoiceActivity", microphone.targetActivity)
        assertTrue(microphone.exported)
        assertEquals(Manifest.permission.WRITE_SECURE_SETTINGS, microphone.permission)
        val quickVoice = packages.getActivityInfo(component("QuickVoiceActivity"), 0)
        assertFalse(quickVoice.exported)
        val setup = packages.getActivityInfo(component("SetupActivity"), 0)
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

    fun testCompatibilityAliasesOverrideConflictingActionsAndRouteExtras() {
        for ((alias, expected) in aliases) {
            val intent = ControlsNavigation.intent(app, ControlsRoute.UPDATES)
                .setComponent(component(alias)).setAction(ControlsNavigation.OPEN_APPS)
            assertEquals("Alias $alias selected the caller's conflicting route", expected, ControlsNavigation.route(intent))
        }
    }

    fun testLauncherAndMalformedSettingsEntriesSelectSafeRoots() {
        assertEquals(ControlsRoute.APPS, ControlsNavigation.route(Intent(Intent.ACTION_MAIN)))
        assertEquals(ControlsRoute.APPS, ControlsNavigation.route(Intent("unrecognized.action")))
        assertEquals(ControlsRoute.SETTINGS, ControlsNavigation.route(Intent(ControlsNavigation.OPEN_SETTINGS)))
        assertEquals(ControlsRoute.SETTINGS, ControlsNavigation.route(Intent(ControlsNavigation.OPEN_SETTINGS)
            .putExtra("org.openvoyah.controls.extra.ROUTE", "REMOVED_ROUTE")))
        for (route in ControlsRoute.entries) {
            assertEquals(route, ControlsNavigation.route(ControlsNavigation.intent(app, route)))
        }
    }

    fun testBackAndRepeatedOpenReuseExistingRoutes() = onMain {
        val model = ControlsNavigationViewModel(SavedStateHandle())
        assertEquals(listOf(ControlsRoute.APPS), model.stack.value)
        model.open(ControlsRoute.SETTINGS)
        model.open(ControlsRoute.BUTTONS)
        model.open(ControlsRoute.BUTTONS)
        assertEquals(listOf(ControlsRoute.APPS, ControlsRoute.SETTINGS, ControlsRoute.BUTTONS), model.stack.value)
        model.back()
        assertEquals(listOf(ControlsRoute.APPS, ControlsRoute.SETTINGS), model.stack.value)
        model.open(ControlsRoute.VOICE)
        model.open(ControlsRoute.SETTINGS)
        assertEquals(listOf(ControlsRoute.APPS, ControlsRoute.SETTINGS), model.stack.value)
        model.back()
        model.back()
        assertEquals(listOf(ControlsRoute.APPS), model.stack.value)
    }

    fun testExplicitEntryResetsTheStackAndNotifiesAnAlreadyOpenRoot() = onMain {
        val model = ControlsNavigationViewModel(SavedStateHandle())
        model.enter(ControlsRoute.SETTINGS)
        model.open(ControlsRoute.VOICE)
        assertEquals(1L, model.entries.value)
        model.enter(ControlsRoute.APPS)
        assertEquals(listOf(ControlsRoute.APPS), model.stack.value)
        assertEquals(2L, model.entries.value)
        model.enter(ControlsRoute.APPS)
        assertEquals(listOf(ControlsRoute.APPS), model.stack.value)
        assertEquals(3L, model.entries.value)
        model.enter(ControlsRoute.UPDATES)
        assertEquals(listOf(ControlsRoute.APPS, ControlsRoute.UPDATES), model.stack.value)
    }

    fun testSavedRoutesRestoreWithoutDuplicateOrRemovedDestinations() = onMain {
        val saved = SavedStateHandle(mapOf("routes" to arrayListOf("APPS", "SETTINGS", "VOICE", "VOICE", "REMOVED_ROUTE")))
        val model = ControlsNavigationViewModel(saved)
        assertEquals(listOf(ControlsRoute.APPS, ControlsRoute.SETTINGS, ControlsRoute.VOICE), model.stack.value)
        model.back()
        assertEquals(arrayListOf("APPS", "SETTINGS"), saved.get<ArrayList<String>>("routes"))
        assertEquals(model.stack.value, ControlsNavigationViewModel(saved).stack.value)

        for (invalid in listOf(emptyList(), listOf("SETTINGS", "VOICE"), listOf("REMOVED_ROUTE"))) {
            assertEquals(listOf(ControlsRoute.APPS), ControlsNavigationViewModel(
                SavedStateHandle(mapOf("routes" to ArrayList(invalid)))).stack.value)
        }
    }

    fun testNewIntentsReplaceTheEntryInTheSameHostAndRecreationKeepsTheSavedRoute() {
        val launched = instrumentation.startActivitySync(MainActivity.appsIntent(app)) as MainActivity
        activity = launched
        val model = onMain { ViewModelProvider(launched)[ControlsNavigationViewModel::class.java] }
        onMain {
            instrumentation.callActivityOnNewIntent(launched, MainActivity.settingsIntent(app))
            instrumentation.callActivityOnNewIntent(launched, MainActivity.settingsIntent(app))
            assertEquals(listOf(ControlsRoute.APPS, ControlsRoute.SETTINGS), model.stack.value)
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
            assertEquals(listOf(ControlsRoute.APPS, ControlsRoute.SETTINGS, ControlsRoute.BUTTONS), retained.stack.value)
            instrumentation.callActivityOnNewIntent(recreated, MainActivity.appsIntent(app))
            assertEquals(listOf(ControlsRoute.APPS), retained.stack.value)
            assertEquals(ControlsRoute.APPS, ControlsNavigation.route(recreated.intent))
            assertFalse(recreated.isFinishing)
        }
    }

    private fun component(alias: String) = ComponentName(app.packageName, "${app.packageName}.$alias")

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        val aliases = linkedMapOf("AppsActivity" to ControlsRoute.APPS, "VoiceActivity" to ControlsRoute.VOICE,
            "DisplayActivity" to ControlsRoute.DISPLAY, "VehicleSettingsActivity" to ControlsRoute.VEHICLE,
            "FirmwareActivity" to ControlsRoute.FIRMWARE, "ClusterActivity" to ControlsRoute.CLUSTER,
            "UpdatesActivity" to ControlsRoute.UPDATES)
    }
}
