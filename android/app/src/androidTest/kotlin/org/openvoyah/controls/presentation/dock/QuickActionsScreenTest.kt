package org.openvoyah.controls.presentation.dock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.ColorUtils
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.InstalledApps
import org.openvoyah.controls.MainActivity
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.data.apps.DesktopInfoPreferences
import org.openvoyah.controls.data.dock.QuickActionPreferences
import org.openvoyah.controls.data.routines.RoutinePreferences
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.data.settings.LocalSettingsArchive
import org.openvoyah.controls.data.vehicle.HeadlightMonitoringPreferences
import org.openvoyah.controls.data.vehicle.LocalHeadlightHistory
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.dock.QuickAction
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.routines.RoutineLibrary
import org.openvoyah.controls.domain.routines.RoutinePlan
import org.openvoyah.controls.domain.routines.RoutineTarget
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.domain.settings.AppTextSize
import org.openvoyah.controls.domain.vehicle.EarlierHeadlightReport
import org.openvoyah.controls.domain.vehicle.ExteriorLamp
import org.openvoyah.controls.domain.vehicle.HeadlightFeedback
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.HeadlightMonitor
import org.openvoyah.controls.domain.vehicle.HeadlightObservation
import org.openvoyah.controls.domain.vehicle.LampActivity
import org.openvoyah.controls.domain.vehicle.LampObservation
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsNavigationViewModel
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.vehicle.VEHICLE_SETTINGS_MODEL_KEY
import org.openvoyah.controls.presentation.vehicle.VehicleSettingsViewModel
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

class QuickActionsScreenTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext
    private lateinit var activity: MainActivity

    fun testDockReservesSpaceAndKeepsNavigationReachable() = withCleanDock {
        activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
        await("Dock settings") { hasText(R.string.quick_actions_enabled) && regions()?.pane != null }
        val full = checkNotNull(regions()?.pane)
        assertNull(regions()?.bar)
        click(UiText.get(R.string.quick_actions_enabled))
        await("Enabled dock") { regions()?.bar != null }
        val density = app.resources.displayMetrics.density
        val wide = full.width / density >= 850 && app.resources.configuration.fontScale <= 1.25f
        assertReserved(wide, left = false, full)
        if (wide) capture("quick-actions-settings")

        click(destination(R.string.appearance_app_grid))
        awaitRoute(ControlsRoute.APPS)
        await("App grid content") { hasText(R.string.ui_111) }
        assertReserved(wide, left = false, full)
        capture(if (wide) "quick-actions" else "quick-actions-compact")

        val buttons = destination(R.string.cockpit_buttons)
        val hit = android.graphics.Rect()
        checkNotNull(control(buttons)).getBoundsInScreen(hit)
        assertTrue("Shortcut target $hit must be at least 48 dp", hit.width() + 1 >= 48 * density && hit.height() + 1 >= 48 * density)
        click(buttons)
        awaitRoute(ControlsRoute.BUTTONS)
        click(UiText.get(R.string.cockpit_back))
        awaitRoute(ControlsRoute.APPS)

        val preferences = QuickActionPreferences(app)
        preferences.restoreSettings(JSONObject().put("side", "left").put("showLabels", false)
            .put("actions", JSONArray(listOf("settings", "buttons", "apps"))))
        await("Reordered icon shortcuts") {
            val bar = regions()?.bar ?: return@await false
            val first = control(destination(R.string.cockpit_settings)) ?: return@await false
            val second = control(buttons) ?: return@await false
            val a = android.graphics.Rect().also(first::getBoundsInScreen)
            val b = android.graphics.Rect().also(second::getBoundsInScreen)
            if (wide) bar.right < full.center.x && a.centerY() < b.centerY() else a.centerX() < b.centerX()
        }
        assertReserved(wide, left = true, full)
        click(buttons)
        awaitRoute(ControlsRoute.BUTTONS)
        preferences.restoreSettings(JSONObject().put("area", "apps"))
        await("Full page outside Apps") { regions()?.let { it.bar == null && it.pane == full } == true }
        click(UiText.get(R.string.cockpit_back))
        awaitRoute(ControlsRoute.APPS)
        await("Dock returns to Apps") { regions()?.bar != null }
        preferences.restoreSettings(JSONObject().put("enabled", false))
        await("Disabled dock releases its space") { regions()?.let { it.bar == null && it.pane == full } == true }
        click(UiText.get(R.string.ui_111))
        awaitRoute(ControlsRoute.SETTINGS)
    }

    fun testCollapsingDockAndCompactEdgeKeepTheEditorReachable() = withCleanDock {
        val preferences = QuickActionPreferences(app)
        preferences.restoreSettings(JSONObject().put("actions", JSONArray(listOf("apps", "voiceSettings", "display", "vehicle", "settings", "buttons"))))
        activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
        await("Dock settings") { hasText(R.string.quick_actions_enabled) && regions()?.pane != null }
        val full = checkNotNull(regions()?.pane)
        val density = app.resources.displayMetrics.density
        val wide = full.width / density >= 850 && app.resources.configuration.fontScale <= 1.25f
        click(UiText.get(R.string.quick_actions_enabled))
        val edge = UiText.get(R.string.quick_actions_compact_edge)
        reveal("quickActionsContent", edge) { control(edge) != null }
        click(edge)
        click(UiText.get(R.string.quick_actions_top))
        await("Saved compact edge") { preferences.exportSettings().getString("compactEdge") == "top" }
        val afterUse = UiText.get(R.string.quick_actions_collapse_after_use)
        reveal("quickActionsContent", afterUse) { control(afterUse) != null }
        click(afterUse)

        val buttons = destination(R.string.cockpit_buttons)
        await("Collapsed handle") { handle(false) != null && control(buttons) == null }
        val closed = checkNotNull(regions()?.bar)
        val hit = android.graphics.Rect().also(checkNotNull(handle(false))::getBoundsInScreen)
        assertTrue("The handle needs a 48 dp touch target", minOf(hit.width(), hit.height()) + 1 >= 48 * density)
        assertReserved(wide, left = false, full, top = true)
        capture(if (wide) "quick-actions-collapsed" else "quick-actions-collapsed-compact")
        clickHandle(false)
        await("Expanded handle") { handle(true) != null }
        assertReserved(wide, left = false, full, top = true)
        val open = checkNotNull(regions()?.bar)
        if (wide) assertTrue(open.width > closed.width) else assertTrue(open.height > closed.height)
        if (!wide) capture("quick-actions-top")
        clickHandle(true)
        await("Manual collapse") { handle(false) != null && regions()?.bar == closed }
        clickHandle(false)
        reveal("quickActionsBar", buttons) { control(buttons) != null }
        assertNotNull("The handle must stay available while shortcuts scroll", handle(true))
        click(buttons)
        awaitRoute(ControlsRoute.BUTTONS)
        await("Collapse after using a shortcut") { handle(false) != null && control(buttons) == null }
        click(UiText.get(R.string.cockpit_back))
        awaitRoute(ControlsRoute.QUICK_ACTIONS)

        withSoftwareKeyboard {
            instrumentation.runOnMainSync {
                instrumentation.callActivityOnNewIntent(activity, ControlsNavigation.intent(app, ControlsRoute.SERVICE_REMINDER))
            }
            awaitRoute(ControlsRoute.SERVICE_REMINDER)
            clickHandle(false)
            reveal("quickActionsContent", "service distance field") { nodes().any { it.isVisibleToUser && it.isEditable } }
            await("Focus the service distance field") {
                nodes().firstOrNull { it.isVisibleToUser && it.isEditable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            await("IME owns space and the dock yields the page") {
                instrumentation.uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } &&
                    regions()?.let { it.pane != null && it.bar == null } == true
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Handle returns after closing the keyboard") { handle(false) != null }
            awaitRoute(ControlsRoute.SERVICE_REMINDER)
        }
    }

    fun testOpenBarTimesOutIndependentlyOfCollapseAfterUse() = withCleanDock {
        val preferences = QuickActionPreferences(app)
        preferences.restoreSettings(JSONObject().put("actions", JSONArray(listOf("buttons", "apps"))))
        activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
        await("Dock settings") { hasText(R.string.quick_actions_enabled) && regions()?.pane != null }
        val full = checkNotNull(regions()?.pane)
        val density = app.resources.displayMetrics.density
        val wide = full.width / density >= 850 && app.resources.configuration.fontScale <= 1.25f
        click(UiText.get(R.string.quick_actions_enabled))
        val idle = UiText.get(R.string.quick_actions_idle_collapse)
        reveal("quickActionsContent", idle) { control(idle) != null }
        click(idle)
        click(UiText.get(R.string.quick_actions_idle_ten))
        await("Idle time saved and dock open") {
            val stored = preferences.exportSettings()
            stored.getInt("idleCollapseSeconds") == 10 && !stored.getBoolean("collapseAfterUse") && handle(true) != null
        }

        val buttons = destination(R.string.cockpit_buttons)
        val apps = destination(R.string.appearance_app_grid)
        click(buttons)
        awaitRoute(ControlsRoute.BUTTONS)
        await("Idle-only dock stays open when a shortcut is used") { handle(true) != null && control(apps) != null }
        assertReserved(wide, left = false, full)
        val expanded = checkNotNull(regions()?.pane)
        capture("quick-actions-idle-open")

        val bounds = android.graphics.Rect().also(checkNotNull(control(apps))::getBoundsInScreen)
        val pressedAt = SystemClock.uptimeMillis()
        fun touch(action: Int) {
            val event = MotionEvent.obtain(pressedAt, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN)
        try {
            SystemClock.sleep(10_300)
            assertNotNull("An active touch keeps the shortcuts available", handle(true))
        } finally {
            touch(MotionEvent.ACTION_CANCEL)
        }

        await("Panel collapses after the touch ends", 16_000) { handle(false) != null && control(apps) == null }
        val collapsed = checkNotNull(regions()?.pane)
        assertTrue(if (wide) collapsed.width > expanded.width else collapsed.height > expanded.height)
        assertReserved(wide, left = false, full)
        val handleBounds = android.graphics.Rect().also(checkNotNull(handle(false))::getBoundsInScreen)
        assertTrue(minOf(handleBounds.width(), handleBounds.height()) + 1 >= 48 * density)
        capture("quick-actions-idle-collapsed")
        clickHandle(false)
        await("Handle can reopen the automatically collapsed panel") { handle(true) != null && control(apps) != null }

        preferences.restoreSettings(JSONObject().put("collapseAfterUse", true))
        await("Click-to-collapse is still available") { handle(false) != null }
        clickHandle(false)
        click(apps)
        awaitRoute(ControlsRoute.APPS)
        await("Click-to-collapse closes without waiting for the idle timer") { handle(false) != null }
        assertEquals(10, preferences.exportSettings().getInt("idleCollapseSeconds"))
    }

    fun testManualStartStateAndCompactShortcutLabelsPersist() = withCleanDock {
        val preferences = QuickActionPreferences(app)
        preferences.restoreSettings(JSONObject().put("enabled", true).put("actions", JSONArray(listOf("buttons", "apps"))))
        activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
        val buttons = destination(R.string.cockpit_buttons)
        val apps = destination(R.string.appearance_app_grid)
        fun width() = control(buttons)?.let { android.graphics.Rect().also(it::getBoundsInScreen).width() }
        await("Initial labeled shortcuts") { width() != null && regions()?.bar != null }
        val originalWidth = checkNotNull(width())
        val bar = checkNotNull(regions()?.bar)
        val wide = bar.width < bar.height

        fun select(label: Int, option: Int) {
            val title = UiText.get(label)
            reveal("quickActionsContent", title) { control(title) != null }
            click(title)
            click(UiText.get(option))
        }
        select(R.string.quick_actions_compact_labels, R.string.quick_actions_compact_labels_icons)
        await("Only horizontal shortcuts use the compact label setting") {
            val currentWidth = width() ?: return@await false
            preferences.exportSettings().getString("compactLabels") == "icons" &&
                (if (wide) currentWidth == originalWidth else currentWidth < originalWidth)
        }
        select(R.string.quick_actions_start_state, R.string.quick_actions_start_collapsed)
        await("Manual dock is saved independently of both timers") {
            val stored = preferences.exportSettings()
            stored.getString("startState") == "collapsed" && stored.getInt("idleCollapseSeconds") == 0 &&
                !stored.getBoolean("collapseAfterUse") && handle(false) != null
        }

        instrumentation.runOnMainSync { activity.finish() }
        activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.BUTTONS)) as MainActivity
        awaitRoute(ControlsRoute.BUTTONS)
        await("Next launch starts collapsed") { handle(false) != null && control(apps) == null }
        capture("quick-actions-manual-collapsed")
        clickHandle(false)
        await("Manual-only dock reopens") { handle(true) != null && control(apps) != null }
        capture("quick-actions-manual-open")
        click(apps)
        awaitRoute(ControlsRoute.APPS)
        await("Manual-only dock stays open across navigation") { handle(true) != null }
        clickHandle(true)
        await("Manual collapse keeps the handle") { handle(false) != null }

        instrumentation.runOnMainSync {
            ViewModelProvider(activity)[ControlsNavigationViewModel::class.java].open(ControlsRoute.QUICK_ACTIONS)
        }
        awaitRoute(ControlsRoute.QUICK_ACTIONS)
        select(R.string.quick_actions_start_state, R.string.quick_actions_start_expanded)
        await("Expanded start mode displays the shortcuts") { handle(true) != null && control(buttons) != null }
        val afterUse = UiText.get(R.string.quick_actions_collapse_after_use)
        reveal("quickActionsContent", afterUse) { control(afterUse) != null }
        click(afterUse)
        await("Expanded start mode stays open before choosing a shortcut") {
            control(afterUse) { it.isChecked } != null && handle(true) != null
        }
        click(buttons)
        awaitRoute(ControlsRoute.BUTTONS)
        await("Expanded at launch still closes after a shortcut") { handle(false) != null }
    }

    fun testHandleContrastCompactLabelAndSeatMemoryRoutineShortcuts() = withCleanDock {
        val preferences = QuickActionPreferences(app)
        preferences.restoreSettings(JSONObject().put("enabled", true).put("collapseAfterUse", true)
            .put("actions", JSONArray(listOf("seatMemory", "routines"))))
        activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
        await("Initial collapsed handle") { handle(false) != null && regions()?.bar != null }
        val initial = checkNotNull(regions())
        val bar = checkNotNull(initial.bar)
        val compact = bar.width > bar.height
        val labelled = android.graphics.Rect().also(checkNotNull(handle(false))::getBoundsInScreen)
        val initialContrast = handleContrast(labelled, compact)

        val contrast = UiText.get(R.string.quick_actions_contrast_handle)
        reveal("quickActionsContent", contrast) { control(contrast) != null }
        click(contrast)
        await("High-contrast expand button") {
            preferences.exportSettings().getBoolean("highContrastHandle") && handleContrast(labelled, compact).let {
                it.surface >= 7 && it.glyph >= 7 && it.surface > initialContrast.surface + 2
            }
        }
        val label = UiText.get(R.string.quick_actions_compact_handle_label)
        reveal("quickActionsContent", label) { control(label) != null }
        click(label)
        val density = app.resources.displayMetrics.density
        await("Icon-only compact handle with the same content area") {
            val hit = handle(false)?.let { android.graphics.Rect().also(it::getBoundsInScreen) } ?: return@await false
            val archive = preferences.exportSettings()
            !archive.getBoolean("showCompactHandleLabel") && archive.getBoolean("showLabels") && regions() == initial &&
                minOf(hit.width(), hit.height()) + 1 >= 48 * density &&
                (if (compact) hit.width() < labelled.width() && abs(hit.width() - 48 * density) <= 1 else hit == labelled)
        }
        capture(if (compact) "quick-actions-handle-compact" else "quick-actions-handle-contrast")

        val seat = destination(R.string.seat_memory_title)
        val routines = destination(R.string.routines_title)
        clickHandle(false)
        await("Seat memory and Routines shortcuts") { control(seat) != null && control(routines) != null }
        if (!compact) capture("quick-actions-seat-routines")
        click(seat)
        awaitRoute(ControlsRoute.SEAT_MEMORY)
        await("Driver seat memory page") { hasText(R.string.seat_memory_title) && handle(false) != null }
        click(UiText.get(R.string.cockpit_back))
        awaitRoute(ControlsRoute.QUICK_ACTIONS)
        clickHandle(false)
        click(routines)
        awaitRoute(ControlsRoute.ROUTINES)
        await("Routines list") { hasText(R.string.routines_title) && handle(false) != null }
        click(UiText.get(R.string.cockpit_back))
        awaitRoute(ControlsRoute.QUICK_ACTIONS)
    }

    fun testCollapsedHandleAccentFollowsSettingsAndKeepsNavigationReachable() = withCleanDock {
        val preferences = QuickActionPreferences(app)
        preferences.restoreSettings(JSONObject().put("enabled", true).put("startState", "collapsed")
            .put("showCompactHandleLabel", false))
        activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
        await("Initial collapsed button") { handle(false) != null && regions()?.bar != null }
        val initial = checkNotNull(regions())
        val bar = checkNotNull(initial.bar)
        val compact = bar.width > bar.height
        val density = app.resources.displayMetrics.density
        fun bounds(expanded: Boolean) = handle(expanded)?.let { android.graphics.Rect().also(it::getBoundsInScreen) }
        fun fill(hit: android.graphics.Rect): Int {
            val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            return try { frame.getPixel(hit.left + (8 * density).roundToInt(), hit.centerY()) }
            finally { frame.recycle() }
        }
        fun chooseAccent(label: Int) {
            val option = UiText.get(R.string.quick_actions_handle_accent)
            reveal("quickActionsContent", option) { control(option) != null }
            click(option)
            click(UiText.get(label))
        }

        val collapsed = checkNotNull(bounds(false))
        chooseAccent(R.string.appearance_accent_amber)
        await("Amber button in the same reserved area") {
            val archive = preferences.exportSettings()
            if (archive.getString("handleAccent") != "amber" || bounds(false) != collapsed || regions() != initial) return@await false
            val color = fill(collapsed)
            Color.red(color) > Color.green(color) && Color.green(color) > Color.blue(color)
        }
        assertTrue(minOf(collapsed.width(), collapsed.height()) + 1 >= 48 * density)
        clickHandle(false)
        await("Reachable expanded shortcuts") { handle(true) != null && control(destination(R.string.appearance_app_grid)) != null }
        val opened = checkNotNull(bounds(true))
        val openedFill = fill(opened)
        chooseAccent(R.string.appearance_accent_teal)
        await("Expanded button keeps the theme color") {
            preferences.exportSettings().getString("handleAccent") == "teal" && bounds(true) == opened && fill(opened) == openedFill
        }
        clickHandle(true)
        await("Teal button in the same reserved area") {
            if (bounds(false) != collapsed || regions() != initial) return@await false
            val color = fill(collapsed)
            Color.green(color) > Color.red(color) && Color.blue(color) > Color.red(color)
        }

        val contrast = UiText.get(R.string.quick_actions_contrast_handle)
        reveal("quickActionsContent", contrast) { control(contrast) != null }
        click(contrast)
        await("Teal button keeps its hue at high contrast") {
            val archive = preferences.exportSettings()
            if (!archive.getBoolean("highContrastHandle") || archive.getString("handleAccent") != "teal") return@await false
            val color = fill(collapsed)
            Color.green(color) > Color.red(color) && Color.blue(color) > Color.red(color) &&
                handleContrast(collapsed, compact).let { it.surface >= 7 && it.glyph >= 7 }
        }
        capture("quick-actions-handle-accent")
    }

    fun testIdleHandleDimmingKeepsKeyboardAccessAndReservedSpace() = withCleanDock {
        val preferences = QuickActionPreferences(app)
        preferences.restoreSettings(JSONObject().put("enabled", true).put("startState", "collapsed")
            .put("side", "left").put("compactHandleSide", "left").put("keepSideInset", true)
            .put("handleAccent", "teal").put("highContrastHandle", true).put("handleSize", "large"))
        activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
        fun bounds() = handle(false)?.let { android.graphics.Rect().also(it::getBoundsInScreen) }
        await("Initial large button") { bounds() != null && regions()?.bar != null }
        val initial = checkNotNull(regions())
        val bar = checkNotNull(initial.bar)
        val compact = bar.width > bar.height
        val hit = checkNotNull(bounds())
        assertTrue(minOf(hit.width(), hit.height()) + 1 >= 60 * app.resources.displayMetrics.density)
        await("Full fill before enabling dimming") { handleContrast(hit, compact).surface >= 7 }

        val option = UiText.get(R.string.quick_actions_dim_idle_handle)
        reveal("quickActionsContent", option) { control(option) != null }
        click(option)
        await("Dimmed button keeps the outline and chevron readable in the original hit area") {
            if (!preferences.exportSettings().getBoolean("dimIdleHandle") || bounds() != hit || regions() != initial) return@await false
            handleContrast(hit, compact).let { it.surface < 3 && it.glyph >= 7 && it.outline >= 3 }
        }
        capture("quick-actions-idle-handle-dimmed")

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        await("The collapsed handle accepts keyboard focus") {
            handle(false)?.let { it.isFocused || it.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } == true
        }
        await("Focus restores the full fill in the same reserved space") {
            handle(false)?.isFocused == true && bounds() == hit && regions() == initial &&
                handleContrast(hit, compact).surface >= 4.5
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        val buttons = destination(R.string.cockpit_buttons)
        await("The expanded button and shortcut are reachable") { handle(true) != null && control(buttons) != null }
        click(buttons)
        awaitRoute(ControlsRoute.BUTTONS)
    }

    fun testAppsShortcutOpensDefaultFavoriteOrAllApps() = withCleanDock {
        val grid = AppGridPreferences(app)
        val original = grid.exportSettings()
        val dock = QuickActionPreferences(app)
        val favorite = UiText.get(R.string.apps_desktop_favorites_one)
        val all = UiText.get(R.string.apps_desktop_all)
        val apps = destination(R.string.appearance_app_grid)
        fun selected(label: String) = matches(label).any { node ->
            generateSequence(node) { it.parent }.any { it.refresh() && it.isVisibleToUser && it.isSelected }
        }
        fun assertDesktop(label: String, expectedEntry: AppGridDesktop?) {
            awaitRoute(ControlsRoute.APPS)
            await("Selected desktop $label") { selected(label) }
            instrumentation.runOnMainSync {
                val state = ViewModelProvider(activity)[ControlsNavigationViewModel::class.java].state.value
                assertEquals(listOf(ControlsRoute.APPS), state.stack)
                assertEquals(expectedEntry, state.entryDesktop)
                assertEquals(AppPalette.DAY, state.temporaryPalette)
            }
        }
        try {
            val fixture = JSONObject(original.toString())
            fixture.getJSONObject("desktops").put("enabled", true).put("defaultDesktop", AppGridDesktop.FAVORITES_ONE.storedValue)
            fixture.getJSONObject("desktopNames").put(AppGridDesktop.FAVORITES_ONE.storedValue, "")
            grid.restoreSettings(fixture)
            dock.restoreSettings(JSONObject().put("enabled", true).put("actions", JSONArray(listOf("apps", "settings"))))
            activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
            await("Apps shortcut") { control(apps) != null }
            instrumentation.runOnMainSync { ViewModelProvider(activity)[ControlsNavigationViewModel::class.java].runRoutine(null, null, AppPalette.DAY) }
            click(apps)
            assertDesktop(favorite, null)

            instrumentation.runOnMainSync {
                instrumentation.callActivityOnNewIntent(activity, ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS))
            }
            awaitRoute(ControlsRoute.QUICK_ACTIONS)
            val title = UiText.get(R.string.quick_actions_apps_destination)
            reveal("quickActionsContent", title) { control(title) != null }
            click(title)
            click(all)
            await("All apps shortcut saved") { dock.exportSettings().getString("appsDestination") == "allApps" }
            click(apps)
            assertDesktop(all, AppGridDesktop.MAIN)
            click(favorite)
            await("Manual favorite selection") { selected(favorite) }
            click(apps)
            assertDesktop(all, AppGridDesktop.MAIN)
            assertEquals("favorites1", grid.exportSettings().getJSONObject("desktops").getString("defaultDesktop"))
        } finally { grid.restoreSettings(original) }
    }

    fun testCompactRailWidthPreservesPageAndShortcutTargets() = withCleanDock {
        val dock = QuickActionPreferences(app)
        dock.restoreSettings(JSONObject().put("enabled", true).put("actions", JSONArray(listOf("buttons"))))
        activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
        val shortcut = destination(R.string.cockpit_buttons)
        await("Standard shortcut bar") { control(shortcut) != null && regions()?.bar != null }
        val standard = checkNotNull(regions())
        val standardBar = checkNotNull(standard.bar)
        val wide = standardBar.width < standardBar.height
        val heading = UiText.get(R.string.quick_actions_wide_width)
        reveal("quickActionsContent", heading) { control(heading) != null }
        click(heading)
        click(UiText.get(R.string.quick_actions_width_compact))
        await("Compact width preserves the content area") {
            val current = regions() ?: return@await false
            dock.exportSettings().getString("railWidth") == "compact" && if (wide) {
                checkNotNull(current.bar).width < standardBar.width && checkNotNull(current.pane).width > checkNotNull(standard.pane).width
            } else current == standard
        }
        val hit = android.graphics.Rect().also(checkNotNull(control(shortcut))::getBoundsInScreen)
        assertTrue("The compact shortcut must remain 48 dp", minOf(hit.width(), hit.height()) + 1 >= 48 * app.resources.displayMetrics.density)
        if (wide) capture("quick-actions-compact-rail")

        click(shortcut)
        awaitRoute(ControlsRoute.BUTTONS)
    }

    fun testFixedSideInsetKeepsThePageInPlaceAndHandleReachable() = withCleanDock {
        val dock = QuickActionPreferences(app)
        dock.restoreSettings(JSONObject().put("enabled", true).put("side", "left").put("startState", "expanded")
            .put("actions", JSONArray(listOf("buttons"))))
        activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
        val option = UiText.get(R.string.quick_actions_keep_side_inset)
        reveal("quickActionsContent", option) { handle(true) != null && control(option) != null }
        val opened = checkNotNull(regions())
        val bar = checkNotNull(opened.bar)
        val wide = bar.height > bar.width
        clickHandle(true)
        await("Adaptive page gets more space while the bar is closed") {
            handle(false) != null && regions()?.pane?.let { it != opened.pane } == true
        }
        val adaptive = checkNotNull(regions())
        clickHandle(false)
        await("Expanded geometry is restored") { handle(true) != null && regions() == opened }

        reveal("quickActionsContent", option) { control(option) != null }
        click(option)
        await("Fixed side inset was saved") { dock.exportSettings().getBoolean("keepSideInset") && regions() == opened }
        clickHandle(true)
        val expected = if (wide) opened else adaptive
        await("Closed bar uses the expected viewport geometry") { handle(false) != null && regions() == expected }
        val handle = checkNotNull(handleBoundsInRoot())
        val density = app.resources.displayMetrics.density
        assertTrue(minOf(handle.width, handle.height) + 1 >= 48 * density)
        if (wide) {
            assertTrue(abs(handle.left - bar.left) <= 1f)
            assertTrue(abs(bar.right - checkNotNull(opened.pane).left) <= 1f)
            capture("quick-actions-stable-inset")
        }
        clickHandle(false)
        await("Buttons shortcut is reachable after reopening") { control(destination(R.string.cockpit_buttons)) != null }
        click(destination(R.string.cockpit_buttons))
        awaitRoute(ControlsRoute.BUTTONS)
    }

    fun testLargerShortcutsPreserveSpaceAndSurviveArchiveRestore() = withCleanDock {
        val dock = QuickActionPreferences(app)
        val home = destination(R.string.appearance_app_grid)
        val seat = destination(R.string.seat_memory_title)
        val headlights = destination(R.string.headlight_mode_title)
        val labels = listOf(home, seat, headlights)
        activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
        await("Full page before enabling the dock") { hasText(R.string.quick_actions_enabled) && regions()?.pane != null }
        val full = checkNotNull(regions()?.pane)
        dock.restoreSettings(JSONObject().put("enabled", true).put("side", "left").put("startState", "expanded")
            .put("keepSideInset", true)
            .put("showHeadlightMode", true).put("actions", JSONArray(listOf("apps", "seatMemory", "vehicle"))))
        await("Standard shortcuts and the headlight indicator") { dockTargets(labels).second.size == labels.size && handle(true) != null }
        val standard = checkNotNull(regions())
        val bar = checkNotNull(standard.bar)
        val wide = bar.height > bar.width
        val growth = (24 * app.resources.displayMetrics.density).roundToInt()
        val baseSizes = dockTargets(labels).second.mapValues { it.value.size }
        val enlargedSizes = baseSizes.mapValues { (label, size) ->
            when {
                label == home -> size
                wide -> IntSize(size.width, size.height + growth)
                else -> IntSize(size.width + growth, size.height)
            }
        }
        fun verifySizes(expected: Map<String, IntSize>, order: List<String> = labels): Boolean {
            val targets = dockTargets(labels).second
            val displayed = targets.entries.sortedBy { if (wide) it.value.clipped.top else it.value.clipped.left }
                .map { it.key }
            return regions() == standard && displayed == order && targets.mapValues { it.value.size } == expected
        }

        val heading = UiText.get(R.string.quick_actions_larger_shortcuts)
        reveal("quickActionsContent", heading) { control(heading) != null }
        click(heading)
        click(UiText.get(R.string.seat_memory_title))
        click(UiText.get(R.string.vehicle_settings_title))
        capture("quick-actions-larger-shortcuts-settings")
        click(UiText.get(R.string.ui_104))
        val largeActions = JSONArray(listOf("vehicle", "seatMemory")).toString()
        await("Selected shortcuts grew along the scroll axis without moving the page") {
            dock.exportSettings().getJSONArray("largeActions").toString() == largeActions && verifySizes(enlargedSizes)
        }
        assertReserved(wide, left = true, full)
        assertFullyVisibleDockTargets(labels)
        val exported = JSONObject(LocalSettingsArchive(app).exportJson()).getJSONObject("quickActions")
        assertEquals(largeActions, exported.getJSONArray("largeActions").toString())

        clickHandle(true)
        await("The dock collapses normally") { handle(false) != null && dockTargets(labels).second.isEmpty() }
        if (wide) assertEquals(standard, regions())
        clickHandle(false)
        await("Reopening restores the enlarged targets") { handle(true) != null && verifySizes(enlargedSizes) }

        val hiddenActions = JSONArray(listOf("vehicle", "apps"))
        dock.restoreSettings(JSONObject().put("actions", hiddenActions))
        val hidden = dock.exportSettings()
        assertEquals(hiddenActions.toString(), hidden.getJSONArray("actions").toString())
        assertEquals(largeActions, hidden.getJSONArray("largeActions").toString())
        await("The active dock reads the changed shortcut list") {
            var actions: List<QuickAction>? = null
            instrumentation.runOnMainSync {
                actions = ViewModelProvider(activity)[QuickActionsViewModel::class.java].state.value.settings?.actions
            }
            actions == listOf(QuickAction.VEHICLE, QuickAction.APPS)
        }
        await("Hiding Seat memory preserves its size choice") {
            verifySizes(enlargedSizes - seat, listOf(headlights, home))
        }
        dock.restoreSettings(JSONObject().put("actions", JSONArray(listOf("vehicle", "apps", "seatMemory"))))
        val reordered = listOf(headlights, home, seat)
        await("Different order retains the same sizes") { verifySizes(enlargedSizes, reordered) }
        dock.restoreSettings(JSONObject().put("largeActions", JSONArray()))
        await("Explicitly clearing the saved choice restores standard sizes") { verifySizes(baseSizes, reordered) }
        dock.restoreSettings(QuickActionPreferences.validateSettings(exported))
        await("The saved dock section restores enlarged sizes") { verifySizes(enlargedSizes) }
        assertFullyVisibleDockTargets(labels)
        click(home)
        awaitRoute(ControlsRoute.APPS)
        capture("quick-actions-larger-shortcuts")
        click(seat)
        awaitRoute(ControlsRoute.SEAT_MEMORY)
    }

    fun testCollapsedSideHandlePositionKeepsRailAndPageReserved() = withCleanDock {
        val dock = QuickActionPreferences(app)
        val position = UiText.get(R.string.quick_actions_side_handle_position)
        val buttons = destination(R.string.cockpit_buttons)
        dock.restoreSettings(JSONObject().put("enabled", true).put("startState", "collapsed")
            .put("side", "left").put("handleSize", "large").put("keepSideInset", true)
            .put("compactHandleSide", "right").put("showCompactHandleLabel", false)
            .put("actions", JSONArray(listOf("buttons"))))
        activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
        await("The collapsed quick actions bar and its expand button are visible") {
            handle(false) != null && regions()?.bar != null && handleBoundsInRoot() != null
        }
        val initial = checkNotNull(regions())
        val bar = checkNotNull(initial.bar)
        val pane = checkNotNull(initial.pane)
        val top = checkNotNull(handleBoundsInRoot())
        val wide = bar.height > bar.width
        val targetSize = 60 * app.resources.displayMetrics.density
        assertTrue("The expand button exposes the complete 60 dp target",
            abs(top.width - targetSize) <= 1f && abs(top.height - targetSize) <= 1f)
        if (wide) {
            assertTrue(abs(top.top - bar.top) <= 1f && abs(top.left - bar.left) <= 1f)
            assertTrue(abs(pane.left - bar.right) <= 1f)
        } else {
            assertTrue(abs(top.right - bar.right) <= 1f && abs(pane.bottom - bar.top) <= 1f)
        }

        fun choosePlacement(option: Int, id: String, matches: (Rect) -> Boolean) {
            reveal("quickActionsContent", position) { control(position) != null }
            val window = checkNotNull(control(position)).windowId
            click(position)
            val label = UiText.get(option)
            await("Position choice $label is available") {
                control(label) { it.windowId != window }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            await("The stored position $id keeps the original reserved regions") {
                val current = handleBoundsInRoot() ?: return@await false
                dock.exportSettings().getString("collapsedHandleAlignment") == id &&
                    dock.exportSettings().getString("sideRailAlignment") == "center" &&
                    regions() == initial && if (wide) {
                        matches(current) && abs(current.left - top.left) <= 1f &&
                            abs(current.width - targetSize) <= 1f && abs(current.height - targetSize) <= 1f
                    } else current == top
            }
        }

        choosePlacement(R.string.quick_actions_center, "center") { abs(it.center.y - bar.center.y) <= 1f }
        choosePlacement(R.string.quick_actions_bottom, "bottom") { abs(it.bottom - bar.bottom) <= 1f }
        if (wide) {
            capture("quick-actions-collapsed-handle-bottom")
            dock.restoreSettings(JSONObject().put("side", "right"))
            await("The expand button stays at the bottom of the physical right side bar") {
                val current = regions() ?: return@await false
                val rail = current.bar ?: return@await false
                val content = current.pane ?: return@await false
                val hit = handleBoundsInRoot() ?: return@await false
                abs(rail.left - content.right) <= 1f && abs(hit.right - rail.right) <= 1f && abs(hit.bottom - rail.bottom) <= 1f
            }
        }

        val closed = checkNotNull(regions())
        clickHandle(false)
        await("Opening the bar makes the Buttons shortcut available") {
            handle(true) != null && control(buttons) != null
        }
        if (wide) {
            assertEquals(closed, regions())
            assertTrue(abs(checkNotNull(handleBoundsInRoot()).top - checkNotNull(closed.bar).top) <= 1f)
        }
        click(buttons)
        awaitRoute(ControlsRoute.BUTTONS)
    }

    fun testHeadlightIndicatorOpensSourcesWithoutLeavingTheDesktop() = withCleanDock {
        val dock = QuickActionPreferences(app)
        val monitoring = HeadlightMonitoringPreferences(app)
        val previousMonitoring = monitoring.exportSettings()
        try {
            monitoring.restoreSettings(JSONObject().put("whileControlsOpen", false))
            dock.restoreSettings(JSONObject().put("enabled", true).put("showHeadlightMode", true).put("startState", "expanded")
                .put("collapseAfterUse", true).put("showLabels", false).put("compactLabels", "icons")
                .put("actions", JSONArray(listOf("buttons"))))
            activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
            val option = UiText.get(R.string.quick_actions_headlight_tap)
            reveal("quickActionsContent", option) { control(option) != null }
            click(option)
            click(UiText.get(R.string.headlight_sources_title))
            await("The headlight action is saved") { dock.exportSettings().getString("headlightTap") == "sources" }
            click(UiText.get(R.string.cockpit_back))
            awaitRoute(ControlsRoute.APPS)

            val indicator = destination(R.string.headlight_sources_title)
            reveal("quickActionsBar", indicator) { control(indicator) != null }
            click(indicator)
            await("Source report is visible") {
                hasText(R.string.headlight_sources_title) && hasText(R.string.headlight_sources_platform_unavailable)
            }
            awaitRoute(ControlsRoute.APPS)
            capture("quick-actions-headlight-sources")
            click(UiText.get(R.string.headlight_sources_done))
            await("The indicator collapsed while the app grid stayed open") { handle(false) != null }
            awaitRoute(ControlsRoute.APPS)
        } finally {
            monitoring.restoreSettings(previousMonitoring)
        }
    }

    fun testLargeExpandButtonAndCompactSideKeepDockReachable() = withCleanDock {
        val appearance = AppearancePreferences(app)
        val originalTextSize = appearance.load().textSize
        val useStandardText = app.resources.configuration.screenWidthDp >= 850 && originalTextSize != AppTextSize.STANDARD
        val dock = QuickActionPreferences(app)
        try {
            if (useStandardText) appearance.setTextSize(AppTextSize.STANDARD)
            dock.restoreSettings(JSONObject().put("enabled", true).put("collapseAfterUse", true).put("side", "left")
                .put("compactEdge", "top").put("compactHandleSide", "left").put("railWidth", "compact").put("showLabels", false)
                .put("compactLabels", "labels").put("showCompactHandleLabel", false)
                .put("actions", JSONArray(listOf("apps", "display", "voiceSettings", "vehicle", "settings", "seatMemory", "routines", "buttons"))))
            activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
            await("Standard collapsed handle") { handle(false) != null && regions()?.bar != null }
            val initial = checkNotNull(regions())
            val bar = checkNotNull(initial.bar)
            val pane = checkNotNull(initial.pane)
            val full = Rect(minOf(bar.left, pane.left), minOf(bar.top, pane.top), maxOf(bar.right, pane.right), maxOf(bar.bottom, pane.bottom))
            val wide = bar.height > bar.width
            val density = app.resources.displayMetrics.density
            fun fitsEdge(targetDp: Int, right: Boolean, label: Boolean = false): Boolean {
                val hit = handleBoundsInRoot() ?: return false
                val currentBar = regions()?.bar ?: return false
                val size = targetDp * density
                val seam = if (right) currentBar.right - hit.right else hit.left - currentBar.left
                return abs(seam) <= 1f && abs(hit.height - size) <= 1f &&
                    if (label) hit.width > size + 1 else abs(hit.width - size) <= 1f
            }
            assertTrue("The original handle is 48 dp at the physical left edge", fitsEdge(48, right = false))
            assertReserved(wide, left = true, full, top = true)

            val size = UiText.get(R.string.quick_actions_handle_size)
            reveal("quickActionsContent", size) { control(size) != null }
            click(size)
            click(UiText.get(R.string.quick_actions_handle_size_large))
            await("Large handle stays on the left") {
                val saved = dock.exportSettings()
                saved.getString("handleSize") == "large" && saved.getString("compactHandleSide") == "left" &&
                    fitsEdge(60, right = false)
            }

            val side = UiText.get(R.string.quick_actions_compact_handle_side)
            reveal("quickActionsContent", side) { control(side) != null }
            click(side)
            click(UiText.get(R.string.quick_actions_right))
            await("Independent compact button side and large target") {
                val saved = QuickActionPreferences(app).exportSettings()
                saved.getString("side") == "left" && saved.getString("compactHandleSide") == "right" &&
                    saved.getString("handleSize") == "large" && fitsEdge(60, right = !wide)
            }
            assertReserved(wide, left = true, full, top = true)
            capture(if (wide) "quick-actions-wide-large-handle" else "quick-actions-compact-right-handle")

            if (!wide) {
                val label = UiText.get(R.string.quick_actions_compact_handle_label)
                reveal("quickActionsContent", label) { control(label) != null }
                click(label)
                await("The optional label extends inward from the right edge") {
                    dock.exportSettings().getBoolean("showCompactHandleLabel") && fitsEdge(60, right = true, label = true)
                }
                assertReserved(wide = false, left = true, full, top = true)
            }

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            await("The enlarged handle accepts keyboard focus") {
                handle(false)?.let { it.isFocused || it.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } == true
            }
            await("Keyboard focus reaches the enlarged handle") { handle(false)?.isFocused == true }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Keyboard opens the dock without moving its control to the other side") {
                handle(true) != null && fitsEdge(48, right = !wide)
            }
            assertReserved(wide, left = true, full, top = true)
            if (wide) assertEquals("The compact open rail remains 56 dp", 56f, checkNotNull(regions()?.bar).width / density, 1f)

            val buttons = destination(R.string.cockpit_buttons)
            reveal("quickActionsBar", buttons) { control(buttons) != null }
            assertNotNull("The expanded control stays available while the shortcuts scroll", handle(true))
            click(buttons)
            awaitRoute(ControlsRoute.BUTTONS)
            await("The shortcut returns to the enlarged control") {
                handle(false) != null && fitsEdge(60, right = !wide, label = !wide)
            }
            assertReserved(wide, left = true, full, top = true)
        } finally {
            if (useStandardText) appearance.setTextSize(originalTextSize)
        }
    }

    fun testHeadlightSwitchIndicatorUsesSharedHistoryAndReservesSpace() = withCleanDock {
        val dock = QuickActionPreferences(app)
        val appearance = AppearancePreferences(app)
        val previousTextSize = appearance.load().textSize
        // Exercise the side rail at the wide viewport and retain the chosen text size on compact screens.
        val useStandardText = app.resources.configuration.screenWidthDp >= 850 && previousTextSize != AppTextSize.STANDARD
        val cards = DesktopInfoPreferences(app)
        val previousCards = cards.exportSettings()
        val monitoring = HeadlightMonitoringPreferences(app)
        val previousMonitoring = monitoring.exportSettings()
        val historyStore = app.getSharedPreferences(LocalHeadlightHistory.STORE, 0)
        val previousHistory = historyStore.all
        val history = LocalHeadlightHistory(app)
        data class Sample(val report: HeadlightFeedback, val processed: CompletableDeferred<Unit> = CompletableDeferred())
        val samples = Channel<Sample>(Channel.RENDEZVOUS)
        val activeSubscriptions = AtomicInteger()
        val subscriptionStarts = AtomicInteger()
        val feedback = flow {
            subscriptionStarts.incrementAndGet()
            activeSubscriptions.incrementAndGet()
            try {
                for (sample in samples) {
                    emit(sample.report)
                    sample.processed.complete(Unit)
                }
            } finally {
                activeSubscriptions.decrementAndGet()
            }
        }
        lateinit var fixture: VehicleSettingsViewModel

        val indicatorName = destination(R.string.headlight_mode_title)
        val buttons = destination(R.string.cockpit_buttons)
        val apps = destination(R.string.appearance_app_grid)
        val dockTargets = listOf(UiText.get(R.string.quick_actions_bar), buttons, apps, indicatorName)
        fun indicator() = control(indicatorName)
        fun indicatorState() = sequenceOf(indicator()).filterNotNull().plus(matches(indicatorName))
            .firstNotNullOfOrNull { AccessibilityNodeInfoCompat.wrap(it).stateDescription?.toString() }
        fun modeState(mode: Int, caption: Int) =
            UiText.get(R.string.headlight_activity_summary).format(UiText.get(mode), UiText.get(caption))
        fun send(report: HeadlightFeedback) = runBlocking {
            withTimeout(5_000) {
                val sample = Sample(report)
                samples.send(sample)
                sample.processed.await()
            }
        }

        try {
            if (useStandardText) appearance.setTextSize(AppTextSize.STANDARD)
            cards.restoreSettings(JSONObject())
            monitoring.restoreSettings(JSONObject().put("whileControlsOpen", false))
            check(historyStore.edit().clear().commit())
            dock.restoreSettings(JSONObject().put("side", "left").put("railWidth", "compact")
                .put("showLabels", false).put("compactLabels", "icons").put("startState", "expanded")
                .put("actions", JSONArray(listOf("buttons", "apps"))))

            activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
            await("Dock settings before enabling the bar") { hasText(R.string.quick_actions_enabled) && regions()?.pane != null }
            val full = checkNotNull(regions()?.pane)
            val density = app.resources.displayMetrics.density
            val wide = full.width / density >= 850 && app.resources.configuration.fontScale <= 1.25f
            instrumentation.runOnMainSync {
                fixture = VehicleSettingsViewModel({ false }, HeadlightMonitor(feedback, history))
                val installed = ViewModelProvider(activity, viewModelFactory { initializer { fixture } })[
                    VEHICLE_SETTINGS_MODEL_KEY, VehicleSettingsViewModel::class.java
                ]
                check(fixture === installed) { "Install the fixture before any headlight screen subscribes" }
            }

            click(UiText.get(R.string.quick_actions_enabled))
            val option = UiText.get(R.string.quick_actions_headlight_indicator)
            reveal("quickActionsContent", option) { control(option) != null }
            assertNull(indicator())
            assertEquals(0, subscriptionStarts.get())
            click(option)
            await("Headlight indicator setting saved") { dock.exportSettings().getBoolean("showHeadlightMode") }
            await("Expanded indicator is clickable") { handle(true) != null && indicator() != null }
            await("Indicator starts one headlight subscription") {
                activeSubscriptions.get() == 1 && subscriptionStarts.get() == 1
            }
            await("Accessible indicator announces waiting for vehicle") {
                indicatorState() == UiText.get(R.string.desktop_info_headlights_waiting)
            }

            val observedAt = System.currentTimeMillis()
            val earlier = EarlierHeadlightReport(HeadlightMode.OFF, observedAt - 30_000)
            val confirmed = HeadlightObservation(HeadlightMode.AUTOMATIC, observedAt)
            val earlierState = modeState(R.string.headlight_mode_off, R.string.desktop_info_headlights_earlier_report)
            val liveState = modeState(R.string.headlight_mode_automatic, R.string.desktop_info_headlights_current)
            val savedState = modeState(R.string.headlight_mode_automatic, R.string.desktop_info_headlights_last_confirmed)
            send(HeadlightFeedback.EarlierSelectorReport(earlier))
            await("Earlier selector mode is identified") { indicatorState() == earlierState }
            assertTrue("Earlier readings must leave the confirmed history empty", historyStore.all.isEmpty())
            send(HeadlightFeedback.Confirmed(confirmed, SystemClock.elapsedRealtimeNanos()))
            assertEquals("The monitor saves the processed confirmation", confirmed, LocalHeadlightHistory(app).read())
            await("View model exposes the confirmed selector reading") { fixture.headlightStatus.value.currentObservation == confirmed }
            await("Confirmed selector mode is shown") { indicatorState() == liveState }
            val historyFile = File(app.applicationInfo.dataDir, "shared_prefs/${LocalHeadlightHistory.STORE}.xml")
            val persisted = historyFile.readText()
            assertTrue("The confirmed mode and timestamp are written to disk",
                persisted.contains(">${confirmed.mode.key}</string>") && persisted.contains("value=\"${confirmed.confirmedAtMillis}\""))
            assertReserved(wide, left = true, full)
            assertFullyVisibleDockTargets(dockTargets)
            capture("quick-actions-headlight-live")

            click(buttons)
            awaitRoute(ControlsRoute.BUTTONS)
            await("Buttons page and compact headlight status") {
                hasText(R.string.cockpit_buttons) && indicatorState() == liveState
            }
            assertReserved(wide, left = true, full)
            assertFullyVisibleDockTargets(dockTargets)

            await("Accessible indicator opens the Vehicle page") {
                indicator()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            awaitRoute(ControlsRoute.VEHICLE)
            val confirmation = UiText.get(R.string.headlight_mode_confirmed).format("")
            await("Vehicle details show the same confirmed reading") {
                val labels = nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }.toSet()
                UiText.get(R.string.vehicle_settings_title) in labels &&
                    UiText.get(R.string.headlight_mode_automatic) in labels && labels.any { it.startsWith(confirmation) }
            }
            assertEquals("Opening Vehicle must reuse the visible indicator's subscription", 1, activeSubscriptions.get())
            assertEquals("Opening Vehicle must keep the original observation session", 1, subscriptionStarts.get())

            send(HeadlightFeedback.Unavailable)
            await("Loss of feedback falls back to the last confirmed mode") { indicatorState() == savedState }
            click(UiText.get(R.string.cockpit_back))
            awaitRoute(ControlsRoute.BUTTONS)
            await("Historical indicator remains visible outside Vehicle") { indicatorState() == savedState && handle(true) != null }
            assertReserved(wide, left = true, full)
            assertFullyVisibleDockTargets(dockTargets)
            capture("quick-actions-headlight-history")

            clickHandle(true)
            await("Collapsed dock releases its headlight subscription") {
                handle(false) != null && indicator() == null && activeSubscriptions.get() == 0
            }
            assertEquals(1, subscriptionStarts.get())
            clickHandle(false)
            await("Reopening the dock reads the stored mode") {
                indicatorState() == savedState && activeSubscriptions.get() == 1 && subscriptionStarts.get() == 2
            }
        } finally {
            samples.cancel()
            if (useStandardText) appearance.setTextSize(previousTextSize)
            cards.restoreSettings(previousCards)
            monitoring.restoreSettings(previousMonitoring)
            val restore = historyStore.edit().clear()
            for ((key, value) in previousHistory) when (value) {
                is String -> restore.putString(key, value)
                is Long -> restore.putLong(key, value)
                else -> error("Unexpected headlight history type")
            }
            check(restore.commit())
        }
    }

    fun testDockCanShowConfirmedHeadlampOrHighBeamActivity() = withCleanDock {
        val dock = QuickActionPreferences(app)
        val cards = DesktopInfoPreferences(app)
        val previousCards = cards.exportSettings()
        val monitoring = HeadlightMonitoringPreferences(app)
        val previousMonitoring = monitoring.exportSettings()
        val historyStore = app.getSharedPreferences(LocalHeadlightHistory.STORE, 0)
        val previousHistory = historyStore.all
        val history = LocalHeadlightHistory(app)
        val reports = MutableSharedFlow<HeadlightFeedback>()
        val indicator = destination(R.string.headlight_mode_title)

        fun state(): String? = sequenceOf(control(indicator)).filterNotNull().plus(matches(indicator))
            .firstNotNullOfOrNull { AccessibilityNodeInfoCompat.wrap(it).stateDescription?.toString() }
        fun label(vararg fields: Int) = fields.map(UiText::get).reduce { reading, caption ->
            UiText.get(R.string.headlight_activity_summary).format(reading, caption)
        }
        fun select(option: Int, savedContent: String) {
            val setting = UiText.get(R.string.quick_actions_headlight_content)
            reveal("quickActionsContent", setting) { control(setting) != null }
            click(setting)
            click(UiText.get(option))
            await("Saved display choice $savedContent") { dock.exportSettings().getString("headlightContent") == savedContent }
        }
        fun send(report: HeadlightFeedback) = runBlocking { withTimeout(5_000) { reports.emit(report) } }

        try {
            cards.restoreSettings(JSONObject())
            monitoring.restoreSettings(JSONObject().put("whileControlsOpen", false))
            check(historyStore.edit().clear().commit())
            dock.restoreSettings(JSONObject().put("enabled", true).put("startState", "expanded")
                .put("actions", JSONArray(listOf("buttons", "apps"))))
            activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
            await("Dock settings opened") { hasText(R.string.quick_actions_enabled) }
            instrumentation.runOnMainSync {
                val fixture = VehicleSettingsViewModel({ false }, HeadlightMonitor(reports, history))
                val installed = ViewModelProvider(activity, viewModelFactory { initializer { fixture } })[
                    VEHICLE_SETTINGS_MODEL_KEY, VehicleSettingsViewModel::class.java
                ]
                check(installed === fixture)
            }

            val enabled = UiText.get(R.string.quick_actions_headlight_indicator)
            reveal("quickActionsContent", enabled) { control(enabled) != null }
            click(enabled)
            await("Live reader is subscribed") { reports.subscriptionCount.value == 1 }
            select(R.string.headlight_activity_main, "headlights")
            val at = System.currentTimeMillis()
            val headlamps = LampObservation(LampActivity.DAYTIME_RUNNING, at)
            val highBeam = LampObservation(LampActivity.ON, at)
            send(HeadlightFeedback.ActivityConfirmed(ExteriorLamp.HEADLIGHTS, headlamps, SystemClock.elapsedRealtimeNanos()))
            await("Live headlamp state") {
                state() == label(R.string.headlight_activity_main, R.string.headlight_mode_daytime_running,
                    R.string.desktop_info_headlights_current)
            }
            select(R.string.headlight_activity_high, "highBeam")
            await("High beam has its own unavailable state") {
                state() == label(R.string.headlight_activity_high, R.string.headlight_activity_unavailable)
            }
            send(HeadlightFeedback.ActivityConfirmed(ExteriorLamp.HIGH_BEAM, highBeam, SystemClock.elapsedRealtimeNanos()))
            val live = label(R.string.headlight_activity_high, R.string.headlight_activity_on, R.string.desktop_info_headlights_current)
            await("Live high beam state") { state() == live }
            send(HeadlightFeedback.ActivityUnavailable(ExteriorLamp.HIGH_BEAM))
            await("High beam shows the last confirmation") {
                state() == label(R.string.headlight_activity_high, R.string.headlight_activity_on,
                    R.string.desktop_info_headlights_lamps_last_confirmed)
            }
            capture("quick-actions-high-beam-history")
            select(R.string.headlight_activity_main, "headlights")
            await("Losing high beam leaves the other lamp live") {
                state() == label(R.string.headlight_activity_main, R.string.headlight_mode_daytime_running,
                    R.string.desktop_info_headlights_current)
            }
            assertEquals(mapOf(ExteriorLamp.HEADLIGHTS to headlamps, ExteriorLamp.HIGH_BEAM to highBeam), history.readLamps())
            assertEquals(1, reports.subscriptionCount.value)
        } finally {
            cards.restoreSettings(previousCards)
            monitoring.restoreSettings(previousMonitoring)
            val restore = historyStore.edit().clear()
            for ((key, value) in previousHistory) when (value) {
                is String -> restore.putString(key, value)
                is Long -> restore.putLong(key, value)
                else -> error("Unexpected headlight history type")
            }
            check(restore.commit())
        }
    }

    fun testInstalledAppShortcutCanBeAddedOpenedAndRemoved() = withCleanDock {
        val dock = QuickActionPreferences(app)
        val target = InstalledApps.list(app).first { it.component().packageName in
            setOf("com.android.calendar", "com.google.android.calendar", "com.android.calculator2") }
        val component = target.component().flattenToString()
        val expectedPackage = target.component().packageName
        val add = UiText.get(R.string.quick_actions_add_app)
        val open = UiText.get(R.string.quick_actions_open_app).format(target.label())
        val remove = UiText.get(R.string.quick_actions_remove_app).format(target.label())
        dock.restoreSettings(JSONObject().put("enabled", true).put("startState", "expanded")
            .put("actions", JSONArray(listOf("buttons", "apps"))))
        activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
        reveal("quickActionsContent", add) { control(add) != null }
        click(add)
        click(target.label())
        await("The app shortcut is saved and the picker has closed") {
            dock.exportSettings().getJSONArray("appShortcuts").optString(0) == component &&
                (handle(true) != null || handle(false) != null)
        }
        // The older system can resize the activity for the picker's search before the dialog closes.
        if (handle(false) != null) clickHandle(false)
        await("The installed app shortcut is visible in the bar") { control(open) != null }
        assertFullyVisibleDockTargets(listOf(UiText.get(R.string.quick_actions_bar),
            destination(R.string.cockpit_buttons), destination(R.string.appearance_app_grid), open))
        capture("quick-actions-installed-app")
        click(open)
        await("The chosen launcher opens its own Android app") {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == expectedPackage
        }
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        await("Controls returns to its own settings after Android Back") { hasText(R.string.quick_actions_title) }
        reveal("quickActionsContent", remove) { control(remove) != null }
        click(remove)
        await("The removed shortcut disappears from the bar and storage") {
            control(open) == null && dock.exportSettings().getJSONArray("appShortcuts").length() == 0
        }
    }

    fun testRoutineShortcutRunsAndTracksIdentity() = withCleanDock {
        val raw = app.getSharedPreferences(RoutinePreferences.STORE, 0)
        val original = raw.getString("settings", null)
        val routines = RoutinePreferences(app)
        val dock = QuickActionPreferences(app)
        val routine = NamedRoutine("3580a7b1-ba3a-40b2-9ca7-930df405ca71", "Evening dashboard", RoutinePlan(RoutineTarget.BUTTONS))
        val renamed = routine.copy(name = "Night dashboard")
        val settings = UiText.get(R.string.quick_actions_routine_shortcut)
        val list = destination(R.string.routines_title)
        val run = UiText.get(R.string.quick_actions_run_routine).format(routine.name)
        val runRenamed = UiText.get(R.string.quick_actions_run_routine).format(renamed.name)
        val key = "routineShortcutId"
        try {
            check(raw.edit().remove("settings").commit())
            runBlocking { routines.update { RoutineLibrary(entries = listOf(routine)) } }
            dock.restoreSettings(JSONObject().put("enabled", true).put("actions", JSONArray(listOf("routines", "settings"))))
            activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
            reveal("quickActionsContent", settings) { control(settings) != null }
            click(settings)
            click(routine.name)
            await("The assigned routine is saved and visible in the dock") {
                dock.exportSettings().optString(key) == routine.id && control(run) != null
            }
            assertFullyVisibleDockTargets(listOf(list, run))
            capture("quick-actions-routine-shortcut")

            click(run)
            awaitRoute(ControlsRoute.BUTTONS)
            await("Routine feedback") { matches(UiText.get(R.string.routines_ran).format(routine.name)).any() }
            click(list)
            awaitRoute(ControlsRoute.ROUTINES)

            runBlocking { routines.update { it.copy(entries = listOf(renamed)) } }
            await("The same tile follows a routine rename") { control(runRenamed) != null && control(run) == null }
            val recovered = routines.exportSettings()
            runBlocking { routines.update { it.remove(renamed) } }
            await("The removed routine disappears and its assignment is retained") {
                control(runRenamed) == null && dock.exportSettings().optString(key) == routine.id
            }
            assertTrue("The Routines tab remains visible", matches(list).any())
            instrumentation.runOnMainSync {
                ViewModelProvider(activity)[ControlsNavigationViewModel::class.java].open(ControlsRoute.QUICK_ACTIONS)
            }
            awaitRoute(ControlsRoute.QUICK_ACTIONS)
            reveal("quickActionsContent", settings) { hasText(R.string.quick_actions_routine_unavailable) }
            assertNotNull(control(settings))
            routines.restoreSettings(recovered)
            await("Restoring the same ID brings its tile back") { control(runRenamed) != null }
            click(settings)
            click(UiText.get(R.string.quick_actions_routine_none))
            await("Clearing the assignment removes its tile") {
                dock.exportSettings().isNull(key) && control(runRenamed) == null && control(list) != null
            }
        } finally {
            check(raw.edit().putString("settings", original).commit())
        }
    }

    fun testWideRailAlignmentAndVirtualDisplayExclusionKeepTargetsReachable() = withCleanDock {
        val dock = QuickActionPreferences(app)
        val description = destination(R.string.virtual_display)
        val handle = UiText.get(R.string.quick_actions_bar)
        val targets = listOf(handle, destination(R.string.cockpit_buttons), description)
        val placement = UiText.get(R.string.quick_actions_rail_alignment)
        val hidePreview = UiText.get(R.string.quick_actions_hide_on_virtual_display)
        fun shortcutY() = control(description)?.let { node -> android.graphics.Rect().also(node::getBoundsInScreen).exactCenterY() }
        fun choosePlacement(option: Int, value: String) {
            reveal("quickActionsContent", placement) { control(placement) != null }
            val settingsWindow = checkNotNull(control(placement)).windowId
            click(placement)
            val label = UiText.get(option)
            await("Option $label in the separate placement dialog") {
                control(label) { node -> node.windowId != settingsWindow }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            await("Saved placement $value") { dock.exportSettings().getString("sideRailAlignment") == value }
        }

        dock.restoreSettings(JSONObject().put("startState", "expanded").put("side", "right")
            .put("actions", JSONArray(listOf("buttons", "display"))))
        activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.QUICK_ACTIONS)) as MainActivity
        await("Quick actions settings are visible") { hasText(R.string.quick_actions_enabled) && regions()?.pane != null }
        val full = checkNotNull(regions()?.pane)
        click(UiText.get(R.string.quick_actions_enabled))
        await("Display shortcut appears") { shortcutY() != null && regions()?.bar != null }
        val bar = checkNotNull(regions()?.bar)
        val wide = bar.height > bar.width
        val centered = checkNotNull(shortcutY())
        assertReserved(wide, left = false, full)
        assertFullyVisibleDockTargets(targets)
        val minimumMovement = 32 * app.resources.displayMetrics.density
        choosePlacement(R.string.quick_actions_top, "top")
        if (wide) await("Shortcut group moves above its centered position") {
            shortcutY()?.let { it < centered - minimumMovement } == true
        }
        choosePlacement(R.string.quick_actions_bottom, "bottom")
        if (wide) {
            await("Shortcut group moves below its centered position") {
                shortcutY()?.let { it > centered + minimumMovement } == true
            }
            capture("quick-actions-rail-bottom")
        } else {
            await("Wide rail placement leaves the horizontal dock in place") {
                shortcutY()?.let { abs(it - centered) <= 1f } == true
            }
        }
        assertReserved(wide, left = false, full)
        assertFullyVisibleDockTargets(targets)

        reveal("quickActionsContent", hidePreview) { control(hidePreview) != null }
        click(hidePreview)
        await("Preview exclusion is saved") { dock.exportSettings().getBoolean("hideOnVirtualDisplay") }
        click(description)
        awaitRoute(ControlsRoute.DISPLAY)
        await("Virtual display occupies the entire page") {
            val bounds = regions()
            val pane = bounds?.pane ?: return@await false
            bounds.bar == null && if (wide) pane.width > full.width - bar.width else pane.height > full.height - bar.height
        }
        capture("quick-actions-preview-without-dock")
        instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        awaitRoute(ControlsRoute.QUICK_ACTIONS)
        await("Returning from Virtual display restores the dock") { shortcutY() != null && regions()?.bar != null }
        assertFullyVisibleDockTargets(targets)
    }

    private data class DockTargetBounds(val clipped: Rect, val size: IntSize)

    private fun dockTargets(labels: List<String>): Pair<Rect?, Map<String, DockTargetBounds>> {
        val targets = mutableMapOf<String, DockTargetBounds>()
        var bar: Rect? = null
        instrumentation.runOnMainSync {
            val root = findRoot(activity.window.decorView)?.semanticsOwner?.rootSemanticsNode ?: return@runOnMainSync
            val dock = descendants(root).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "quickActionsBar" }
                ?: return@runOnMainSync
            bar = dock.boundsInRoot
            for (target in descendants(dock)) {
                if (SemanticsActions.OnClick !in target.config) continue
                val label = target.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull { it in labels } ?: continue
                targets[label] = DockTargetBounds(target.boundsInRoot, target.size)
            }
        }
        return bar to targets
    }

    private fun assertFullyVisibleDockTargets(labels: List<String>) {
        val (bar, targets) = dockTargets(labels)
        val dock = checkNotNull(bar)
        val minSize = 48 * app.resources.displayMetrics.density
        assertEquals("The dock must expose the handle, shortcuts and indicator", labels.toSet(), targets.keys)
        for ((label, target) in targets) {
            val hit = target.clipped
            assertTrue("$label must fit within the reserved dock $dock, measured $hit",
                hit.left >= dock.left - 1 && hit.top >= dock.top - 1 && hit.right <= dock.right + 1 && hit.bottom <= dock.bottom + 1)
            assertTrue("$label must stay completely visible, measured $hit for layout size ${target.size}",
                abs(hit.width - target.size.width) <= 1 && abs(hit.height - target.size.height) <= 1)
            assertTrue("$label must have a touch target of at least 48 dp, measured $hit",
                hit.width + 1 >= minSize && hit.height + 1 >= minSize)
        }
    }

    private data class HandleContrast(val surface: Double, val glyph: Double, val outline: Double)

    private fun handleContrast(hit: android.graphics.Rect, compact: Boolean): HandleContrast {
        val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val density = app.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).roundToInt()
            val y = hit.centerY()
            val fill = frame.getPixel(hit.left + dp(8), y)
            val rail = if (compact) frame.getPixel(hit.right + dp(8), y) else frame.getPixel(hit.centerX(), hit.bottom + dp(8))
            // The chevron occupies the central 24 dp of the first 48 dp touch target.
            var glyph = 1.0
            for (x in hit.left + dp(12)..hit.left + dp(36)) for (row in y - dp(12)..y + dp(12)) {
                glyph = maxOf(glyph, ColorUtils.calculateContrast(frame.getPixel(x, row), fill))
            }
            val outline = (hit.right - dp(2) until hit.right).maxOf { x ->
                val pixel = frame.getPixel(x, y)
                minOf(ColorUtils.calculateContrast(pixel, fill), ColorUtils.calculateContrast(pixel, rail))
            }
            return HandleContrast(ColorUtils.calculateContrast(fill, rail), glyph, outline)
        } finally { frame.recycle() }
    }

    private fun withCleanDock(test: () -> Unit) {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val store = app.getSharedPreferences(QuickActionPreferences.STORE, 0)
        val previous = store.getString("settings", null)
        val updates = app.getSharedPreferences("updates", 0)
        val startup = updates.all["check_on_startup"] as? Boolean
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        try {
            check(updates.edit().putBoolean("check_on_startup", false).commit())
            check(store.edit().remove("settings").commit())
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            test()
        } finally {
            if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            check(store.edit().putString("settings", previous).commit())
            val editor = updates.edit()
            if (startup == null) editor.remove("check_on_startup") else editor.putBoolean("check_on_startup", startup)
            check(editor.commit())
        }
    }

    private fun destination(title: Int) = UiText.get(R.string.quick_actions_open).format(UiText.get(title))

    private fun assertReserved(wide: Boolean, left: Boolean, full: Rect, top: Boolean = false) {
        val measured = checkNotNull(regions())
        val bar = checkNotNull(measured.bar)
        val pane = checkNotNull(measured.pane)
        assertTrue("Empty dock or page", bar.width > 0 && bar.height > 0 && pane.width > 0 && pane.height > 0)
        if (wide) {
            val seam = if (left) pane.left - bar.right else bar.left - pane.right
            assertTrue("The side bar must touch the page without covering it: $bar $pane", abs(seam) < 1f)
            assertTrue("The side bar must reserve the page width", pane.width + bar.width <= full.width + 1)
        } else {
            val seam = if (top) pane.top - bar.bottom else bar.top - pane.bottom
            assertTrue("The horizontal bar must touch the page without covering it: $bar $pane", abs(seam) < 1f)
            assertTrue("The horizontal bar must reserve the page height", pane.height + bar.height <= full.height + 1)
        }
    }

    private data class Regions(val pane: Rect?, val bar: Rect?)

    private fun regions(): Regions? {
        var regions: Regions? = null
        instrumentation.runOnMainSync {
            val owner = findRoot(activity.window.decorView)?.semanticsOwner ?: return@runOnMainSync
            val tree = descendants(owner.rootSemanticsNode).toList()
            fun bounds(tag: String): Rect? = tree.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }?.boundsInRoot
            regions = Regions(bounds("quickActionsContent"), bounds("quickActionsBar"))
        }
        return regions
    }

    private fun handleBoundsInRoot(): Rect? {
        var result: Rect? = null
        instrumentation.runOnMainSync {
            val root = findRoot(activity.window.decorView)?.semanticsOwner?.rootSemanticsNode ?: return@runOnMainSync
            result = descendants(root).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "quickActionsHandle" }
                ?.boundsInRoot
        }
        return result
    }

    private fun findRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(descendants(it)) }
    }

    private fun awaitRoute(expected: ControlsRoute) {
        await("Route $expected") {
            var current: ControlsRoute? = null
            instrumentation.runOnMainSync { current = ViewModelProvider(activity)[ControlsNavigationViewModel::class.java].state.value.route }
            current == expected
        }
    }

    private fun hasText(resource: Int): Boolean = matches(UiText.get(resource)).any()

    private fun nodes(): Sequence<AccessibilityNodeInfo> {
        val automation = instrumentation.uiAutomation
        val root = automation.windows.firstOrNull { it.isActive }?.root ?: automation.rootInActiveWindow ?: return emptySequence()
        fun tree(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            if (!node.refresh()) return@sequence
            yield(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { yieldAll(tree(it)) }
        }
        return tree(root)
    }

    private fun matches(text: String) = nodes().filter {
        it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text)
    }

    private fun control(label: String, accepts: (AccessibilityNodeInfo) -> Boolean = { true }): AccessibilityNodeInfo? {
        for (match in matches(label)) {
            var node: AccessibilityNodeInfo? = match
            while (node != null) {
                if (!node.refresh()) break
                if (node.isVisibleToUser && node.isEnabled && accepts(node) &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return node
                node = node.parent
            }
        }
        return null
    }

    private fun click(label: String) {
        await("Clickable control $label") { control(label)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        instrumentation.waitForIdleSync()
    }

    private fun handle(expanded: Boolean): AccessibilityNodeInfo? {
        // On compact layouts Android can expose the button's name on a child of its checked, clickable node.
        return control(UiText.get(R.string.quick_actions_bar)) { node ->
            node.isCheckable && node.isChecked == expanded
        }
    }

    private fun clickHandle(expanded: Boolean) {
        await("Clickable handle, expanded=$expanded") { handle(expanded)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        instrumentation.waitForIdleSync()
    }

    private fun reveal(region: String, description: String, visible: () -> Boolean) {
        var forward = true
        await("Visible $description") {
            if (visible()) return@await true
            runBlocking {
                withTimeoutOrNull(1_250) {
                    withContext<Unit>(AndroidUiDispatcher.Main) {
                        val root = findRoot(activity.window.decorView)?.semanticsOwner?.rootSemanticsNode
                            ?: return@withContext
                        val area = descendants(root).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == region }
                            ?: return@withContext
                        val node = descendants(area).firstOrNull { SemanticsActions.ScrollByOffset in it.config }
                            ?: return@withContext
                        val horizontal = node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
                        val axis = horizontal ?: node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                            ?: return@withContext
                        if (axis.value() >= axis.maxValue() - 1f) forward = false
                        if (axis.value() <= 1f) forward = true
                        val extent = if (horizontal != null) node.boundsInRoot.width else node.boundsInRoot.height
                        val delta = extent * if (forward) .65f else -.65f
                        val offset = if (horizontal != null) Offset(delta, 0f) else Offset(0f, delta)
                        node.config[SemanticsActions.ScrollByOffset](offset)
                    }
                }
            }
            instrumentation.waitForIdleSync()
            visible()
        }
    }

    private fun withSoftwareKeyboard(test: () -> Unit) {
        // The stock emulator advertises a hardware keyboard. Restore its system setting after the real IME check.
        val key = "show_ime_with_hard_keyboard"
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val previous = shell("settings get secure $key")
        check(previous in setOf("null", "0", "1"))
        try {
            if (previous != "1") shell("settings put secure $key 1")
            test()
        } finally {
            if (previous == "null") shell("settings delete secure $key")
            else if (previous != "1") shell("settings put secure $key $previous")
        }
    }

    private fun await(description: String, timeoutMillis: Long = 8_000, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMillis
        do {
            if (condition()) return
            SystemClock.sleep(60)
        } while (SystemClock.uptimeMillis() < until)
        var evidence = ""
        if (::activity.isInitialized) {
            val name = "failure-dock-" + description.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(55)
            val handleName = UiText.get(R.string.quick_actions_bar)
            val native = runCatching { nodes().filter { it.contentDescription?.toString() == handleName }.take(4)
                .joinToString { label -> generateSequence(label) { it.parent }.take(3).joinToString(" <- ") {
                    "${it.className} checkable=${it.isCheckable} checked=${it.isChecked} click=${it.isClickable}"
                } }
            }.getOrNull()
            instrumentation.runOnMainSync {
                val root = findRoot(activity.window.decorView)?.semanticsOwner?.rootSemanticsNode
                val rendered = root?.let(::descendants)?.mapNotNull { node ->
                    val tag = node.config.getOrNull(SemanticsProperties.TestTag)
                    if (tag !in setOf("quickActionsContent", "quickActionsBar", "quickActionsHandle")) null else
                        "$tag ${node.config.getOrNull(SemanticsProperties.ToggleableState)} ${node.boundsInRoot}"
                }?.joinToString()
                evidence = "\nlifecycle=${activity.lifecycle.currentState}\nCompose=$rendered\nAndroid=$native"
            }
            runCatching { capture(name) }
        }
        fail("Timed out waiting for $description$evidence")
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(320)
        val directory = File(app.filesDir, "ui-audit").apply { check(isDirectory || mkdirs()) }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
