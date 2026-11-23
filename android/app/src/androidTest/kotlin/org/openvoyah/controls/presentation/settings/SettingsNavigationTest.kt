package org.openvoyah.controls.presentation.settings

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.MainActivity
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsNavigationViewModel
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.ControlsTheme

/**
 * Real Compose rendering of the Settings page and a section page on the 1920 × 720 emulator at 240 dpi, where the
 * window is wide enough for the column of parts. Nothing is stored except what the real host stores on navigation.
 */
class SettingsNavigationTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext
    private var activity: MainActivity? = null
    private var savedLanguage: String? = null
    private var savedRecent: Map<String, *>? = null

    override fun setUp() {
        super.setUp()
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        savedLanguage = app.getSharedPreferences("ui", 0).getString("language", null)
        UiText.language("en")
        // Recent changes from earlier tests would push the section tiles below the fold.
        val recent = app.getSharedPreferences(RecentSettings.STORE, 0)
        savedRecent = recent.all
        check(recent.edit().clear().commit())
    }

    override fun tearDown() {
        try {
            check(app.getSharedPreferences(RecentSettings.STORE, 0).edit().clear().apply {
                savedRecent.orEmpty().forEach { (key, value) -> if (value is String) putString(key, value) }
            }.commit())
            check(app.getSharedPreferences("ui", 0).edit().apply {
                savedLanguage?.let { putString("language", it) } ?: remove("language")
            }.commit())
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            instrumentation.waitForIdleSync()
        } finally { super.tearDown() }
    }

    fun testASectionIsOnePageAndASearchFocusScrollsToAGroup() {
        var focus by mutableStateOf<String?>(null)
        val touched = mutableListOf<String>()
        val parts = listOf("A", "B", "C", "D", "E").map { key ->
            SettingsPart(key, "Part $key", CockpitIcon.SETTINGS) {
                CockpitSection("Part $key", CockpitIcon.SETTINGS) {
                    Text("Content of $key")
                    Spacer(Modifier.height(240.dp))
                }
            }
        }
        show {
            ControlsTheme {
                SettingsSectionPage("Section", "Fixture", {}, parts, focus) { synchronized(touched) { touched += it } }
            }
        }
        // No tabs: the groups follow one another on the same page.
        await("the first group") { text("Content of A") != null }
        assertNull("The last group starts below the fold", text("Content of E"))

        instrumentation.runOnMainSync { focus = "E" }
        await("the group a search result opens") { text("Content of E") != null }

        val target = bounds(checkNotNull(text("Content of E")))
        tap(target.centerX().toFloat(), target.centerY().toFloat())
        await("the touched group is reported") { synchronized(touched) { touched.lastOrNull() == "E" } }
    }

    fun testShortSettingsShareARowAndLongContentTakesTheWidth() {
        var checked by mutableStateOf(false)
        val note = "A hint long enough to need the whole width of the card, ".repeat(4).trim()
        show {
            ControlsTheme {
                CockpitDetail("Grid", null, {}) {
                    CockpitSection("Fixture", CockpitIcon.SETTINGS) {
                        CockpitSwitch("One", checked) { checked = it }
                        CockpitSwitch("Two", checked) { checked = it }
                        CockpitChoice("Three", "Short") {}
                        Text(note)
                        CockpitSwitch("Four", checked) { checked = it }
                    }
                }
            }
        }
        await("the grid") { text("One") != null && text("Four") != null }
        val one = bounds(checkNotNull(text("One")))
        val two = bounds(checkNotNull(text("Two")))
        val three = bounds(checkNotNull(text("Three")))
        val hint = bounds(checkNotNull(text(note)))
        val four = bounds(checkNotNull(text("Four")))
        assertTrue("Short settings share the first row", kotlin.math.abs(one.centerY() - two.centerY()) < 8 &&
            kotlin.math.abs(one.centerY() - three.centerY()) < 30)
        assertTrue("They stand side by side", one.left < two.left && two.left < three.left)
        assertTrue("The long hint starts a row of its own", hint.top > one.bottom)
        assertTrue("A setting after the hint goes below it", four.top > hint.top)
    }

    /** Scrolls the page a step at a time until [value] is on screen. */
    private fun scrollUntil(value: String) {
        repeat(12) {
            if (text(value) != null) return
            // A short drag, so what was on screen above the target stays in view.
            val start = SystemClock.uptimeMillis()
            instrumentation.sendPointerSync(MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, 960f, 520f, 0))
            for (step in 1..10) {
                instrumentation.sendPointerSync(MotionEvent.obtain(start, start + step * 16L, MotionEvent.ACTION_MOVE, 960f,
                    520f - step * 16f, 0))
            }
            instrumentation.sendPointerSync(MotionEvent.obtain(start, start + 400, MotionEvent.ACTION_UP, 960f, 360f, 0))
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250)
        }
        await("$value on screen") { text(value) != null }
    }

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    private fun tap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, x, y, 0))
    }

    fun testSearchFindsASettingAndOpensItsSectionAtItsPart() {
        var opened: Pair<ControlsRoute, String?>? = null
        show {
            ControlsTheme {
                CockpitDetail(UiText.get(R.string.cockpit_settings), null, {}) {
                    SettingsHub { route, part -> opened = route to part }
                }
            }
        }
        await("the sections") { text(UiText.get(R.string.settings_section_home)) != null }
        // The last row may sit below the fold of the 720 px display; scroll the page while collecting the titles.
        val seen = mutableSetOf<String>()
        repeat(10) {
            nodes().mapNotNullTo(seen) { it.text?.toString() }
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300)
        }
        nodes().mapNotNullTo(seen) { it.text?.toString() }
        // A page scroll can step over a row in the middle, so the first and last sections stand for the list; the
        // unit test SettingsStructureTest checks that all ten exist.
        listOf(SettingsSection.HOME_SCREEN, SettingsSection.SYSTEM).forEach { section ->
            assertTrue("${section.name} is listed in $seen", UiText.get(section.title) in seen)
        }
        repeat(6) {
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            instrumentation.waitForIdleSync()
        }

        await("the search field back on screen") {
            nodes().any { it.isEditable } || run {
                nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                SystemClock.sleep(200)
                false
            }
        }
        val field = checkNotNull(nodes().firstOrNull { it.isEditable })
        val words = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "ducking calls")
        }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, words))
        val result = UiText.get(R.string.voice_duck_calls)
        await("the search result") { text(result) != null && text(UiText.get(R.string.settings_section_home)) == null }
        click(result)
        await("the opened part") { opened != null }
        assertEquals(ControlsRoute.SOUND to SettingsPartId.SOUND_DUCKING.name, opened)
    }

    fun testASectionTileOpensItsRouteAndBackReturnsToSettings() {
        val launched = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.SETTINGS)) as MainActivity
        activity = launched
        val navigation = ViewModelProvider(launched)[ControlsNavigationViewModel::class.java]
        await("Settings") { navigation.state.value.route == ControlsRoute.SETTINGS && text(UiText.get(R.string.settings_section_sound)) != null }
        click(UiText.get(R.string.settings_section_sound))
        await("Sound and media") { navigation.state.value.route == ControlsRoute.SOUND }
        await("its first group") { text(UiText.get(R.string.settings_startup_volume_title)) != null }
        assertEquals(listOf(ControlsRoute.APPS, ControlsRoute.SETTINGS, ControlsRoute.SOUND), navigation.state.value.stack)

        instrumentation.runOnMainSync { navigation.open(ControlsRoute.APPEARANCE, SettingsPartId.APPEARANCE_TEXT.name) }
        await("Appearance at Text") {
            navigation.state.value.route == ControlsRoute.APPEARANCE && text(UiText.get(R.string.appearance_text_size)) != null
        }
        instrumentation.runOnMainSync { navigation.back() }
        await("back to Sound and media") { navigation.state.value.route == ControlsRoute.SOUND }
        assertNull(navigation.state.value.focus)
    }

    fun testSoundAndMediaLinksToTheMultimediaRestartBesideTheSystemReboot() {
        val launched = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.SOUND)) as MainActivity
        activity = launched
        val navigation = ViewModelProvider(launched)[ControlsNavigationViewModel::class.java]
        val restartMedia = UiText.get(R.string.system_restart_media)
        await("Sound and media") { navigation.state.value.route == ControlsRoute.SOUND }
        // The search focus scrolls the page to the group with the link.
        instrumentation.runOnMainSync { navigation.open(ControlsRoute.SOUND, SettingsPartId.SOUND_MEDIA.name) }
        await("the link in Sound and media") { text(restartMedia) != null }
        assertNull("Sound and media holds no copy of the action", text(UiText.get(R.string.system_restart_media_hint)))
        click(restartMedia)
        await("System at About and restart") {
            navigation.state.value.route == ControlsRoute.SYSTEM &&
                navigation.state.value.focus == SettingsPartId.SYSTEM_ABOUT.name && text(restartMedia) != null
        }
        scrollUntil(UiText.get(R.string.system_reboot))
        // Both root restarts sit in one group, the multimedia one right above the system reboot.
        val media = bounds(checkNotNull(text(restartMedia)))
        val reboot = bounds(checkNotNull(text(UiText.get(R.string.system_reboot))))
        assertTrue("Restart multimedia comes right before Restart the system", media.top < reboot.top)
        assertTrue("Both actions share a column", kotlin.math.abs(media.centerX() - reboot.centerX()) < 4)
        assertNotNull(text(UiText.get(R.string.system_restart_media_hint)))
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        val launched = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.SETTINGS)) as MainActivity
        activity = launched
        instrumentation.runOnMainSync { launched.setContent { content() } }
        instrumentation.waitForIdleSync()
    }

    private fun click(value: String) {
        await("actionable $value") {
            val target = control(value) ?: return@await false
            target.isEnabled && target.isVisibleToUser && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }

    private fun control(value: String): AccessibilityNodeInfo? {
        var node = text(value)
        var depth = 0
        while (node != null && depth++ < 16) {
            if (!node.refresh()) return null
            if (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return node
            node = node.parent
        }
        return null
    }

    private fun text(value: String): AccessibilityNodeInfo? =
        nodes().firstOrNull { it.refresh() && it.isVisibleToUser && it.text?.toString() == value }

    private fun nodes(): Sequence<AccessibilityNodeInfo> {
        fun walk(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            yield(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(walk(it)) }
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptySequence()
        return if (root.refresh()) walk(root) else emptySequence()
    }

    private fun await(description: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (ready()) return
            SystemClock.sleep(50)
        }
        val seen = nodes().take(80).joinToString("\n") { "text=${it.text} selected=${it.isSelected} enabled=${it.isEnabled}" }
        throw junit.framework.AssertionFailedError("Settings did not reach $description\n$seen")
    }
}
