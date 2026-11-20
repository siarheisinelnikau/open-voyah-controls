package org.openvoyah.controls.presentation.routines

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.InstalledApps
import org.openvoyah.controls.MainActivity
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.data.apps.DesktopInfoPreferences
import org.openvoyah.controls.data.apps.DesktopLayoutsPreferences
import org.openvoyah.controls.data.apps.DesktopWallpaperPreferences
import org.openvoyah.controls.data.routines.RoutinePreferences
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.data.settings.LocalSettingsArchive
import org.openvoyah.controls.domain.apps.AppGridColumns
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.DesktopInfoCard
import org.openvoyah.controls.domain.apps.DesktopInfoSize
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.domain.settings.LauncherEntry
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsNavigationViewModel
import org.openvoyah.controls.presentation.navigation.ControlsRoute

class RoutinesWorkflowTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext
    private lateinit var activity: MainActivity

    fun testCreateRunSortAndLaunch() {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val stores = listOf(RoutinePreferences.STORE, RoutinePreferences.HISTORY_STORE, AppGridPreferences.STORE, AppearancePreferences.STORE, "updates")
            .associateWith { app.getSharedPreferences(it, Context.MODE_PRIVATE).all }
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        val store = RoutinePreferences(app)
        val appearance = AppearancePreferences(app)
        val commute = "Morning commute"
        val settings = "Settings review"
        val favorite = "Commute"
        val favoriteSlot = UiText.get(R.string.apps_grid_names_with_slot).format(favorite, UiText.get(R.string.apps_desktop_favorites_two))
        try {
            check(app.getSharedPreferences("updates", 0).edit().putBoolean("check_on_startup", false).commit())
            check(app.getSharedPreferences(RoutinePreferences.STORE, 0).edit().remove("settings").commit())
            appearance.setPalette(AppPalette.DARK)
            appearance.setLauncherEntry(LauncherEntry.APP_GRID)
            AppGridPreferences(app).restoreSettings(JSONObject().put("pinnedApps", JSONArray())
                .put("desktops", JSONObject().put("enabled", true).put("defaultDesktop", "main")
                    .put("favorites1", JSONArray()).put("favorites2", JSONArray()))
                .put("desktopNames", JSONObject().put("favorites2", favorite)))
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }

            launch(MainActivity.settingsIntent(app))
            click(UiText.get(R.string.routines_title))
            awaitRoute(ControlsRoute.ROUTINES)
            click(UiText.get(R.string.routines_create))
            enterText(commute)
            choose(UiText.get(R.string.routines_page), favoriteSlot)
            choose(UiText.get(R.string.routines_theme), UiText.get(R.string.appearance_palette_day))
            capture("routine-editor")
            click(UiText.get(R.string.routines_save))
            await("First saved routine") { store.exportSettings().getJSONArray("entries").length() == 1 }
            val firstId = store.exportSettings().getJSONArray("entries").getJSONObject(0).getString("id")

            click(UiText.get(R.string.routines_create))
            enterText(settings)
            choose(UiText.get(R.string.routines_page), UiText.get(R.string.cockpit_settings))
            click(UiText.get(R.string.routines_save))
            await("Newest row first") { rowOrder(commute, settings) == listOf(settings, commute) }
            val secondId = store.exportSettings().getJSONArray("entries").getJSONObject(0).getString("id")
            val sort = UiText.get(R.string.routines_sort_action).format(UiText.get(R.string.routines_sort_newest))
            choose(sort, UiText.get(R.string.routines_sort_name))
            await("Alphabetical rows") { rowOrder(commute, settings) == listOf(commute, settings) }
            click(UiText.get(R.string.routines_edit_named).format(settings))
            click(UiText.get(R.string.routines_pin))
            click(UiText.get(R.string.routines_save))
            await("Pinned settings above alphabetical rows") { rowOrder(commute, settings) == listOf(settings, commute) }
            assertEquals(secondId, store.exportSettings().getJSONArray("pinnedIds").getString(0))
            choose(UiText.get(R.string.routines_startup), commute)
            await("Selected startup identity") { store.exportSettings().opt("startup") == firstId }
            click(UiText.get(R.string.routines_schedule))
            val today = LocalDate.now().dayOfWeek
            val language = Locale.forLanguageTag(UiText.language())
            // Also exclude tomorrow so the test is valid on either side of midnight.
            listOf(today, today.plus(1)).forEach { click(it.getDisplayName(TextStyle.FULL_STANDALONE, language)) }
            click(UiText.get(R.string.routines_limit_hours))
            click(UiText.get(R.string.routines_time_start))
            val ok = app.getString(android.R.string.ok)
            await("Android time picker") {
                val confirm = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.equals(ok, ignoreCase = true) == true }
                confirm?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            await("Time range editor") { nodes().any { it.matches(UiText.get(R.string.routines_schedule)) && it.isVisibleToUser } }
            capture("routine-schedule")
            click(UiText.get(R.string.routines_save_schedule))
            await("Saved daily start and end") { store.exportSettings().optJSONObject("startupWindow")?.getInt("startMinute") == 420 }
            val saved = store.exportSettings()
            assertEquals(600, saved.getJSONObject("startupWindow").getInt("endMinute"))
            val days = saved.getJSONArray("startupDays")
            assertEquals(5, days.length())
            assertTrue((0 until days.length()).none { days.getInt(it) == today.value || days.getInt(it) == today.plus(1).value })
            await("Scheduled routine rows") { rowOrder(commute, settings) == listOf(settings, commute) }
            capture("routines")

            click(UiText.get(R.string.routines_run_named).format(commute))
            awaitRoute(ControlsRoute.APPS)
            await("Named favorite desktop selected") {
                nodes().any { node -> node.matches(favoriteSlot) && node.isVisibleToUser &&
                    generateSequence(node) { it.parent }.any { it.isSelected } }
            }
            await("Daytime presentation") { daytime() }
            assertEquals(AppPalette.DARK, appearance.load().palette)
            assertEquals("main", AppGridPreferences(app).exportSettings().getJSONObject("desktops").getString("defaultDesktop"))

            store.restoreSettings(JSONObject().put("startup", secondId))
            instrumentation.waitForIdleSync()
            assertEquals(ControlsRoute.APPS, route())
            assertTrue(daytime())
            app.startActivity(ControlsNavigation.intent(app, ControlsRoute.BUTTONS))
            awaitRoute(ControlsRoute.BUTTONS)
            finishHost()
            fun launcher() = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launch(launcher())
            awaitRoute(ControlsRoute.APPS)
            await("Saved dark theme after restricted startup") { !daytime() }
            store.restoreSettings(JSONObject().put("startupDays", JSONArray((1..7).toList())).put("startupWindow", JSONObject.NULL))
            // A host recreation retains the current page even though the next launcher entry now qualifies.
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync { activity.recreate() }
                activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5_000)) as MainActivity
            } finally { instrumentation.removeMonitor(monitor) }
            awaitRoute(ControlsRoute.APPS)
            finishHost()
            launch(launcher())
            awaitRoute(ControlsRoute.SETTINGS)
            await("Saved dark theme on next launch") { !daytime() }
            assertEquals(LauncherEntry.APP_GRID, appearance.load().launcherEntry)
        } finally {
            if (::activity.isInitialized && !activity.isDestroyed) finishHost()
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            restoreStores(stores)
        }
    }

    fun testDailyStartupAndForegroundDelay() {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val stores = listOf(RoutinePreferences.STORE, RoutinePreferences.HISTORY_STORE, AppearancePreferences.STORE, "updates")
            .associateWith { app.getSharedPreferences(it, Context.MODE_PRIVATE).all }
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        val store = RoutinePreferences(app)
        val history = app.getSharedPreferences(RoutinePreferences.HISTORY_STORE, 0)
        val id = UUID.randomUUID().toString()
        val name = "Morning settings"
        fun recorded() = history.all.keys.any { it.substringBefore('.') == id }
        fun launcher() = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun notice() = nodes().any { it.isVisibleToUser && it.matches(UiText.get(R.string.routines_stay)) }
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            check(app.getSharedPreferences("updates", 0).edit().putBoolean("check_on_startup", false).commit())
            AppearancePreferences(app).setLauncherEntry(LauncherEntry.APP_GRID)
            store.restoreSettings(JSONObject().put("entries", JSONArray().put(JSONObject().put("id", id).put("name", name)
                .put("target", "settings"))).put("sort", "newest").put("startup", id)
                .put("startupDays", JSONArray((1..7).toList())).put("startupWindow", JSONObject.NULL).put("pinnedIds", JSONArray())
                .put("startupDelaySeconds", 0).put("startupFrequency", "every").put("startupEnabled", true))
            launch(ControlsNavigation.intent(app, ControlsRoute.ROUTINES))
            click(UiText.get(R.string.routines_schedule))
            choose(UiText.get(R.string.routines_frequency), UiText.get(R.string.routines_once_daily))
            choose(UiText.get(R.string.routines_delay), UiText.get(R.string.routines_delay_seconds).format(8))
            capture("routine-startup-options")
            click(UiText.get(R.string.routines_save_schedule))
            await("Saved daily launch and eight-second delay") {
                val saved = store.exportSettings()
                saved.getString("startupFrequency") == "daily" && saved.getInt("startupDelaySeconds") == 8
            }
            assertFalse(recorded())
            val startupSwitch = UiText.get(R.string.routines_startup_enabled)
            click(startupSwitch)
            await("Paused routine still selected") {
                val saved = store.exportSettings()
                !saved.getBoolean("startupEnabled") && saved.getString("startup") == id &&
                    saved.getString("startupFrequency") == "daily" && saved.getInt("startupDelaySeconds") == 8
            }
            val paused = UiText.get(R.string.routines_startup_paused)
            performOnScreen(paused) { nodes().any { it.isVisibleToUser && it.matches(paused) } }
            capture("routine-startup-paused")
            finishHost()

            launch(launcher())
            awaitRoute(ControlsRoute.APPS)
            assertFalse(notice())
            assertFalse(recorded())
            finishHost()

            launch(ControlsNavigation.intent(app, ControlsRoute.ROUTINES))
            click(UiText.get(R.string.routines_run_named).format(name))
            awaitRoute(ControlsRoute.SETTINGS)
            assertFalse(recorded())
            finishHost()

            launch(ControlsNavigation.intent(app, ControlsRoute.ROUTINES))
            click(startupSwitch)
            await("Resumed daily launch") { store.exportSettings().getBoolean("startupEnabled") }
            finishHost()

            launch(launcher())
            awaitRoute(ControlsRoute.APPS)
            await("Countdown above the usual launcher") { notice() }
            capture("routine-startup-pending")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Normal input canceled the countdown") { !notice() }
            assertFalse(recorded())
            store.restoreSettings(JSONObject().put("startupDelaySeconds", 3))
            finishHost()

            launch(launcher())
            await("Pending before explicit intent") { notice() }
            app.startActivity(ControlsNavigation.intent(app, ControlsRoute.BUTTONS))
            awaitRoute(ControlsRoute.BUTTONS)
            assertFalse(recorded())
            finishHost()

            launch(launcher())
            await("Pending before recreation") { notice() }
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync { activity.recreate() }
                activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5_000)) as MainActivity
            } finally { instrumentation.removeMonitor(monitor) }
            awaitRoute(ControlsRoute.APPS)
            SystemClock.sleep(3_250)
            assertEquals(ControlsRoute.APPS, route())
            assertFalse(recorded())
            finishHost()

            launch(launcher())
            awaitRoute(ControlsRoute.APPS)
            awaitRoute(ControlsRoute.SETTINGS)
            await("Day recorded only after normal application", ::recorded)
            val successful = history.all
            finishHost()
            if (store.startupPlan() == null) {
                launch(launcher())
                awaitRoute(ControlsRoute.APPS)
                assertFalse(notice())
                finishHost()
            }
            launch(ControlsNavigation.intent(app, ControlsRoute.ROUTINES))
            click(UiText.get(R.string.routines_run_named).format(name))
            awaitRoute(ControlsRoute.SETTINGS)
            assertEquals(successful, history.all)
        } finally {
            if (::activity.isInitialized && !activity.isDestroyed) finishHost()
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            restoreStores(stores)
        }
    }

    fun testInstalledAppRoutineRunsManuallyAndOnlyOnceOnAnEligibleLauncherEntry() {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val stores = listOf(RoutinePreferences.STORE, RoutinePreferences.HISTORY_STORE, AppearancePreferences.STORE, "updates")
            .associateWith { app.getSharedPreferences(it, Context.MODE_PRIVATE).all }
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        val store = RoutinePreferences(app)
        val history = app.getSharedPreferences(RoutinePreferences.HISTORY_STORE, 0)
        val target = InstalledApps.list(app).first { it.component().packageName in
            setOf("com.android.calendar", "com.google.android.calendar", "com.android.calculator2") }
        val component = target.component().flattenToString()
        val name = "Open installed app"
        fun foreground(packageName: String) = automation.rootInActiveWindow?.packageName?.toString() == packageName
        fun launcher() = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun waitForTarget() = await("Selected installed app in the foreground") { foreground(target.component().packageName) }
        fun returnToControls(route: ControlsRoute) {
            assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            await("Controls window after returning from the selected app") { foreground(app.packageName) && activity.hasWindowFocus() }
            awaitRoute(route)
        }
        fun countdown() = nodes().any { it.isVisibleToUser && it.matches(UiText.get(R.string.routines_stay)) }
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            check(app.getSharedPreferences("updates", 0).edit().putBoolean("check_on_startup", false).commit())
            check(app.getSharedPreferences(RoutinePreferences.STORE, 0).edit().remove("settings").commit())
            AppearancePreferences(app).setLauncherEntry(LauncherEntry.APP_GRID)
            launch(ControlsNavigation.intent(app, ControlsRoute.ROUTINES))
            click(UiText.get(R.string.routines_create))
            enterText(name)
            choose(UiText.get(R.string.routines_page), UiText.get(R.string.routines_current_page))
            click(UiText.get(R.string.routines_app))
            await("App picker search field") {
                nodes().any { it.isVisibleToUser && it.matches(UiText.get(R.string.ui_113)) }
            }
            await("Scroll the installed apps before filtering") {
                nodes().filter { it.isVisibleToUser && it.isScrollable }
                    .any { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            }
            enterText(component)
            try {
                await("Only the matched app occupies the filtered picker") {
                    clickable(target.label()) != null &&
                        nodes().none { it.isVisibleToUser && it.matches(UiText.get(R.string.routines_app_none)) }
                }
            } finally {
                capture("routine-app-search")
            }
            click(target.label())
            performOnScreen(UiText.get(R.string.routines_save)) {
                clickable(UiText.get(R.string.routines_save)) != null &&
                    nodes().any { it.isVisibleToUser && it.matches(target.label()) }
            }
            capture("routine-installed-app-editor")
            click(UiText.get(R.string.routines_save))
            await("Exact installed app component was saved") {
                store.exportSettings().getJSONArray("entries").optJSONObject(0)?.optString("installedApp") == component
            }
            val entry = store.exportSettings().getJSONArray("entries").getJSONObject(0)
            val id = entry.getString("id")
            assertEquals("current", entry.getString("target"))
            val initialClaims = history.all

            click(UiText.get(R.string.routines_run_named).format(name))
            waitForTarget()
            returnToControls(ControlsRoute.ROUTINES)
            assertEquals(initialClaims, history.all)
            store.restoreSettings(JSONObject().put("entries", JSONArray().put(entry.put("target", "buttons")))
                .put("startup", id).put("startupFrequency", "daily").put("startupDelaySeconds", 3))
            finishHost()

            launch(launcher())
            await("Delay before the external app opens", ::countdown)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Normal input cancels the scheduled app launch") { !countdown() }
            SystemClock.sleep(3_250)
            assertTrue(foreground(app.packageName))
            awaitRoute(ControlsRoute.APPS)
            assertEquals(initialClaims, history.all)
            finishHost()

            store.restoreSettings(JSONObject().put("startupDelaySeconds", 0))
            launch(launcher())
            waitForTarget()
            returnToControls(ControlsRoute.BUTTONS)
            val claimed = history.all
            assertTrue(claimed.keys.any { it.substringBefore('.') == id })
            finishHost()
            if (store.startupPlan() == null) {
                launch(launcher())
                awaitRoute(ControlsRoute.APPS)
                await("Usual page visible after exhausting the daily launch") {
                    foreground(app.packageName) && activity.hasWindowFocus()
                }
                assertFalse(countdown())
                assertEquals(claimed, history.all)
            }
        } finally {
            if (::activity.isInitialized && !activity.isDestroyed) finishHost()
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            restoreStores(stores)
        }
    }

    fun testUnavailableRoutineAppRemainsVisibleAndCanBeCleared() {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val stores = listOf(RoutinePreferences.STORE, RoutinePreferences.HISTORY_STORE, "updates")
            .associateWith { app.getSharedPreferences(it, Context.MODE_PRIVATE).all }
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        val store = RoutinePreferences(app)
        val id = UUID.randomUUID().toString()
        val name = "Unavailable app"
        val component = "org.example.unavailable/org.example.unavailable.MainActivity"
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            check(app.getSharedPreferences("updates", 0).edit().putBoolean("check_on_startup", false).commit())
            store.restoreSettings(JSONObject().put("entries", JSONArray().put(JSONObject().put("id", id).put("name", name)
                .put("target", "settings").put("installedApp", component))).put("startup", JSONObject.NULL))
            launch(ControlsNavigation.intent(app, ControlsRoute.ROUTINES))
            val unavailable = UiText.get(R.string.routines_app_summary).format(UiText.get(R.string.routines_app_unavailable).format(component))
            await("Saved component shown as unavailable") { nodes().any { it.isVisibleToUser && it.matches(unavailable) } }
            click(UiText.get(R.string.routines_run_named).format(name))
            awaitRoute(ControlsRoute.SETTINGS)
            val notice = UiText.get(R.string.routines_app_missing_notice).format(component)
            await("Missing app feedback on the prepared page") { nodes().any { it.isVisibleToUser && it.matches(notice) } }
            click(UiText.get(R.string.routines_app_dismiss_notice))
            click(UiText.get(R.string.routines_title))
            awaitRoute(ControlsRoute.ROUTINES)
            click(UiText.get(R.string.routines_edit_named).format(name))
            click(UiText.get(R.string.routines_app))
            click(UiText.get(R.string.routines_app_none))
            click(UiText.get(R.string.routines_save))
            await("Only the external app action was cleared") {
                val entry = store.exportSettings().getJSONArray("entries").getJSONObject(0)
                entry.has("installedApp") && entry.isNull("installedApp") && entry.getString("target") == "settings"
            }
        } finally {
            if (::activity.isInitialized && !activity.isDestroyed) finishHost()
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            restoreStores(stores)
        }
    }

    fun testSavedDesktopLayoutRoutineUsesItsIdAndCanBeRepairedAfterRemoval() {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val stores = listOf(RoutinePreferences.STORE, DesktopLayoutsPreferences.STORE, AppGridPreferences.STORE,
            DesktopInfoPreferences.STORE, DesktopWallpaperPreferences.STORE, "updates")
            .associateWith { app.getSharedPreferences(it, Context.MODE_PRIVATE).all }
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        val routines = RoutinePreferences(app)
        val archive = LocalSettingsArchive(app)
        val layouts = DesktopLayoutsPreferences(app)
        val grid = AppGridPreferences(app)
        val cards = DesktopInfoPreferences(app)
        val routineName = "Weekend drive"
        val savedName = "Weekend maps"
        val renamed = "Weekend navigation"
        val layoutControl = UiText.get(R.string.routines_layout)
        val save = UiText.get(R.string.routines_save)
        val run = UiText.get(R.string.routines_run_named).format(routineName)
        fun visible(label: String) = nodes().any { it.isVisibleToUser && it.matches(label) }
        fun entry() = routines.exportSettings().getJSONArray("entries").optJSONObject(0)

        try {
            check(app.getSharedPreferences("updates", 0).edit().putBoolean("check_on_startup", false).commit())
            check(app.getSharedPreferences(RoutinePreferences.STORE, 0).edit().remove("settings").commit())
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val savedDesktop = runBlocking {
                layouts.restoreSettings(JSONObject())
                grid.setColumns(AppGridColumns.FOUR)
                cards.set(AppGridDesktop.MAIN, DesktopInfoCard.CLOCK, DesktopInfoSize.WIDE)
                archive.captureDesktopLayout().toString().also { layouts.save(savedName) }
            }
            val preset = runBlocking { layouts.observe().first().getOrThrow().single() }
            runBlocking {
                grid.setColumns(AppGridColumns.SIX)
                cards.set(AppGridDesktop.MAIN, DesktopInfoCard.CLOCK, null)
            }
            val changedDesktop = archive.captureDesktopLayout().toString()
            assertFalse(savedDesktop == changedDesktop)

            launch(ControlsNavigation.intent(app, ControlsRoute.ROUTINES))
            awaitRoute(ControlsRoute.ROUTINES)
            click(UiText.get(R.string.routines_create))
            enterText(routineName)
            choose(UiText.get(R.string.routines_page), UiText.get(R.string.routines_current_page))
            click(layoutControl)
            await("Saved desktop layout in the picker") { visible(savedName) && visible(UiText.get(R.string.routines_layout_hint)) }
            capture("routine-layout-picker")
            click(savedName)
            await("Selected layout and full Save button are visible together") {
                val button = clickable(save)
                val height = Rect().also { button?.getBoundsInScreen(it) }.height()
                visible(savedName) && !visible(UiText.get(R.string.ui_89)) &&
                    height >= (48 * app.resources.displayMetrics.density).toInt()
            }
            capture("routine-layout-editor")
            click(save)
            await("Saved layout-only routine") {
                entry()?.let { it.optString("desktopLayoutId") == preset.id && it.getString("target") == "current" &&
                    !it.has("palette") && it.isNull("installedApp") } == true
            }
            assertEquals(changedDesktop, archive.captureDesktopLayout().toString())

            runBlocking { layouts.rename(preset.id, renamed) }
            await("Renamed layout shown for the same routine") {
                visible(UiText.get(R.string.routines_layout_summary).format(renamed))
            }
            assertEquals(preset.id, entry()?.getString("desktopLayoutId"))
            click(run)
            await("Completed layout action") {
                visible(UiText.get(R.string.routines_ran).format(routineName))
            }
            assertEquals(savedDesktop, archive.captureDesktopLayout().toString())
            assertEquals(ControlsRoute.ROUTINES, route())

            runBlocking {
                grid.setColumns(AppGridColumns.SIX)
                cards.set(AppGridDesktop.MAIN, DesktopInfoCard.CLOCK, null)
                layouts.delete(preset.id)
            }
            await("Removed layout reported on the routine") { visible(UiText.get(R.string.routines_layout_missing)) }
            click(run)
            await("Unavailable layout reported after a failed run") { visible(UiText.get(R.string.routines_layout_unavailable)) }
            assertEquals(changedDesktop, archive.captureDesktopLayout().toString())
            assertEquals(ControlsRoute.ROUTINES, route())

            click(UiText.get(R.string.routines_edit_named).format(routineName))
            choose(layoutControl, UiText.get(R.string.routines_layout_keep))
            choose(UiText.get(R.string.routines_page), UiText.get(R.string.cockpit_settings))
            click(save)
            await("Routine repaired with an explicit empty layout") {
                entry()?.let { it.has("desktopLayoutId") && it.isNull("desktopLayoutId") && it.getString("target") == "settings" } == true
            }
            click(run)
            awaitRoute(ControlsRoute.SETTINGS)
            assertEquals(changedDesktop, archive.captureDesktopLayout().toString())
        } finally {
            if (::activity.isInitialized && !activity.isDestroyed) finishHost()
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            restoreStores(stores)
        }
    }

    fun testLauncherAppliesTheLayoutBeforeOpeningTheAppAndKeepsFailedAttemptsRetryable() {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val stores = listOf(RoutinePreferences.STORE, RoutinePreferences.HISTORY_STORE, AppearancePreferences.STORE,
            DesktopLayoutsPreferences.STORE, AppGridPreferences.STORE, DesktopInfoPreferences.STORE,
            DesktopWallpaperPreferences.STORE, "updates").associateWith { app.getSharedPreferences(it, 0).all }
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        val routines = RoutinePreferences(app)
        val archive = LocalSettingsArchive(app)
        val layouts = DesktopLayoutsPreferences(app)
        val grid = AppGridPreferences(app)
        val cards = DesktopInfoPreferences(app)
        val history = app.getSharedPreferences(RoutinePreferences.HISTORY_STORE, 0)
        val target = InstalledApps.list(app).first { it.component().packageName in
            setOf("com.android.calendar", "com.google.android.calendar", "com.android.calculator2") }
        val id = UUID.randomUUID().toString()
        val stay = UiText.get(R.string.routines_stay)
        fun launcher() = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun visible(label: String) = nodes().any { it.isVisibleToUser && it.matches(label) }
        fun recorded() = history.all.keys.any { it.substringBefore('.') == id }
        fun inForeground(packageName: String) = automation.rootInActiveWindow?.packageName?.toString() == packageName

        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            check(app.getSharedPreferences("updates", 0).edit().putBoolean("check_on_startup", false).commit())
            AppearancePreferences(app).setLauncherEntry(LauncherEntry.APP_GRID)
            val selectedDesktop = runBlocking {
                layouts.restoreSettings(JSONObject())
                grid.setColumns(AppGridColumns.FOUR)
                cards.set(AppGridDesktop.MAIN, DesktopInfoCard.CLOCK, DesktopInfoSize.WIDE)
                archive.captureDesktopLayout().toString().also { layouts.save("Startup maps") }
            }
            val layoutLibrary = layouts.exportSettings()
            val preset = runBlocking { layouts.observe().first().getOrThrow().single() }
            val initialDesktop = runBlocking {
                grid.setColumns(AppGridColumns.SIX)
                cards.set(AppGridDesktop.MAIN, DesktopInfoCard.CLOCK, null)
                archive.captureDesktopLayout().toString()
            }
            assertFalse(initialDesktop == selectedDesktop)
            routines.restoreSettings(JSONObject().put("entries", JSONArray().put(JSONObject()
                .put("id", id).put("name", "Startup layout").put("target", "settings")
                .put("desktopLayoutId", preset.id).put("installedApp", target.component().flattenToString())))
                .put("startup", id).put("startupDays", JSONArray((1..7).toList()))
                .put("startupWindow", JSONObject.NULL).put("startupFrequency", "daily")
                .put("startupDelaySeconds", 8).put("startupEnabled", true))

            launch(launcher())
            await("Layout startup countdown") { visible(stay) }
            click(stay)
            await("Cancelled layout startup") { !visible(stay) }
            assertFalse(recorded())
            assertEquals(initialDesktop, archive.captureDesktopLayout().toString())
            awaitRoute(ControlsRoute.APPS)
            finishHost()

            routines.restoreSettings(JSONObject().put("startupDelaySeconds", 0))
            runBlocking { layouts.delete(preset.id) }
            launch(launcher())
            await("Unavailable layout reported before any later action") { visible(UiText.get(R.string.routines_layout_unavailable)) }
            assertFalse(recorded())
            assertTrue(inForeground(app.packageName))
            assertEquals(initialDesktop, archive.captureDesktopLayout().toString())
            awaitRoute(ControlsRoute.APPS)
            finishHost()

            layouts.restoreSettings(layoutLibrary)
            launch(launcher())
            await("Selected app opened after the saved layout") { inForeground(target.component().packageName) }
            assertEquals(selectedDesktop, archive.captureDesktopLayout().toString())
            assertTrue(recorded())
            assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            await("Controls available again") { inForeground(app.packageName) && activity.hasWindowFocus() }
            awaitRoute(ControlsRoute.SETTINGS)
            finishHost()

            if (routines.startupRequest() == null) {
                launch(launcher())
                awaitRoute(ControlsRoute.APPS)
                assertTrue(inForeground(app.packageName))
                assertFalse(visible(stay))
            }
        } finally {
            if (::activity.isInitialized && !activity.isDestroyed) finishHost()
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            restoreStores(stores)
        }
    }

    private fun restoreStores(stores: Map<String, Map<String, *>>) {
        stores.forEach { (name, before) ->
            val editor = app.getSharedPreferences(name, 0).edit().clear()
            before.forEach { (key, value) -> when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                else -> error("Unexpected fixture setting in $name")
            } }
            check(editor.commit())
        }
    }

    private fun launch(intent: Intent) {
        activity = instrumentation.startActivitySync(intent) as MainActivity
        instrumentation.waitForIdleSync()
        await("Laid out host") { activity.window.decorView.isLaidOut }
    }

    private fun finishHost() {
        instrumentation.runOnMainSync { activity.finish() }
        await("Finished host") { activity.isDestroyed }
    }

    private fun route(): ControlsRoute? {
        var value: ControlsRoute? = null
        instrumentation.runOnMainSync { value = ViewModelProvider(activity)[ControlsNavigationViewModel::class.java].state.value.route }
        return value
    }

    private fun awaitRoute(expected: ControlsRoute) = await("Route $expected") { route() == expected }

    private fun daytime(): Boolean {
        var value = false
        instrumentation.runOnMainSync {
            val window = activity.window
            value = WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
        }
        return value
    }

    private fun rowOrder(first: String, second: String): List<String> {
        var result: List<String> = emptyList()
        instrumentation.runOnMainSync {
            fun root(view: View): ViewRootForTest? {
                if (view is ViewRootForTest) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) root(view.getChildAt(i))?.let { return it }
                return null
            }
            val node = root(activity.window.decorView)?.semanticsOwner?.rootSemanticsNode ?: return@runOnMainSync
            result = buildList {
                fun collect(item: SemanticsNode) {
                    item.config.getOrNull(SemanticsProperties.Text)?.forEach { if (it.text == first || it.text == second) add(it.text) }
                    item.children.forEach(::collect)
                }
                collect(node)
            }.distinct()
        }
        return result
    }

    private fun enterText(value: String) {
        await("Editable field in the active window") {
            val field = nodes().firstOrNull { it.isEditable && it.isVisibleToUser }
            field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }) == true
        }
        instrumentation.waitForIdleSync()
    }

    private fun choose(control: String, option: String) {
        click(control)
        await("Choice dialog for $control") { nodes().any { it.matches(UiText.get(R.string.ui_89)) && it.isVisibleToUser } }
        click(option)
        await("Closed choice dialog") { nodes().none { it.matches(UiText.get(R.string.ui_89)) && it.isVisibleToUser } }
    }

    private fun nodes(): Sequence<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.windows.firstOrNull { it.isActive }?.root
            ?: instrumentation.uiAutomation.rootInActiveWindow ?: return emptySequence()
        fun walk(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            if (!node.refresh()) return@sequence
            yield(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { yieldAll(walk(it)) }
        }
        return walk(root)
    }

    private fun AccessibilityNodeInfo.matches(label: String): Boolean = text?.toString() == label || contentDescription?.toString() == label

    private fun clickable(label: String): AccessibilityNodeInfo? = nodes().filter { it.matches(label) && it.isVisibleToUser }
        .flatMap { generateSequence(it) { node -> node.parent } }
        .firstOrNull { it.isVisibleToUser && it.isEnabled && it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK } }

    private fun click(label: String) = performOnScreen(label) {
        clickable(label)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun performOnScreen(label: String, action: () -> Boolean) {
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        var previous = ""
        var nextScroll = SystemClock.uptimeMillis() + 450
        await("Find and use $label") {
            if (action()) return@await true
            if (SystemClock.uptimeMillis() < nextScroll) return@await false
            nextScroll = SystemClock.uptimeMillis() + 400
            val tree = nodes().toList()
            val signature = tree.filter { it.isVisibleToUser && it.text != null }.joinToString { node ->
                node.text.toString() + Rect().also(node::getBoundsInScreen).top
            }
            if (signature == previous) direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            previous = signature
            val show = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
            val hidden = tree.firstOrNull { it.matches(label) && it.actionList.any { action -> action.id == show } }
            if (hidden?.performAction(show) != true)
                tree.filter { it.isScrollable && it.isVisibleToUser }.maxByOrNull { node ->
                    val b = Rect().also(node::getBoundsInScreen)
                    b.height().toLong() * b.width()
                }?.performAction(direction)
            false
        }
        instrumentation.waitForIdleSync()
    }

    private fun await(description: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        do {
            if (condition()) return
            SystemClock.sleep(60)
        } while (SystemClock.uptimeMillis() < until)
        val visible = nodes().filter { it.isVisibleToUser }.mapNotNull { it.contentDescription ?: it.text }.distinct().take(18).toList()
        fail("Timed out waiting for $description. Visible controls: $visible")
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(330)
        val directory = File(app.filesDir, "ui-audit").apply { check(isDirectory || mkdirs()) }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
