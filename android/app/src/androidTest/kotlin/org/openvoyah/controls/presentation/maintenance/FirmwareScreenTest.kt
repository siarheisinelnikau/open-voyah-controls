package org.openvoyah.controls.presentation.maintenance

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.MainActivity
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.ControlsTheme
import java.io.File

/**
 * Real Compose rendering of the firmware page with explicit state. The window service exists only on Android 9 and
 * 11, so on other releases the page never shows its rows on its own; here they are given, and no bridge is asked.
 */
class FirmwareScreenTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext
    private var activity: MainActivity? = null
    private var savedLanguage: String? = null
    private var strip by mutableStateOf<WindowStripRow?>(WindowStripRow(false, "Off", changeable = true))
    private var work by mutableStateOf(MaintenanceWork.State(null))
    private var service by mutableStateOf<Int?>(R.string.cockpit_enabled)
    private val selected = mutableListOf<FirmwareAction>()

    override fun setUp() {
        super.setUp()
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        savedLanguage = app.getSharedPreferences("ui", 0).getString("language", null)
        UiText.language("en")
        val launched = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.FIRMWARE)) as MainActivity
        activity = launched
        instrumentation.runOnMainSync {
            launched.setContent {
                ControlsTheme {
                    FirmwareScreen("fixture · TEST", translationEnabled = false, windowStatus = service, windowStrip = strip,
                        state = work, back = {}, select = { synchronized(selected) { selected += it } })
                }
            }
        }
        await("the page title") { text(label(R.string.firmware_settings)) != null }
    }

    override fun tearDown() {
        try {
            check(app.getSharedPreferences("ui", 0).edit().apply {
                savedLanguage?.let { putString("language", it) } ?: remove("language")
            }.commit())
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            instrumentation.waitForIdleSync()
        } finally { super.tearDown() }
    }

    fun testTheExperimentalStripSitsUnderTheWindowServiceAndAsksThroughItsOwnAction() {
        val title = label(R.string.window_strip_title)
        await("the option and its status") { text(title) != null && text("Off") != null }
        val row = checkNotNull(control(title))
        val serviceRow = checkNotNull(text(label(R.string.window_hook_title)))
        assertTrue("The option belongs below the window service it depends on", top(serviceRow) < top(row))
        assertEquals(false, row.isChecked)
        capture("fixture-firmware-strip-off")

        click(title)
        await("the option's own action") { synchronized(selected) { selected == listOf(FirmwareAction.WINDOW_STRIP) } }

        instrumentation.runOnMainSync {
            strip = WindowStripRow(true, label(R.string.window_strip_status_active).format(171), changeable = true)
        }
        await("the reserved width") { text(label(R.string.window_strip_status_active).format(171)) != null }
        assertEquals(true, control(title)?.isChecked)
        capture("fixture-firmware-strip-on")
    }

    fun testTheOptionCannotBeChangedWithoutTheServiceOrWhileWorkIsRunning() {
        val title = label(R.string.window_strip_title)
        instrumentation.runOnMainSync {
            strip = WindowStripRow(false, label(R.string.window_strip_status_service_off), changeable = false)
        }
        await("a disabled switch") { control(title)?.isEnabled == false }
        assertNotNull(text(label(R.string.window_strip_status_service_off)))

        instrumentation.runOnMainSync {
            strip = WindowStripRow(true, label(R.string.window_strip_status_blocked), changeable = true)
            work = MaintenanceWork.State("Working", working = true)
        }
        await("a switch that waits for the running operation") {
            control(title)?.isEnabled == false && text(label(R.string.window_strip_status_blocked)) != null
        }
        instrumentation.runOnMainSync { work = MaintenanceWork.State(null) }
        await("the switch again") { control(title)?.isEnabled == true }
        synchronized(selected) { assertTrue(selected.isEmpty()) }
    }

    fun testWithoutAWindowServiceThePageShowsNeitherRow() {
        instrumentation.runOnMainSync {
            service = null
            strip = null
        }
        await("no window rows") {
            text(label(R.string.window_strip_title)) == null && text(label(R.string.window_hook_title)) == null
        }
        // The rest of the page does not depend on the service.
        assertNotNull(text(label(R.string.cluster_title)))
        assertNotNull(text(label(R.string.translation_import)))
    }

    private fun top(node: AccessibilityNodeInfo): Int = android.graphics.Rect().also(node::getBoundsInScreen).top

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
            if (node.isClickable || node.isCheckable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return node
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
        val deadline = SystemClock.uptimeMillis() + 6_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (ready()) return
            SystemClock.sleep(50)
        }
        val seen = nodes().take(80).joinToString("\n") { "text=${it.text} checked=${it.isChecked} enabled=${it.isEnabled}" }
        throw junit.framework.AssertionFailedError("The firmware page did not reach $description\n$seen")
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
        val output = File(app.getExternalFilesDir(null), "ui-audit").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(output, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    private fun label(id: Int): String = UiText.get(id)
}
