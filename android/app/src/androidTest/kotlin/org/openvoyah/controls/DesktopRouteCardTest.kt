package org.openvoyah.controls

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.data.apps.DesktopInfoPreferences
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.DesktopRouteContent
import org.openvoyah.controls.domain.apps.DesktopRouteOpenTarget
import org.openvoyah.controls.platform.navigation.NavigationBroadcast
import org.openvoyah.controls.presentation.navigation.ControlsNavigationViewModel
import org.openvoyah.controls.presentation.navigation.ControlsRoute

/** Real launcher card on the isolated emulator, with the normal local navigation decoder and a test snapshot. */
class DesktopRouteCardTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext

    fun testNavigationCardFollowsCurrentRouteAndOpensPreview() = withRouteCard(DesktopRouteOpenTarget.DISPLAY) { activity, cards ->
        fun publishRoute() = publish("route") {
            putExtra("road", "Парковая улица")
            putExtra("nextRoad", "Лесной проспект")
            putExtra("meters", 400)
        }

        val waiting = UiText.get(R.string.desktop_info_route_waiting)
        awaitText(waiting)

        publishRoute()
        awaitText("Лесной проспект")
        awaitText("400 m")
        assertFalse(hasText("Парковая улица"))

        runBlocking { cards.setRouteContent(AppGridDesktop.MAIN, DesktopRouteContent.CURRENT_AND_NEXT) }
        awaitText(UiText.get(R.string.desktop_info_route_current_road).format("Парковая улица"))
        instrumentation.waitForIdleSync()
        // The accessibility tree can expose newly composed text before the frame is painted.
        SystemClock.sleep(300)
        val directory = File(app.filesDir, "ui-audit").apply { check(isDirectory || mkdirs()) }
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "desktop-route-card.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }

        publish("open") { putExtra("app", "org.example.navigator") }
        awaitText(waiting)
        assertFalse(hasText("org.example.navigator"))
        publishRoute()
        awaitText("Лесной проспект")

        tapRouteCard()
        awaitRoute(activity, ControlsRoute.DISPLAY)
        awaitText(UiText.get(R.string.app_display_settings_title))
        publish("end")
        returnFromPreview(activity)
        awaitText(waiting, scrollTowardsStart = true)
        assertFalse(hasText("Лесной проспект"))
    }

    fun testNavigationCardUsesAssignedNavigatorOrFallsBack() = withRouteCard(DesktopRouteOpenTarget.NONE) { activity, cards ->
        val target = InstalledApps.list(app).first { it.component().packageName in
            setOf("com.android.calendar", "com.google.android.calendar", "com.android.calculator2") }.component()
        val voice = VoiceSettings(app)
        val voicePreferences = app.getSharedPreferences("voice", Context.MODE_PRIVATE)
        val navigatorKey = "app.Navigator"
        val previousNavigator = voicePreferences.getString(navigatorKey, null)
        try {
            voice.app("Navigator", target.flattenToString())
            val inert = awaitNode("navigation card without action") { it.viewIdResourceName == "desktop-info:route" }
            assertFalse(inert.isClickable)
            assertFalse(inert.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })

            // A package with other launchers cannot substitute for an unavailable exact assignment.
            val missing = ComponentName(target.packageName, target.packageName + ".MissingControlsNavigator")
            voice.app("Navigator", missing.flattenToString())
            runBlocking { cards.setRouteOpenTarget(AppGridDesktop.MAIN, DesktopRouteOpenTarget.NAVIGATOR) }
            tapRouteCard()
            awaitRoute(activity, ControlsRoute.DISPLAY)
            awaitText(UiText.get(R.string.app_display_settings_title))
            returnFromPreview(activity)

            voice.app("Navigator", target.flattenToString())
            tapRouteCard(scrollTowardsStart = true)
            awaitForegroundPackage(target.packageName)
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            awaitRoute(activity, ControlsRoute.APPS)
        } finally {
            check(voicePreferences.edit().putString(navigatorKey, previousNavigator).commit())
        }
    }

    private fun withRouteCard(
        action: DesktopRouteOpenTarget,
        test: (MainActivity, DesktopInfoPreferences) -> Unit,
    ) {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val cards = DesktopInfoPreferences(app)
        val grid = AppGridPreferences(app)
        val previousCards = cards.exportSettings()
        val previousGrid = grid.exportSettings()
        val language = app.getSharedPreferences("ui", 0)
        val previousLanguage = language.getString("language", null)
        val updates = app.getSharedPreferences("updates", 0)
        val previousStartupCheck = updates.all["check_on_startup"] as? Boolean
        val events = UiText.displayEvents(app)
        val previousRoute = events.snapshot().navigation()
        var launched: MainActivity? = null
        try {
            UiText.language("ru")
            check(updates.edit().putBoolean("check_on_startup", false).commit())
            val layout = JSONObject(previousGrid.toString()).put("showSearchField", false).put("desktopCardPlacement", "beforeApps")
            layout.getJSONObject("desktops").put("enabled", false)
            grid.restoreSettings(layout)
            cards.restoreSettings(JSONObject().put("main", JSONObject().put("route", "wide")
                .put("routeContent", "nextTurn").put("routeOpenTarget", action.storedValue)))
            publish("end")
            val activity = instrumentation.startActivitySync(MainActivity.appsIntent(app)) as MainActivity
            launched = activity
            test(activity, cards)
        } finally {
            instrumentation.runOnMainSync {
                launched?.finish()
                events.publishNavigation(previousRoute)
            }
            cards.restoreSettings(previousCards)
            grid.restoreSettings(previousGrid)
            check(language.edit().putString("language", previousLanguage).commit())
            check(updates.edit().apply {
                if (previousStartupCheck == null) remove("check_on_startup")
                else putBoolean("check_on_startup", previousStartupCheck)
            }.commit())
        }
    }

    private fun publish(event: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(NavigationBroadcast.ACTION).putExtra("event", event).apply(extras)
        val events = UiText.displayEvents(app)
        instrumentation.runOnMainSync { NavigationBroadcast.publish(intent, events) }
    }

    private fun tapRouteCard(scrollTowardsStart: Boolean = false) {
        val card = awaitNode("navigation card action", scrollTowardsStart) {
            it.viewIdResourceName == "desktop-info:route" && it.isClickable
        }
        assertTrue(card.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun returnFromPreview(activity: MainActivity) {
        val back = awaitText(UiText.get(R.string.cockpit_back))
        val backButton = generateSequence(back) { it.parent }.take(12).firstOrNull {
            it.refresh() && it.isEnabled && it.isVisibleToUser && it.isClickable
        }
        assertTrue("Display's visible Back control could not be activated",
            backButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        awaitRoute(activity, ControlsRoute.APPS)
    }

    private fun awaitForegroundPackage(expected: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        val automation = instrumentation.uiAutomation
        var foreground = automation.rootInActiveWindow?.packageName?.toString()
        while (foreground != expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            foreground = automation.rootInActiveWindow?.packageName?.toString()
        }
        assertEquals(expected, foreground)
    }

    private fun awaitText(text: String, scrollTowardsStart: Boolean = false) =
        awaitNode(text, scrollTowardsStart) { it.text?.contains(text) == true }

    private fun hasText(text: String): Boolean = nodes().any { it.isVisibleToUser && it.text?.contains(text) == true }

    private fun awaitNode(
        description: String, scrollTowardsStart: Boolean = false, matches: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 5_000
        val packageName = instrumentation.targetContext.packageName
        val direction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        while (SystemClock.uptimeMillis() < deadline) {
            val visible = nodes().filter { it.isVisibleToUser && it.packageName?.toString() == packageName }.toList()
            visible.firstOrNull(matches)?.let { return it }
            val moved = scrollTowardsStart && visible.firstOrNull { node ->
                node.isScrollable && node.actionList.any { it.id == direction }
            }?.performAction(direction) == true
            SystemClock.sleep(if (moved) 250 else 50)
        }
        throw AssertionError("Could not find $description in ${nodes().mapNotNull { it.text }.toList()}")
    }

    private fun nodes(): Sequence<AccessibilityNodeInfo> {
        fun walk(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            if (!node.refresh()) return@sequence
            yield(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(walk(it)) }
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::walk) ?: emptySequence()
    }

    private fun awaitRoute(activity: MainActivity, expected: ControlsRoute) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var route: ControlsRoute? = null
        var hostClosed = false
        var resumed = false
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                hostClosed = activity.isFinishing || activity.isDestroyed
                if (!hostClosed) {
                    route = ViewModelProvider(activity)[ControlsNavigationViewModel::class.java].state.value.route
                    resumed = activity.lifecycle.currentState == Lifecycle.State.RESUMED
                }
            }
            assertFalse("The original app host closed", hostClosed)
            if (route == expected && resumed) return
            SystemClock.sleep(50)
        }
        assertEquals(expected, route)
        assertTrue("The original app host did not resume", resumed)
    }
}
