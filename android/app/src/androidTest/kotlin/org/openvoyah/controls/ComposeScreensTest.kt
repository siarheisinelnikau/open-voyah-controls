package org.openvoyah.controls

import android.app.Activity
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.io.File
import java.util.ArrayDeque

/** Real application rendering on an explicitly provisioned, isolated emulator. */
class ComposeScreensTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext
    private var active: Activity? = null
    private var startupPreference: Boolean? = null

    override fun setUp() {
        super.setUp()
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val updates = app.getSharedPreferences("updates", 0)
        startupPreference = if (updates.contains("check_on_startup")) updates.getBoolean("check_on_startup", true) else null
        check(updates.edit().putBoolean("check_on_startup", false).commit())
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun tearDown() {
        active?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
        instrumentation.waitForIdleSync()
        app.getSharedPreferences("updates", 0).edit().apply {
            startupPreference?.let { putBoolean("check_on_startup", it) } ?: remove("check_on_startup")
        }.commit()
        super.tearDown()
    }

    fun testAllOwnedActivityRoutesRenderCompose() {
        val routes = listOf(
            MainActivity::class.java to "home", AppsActivity::class.java to "apps",
            VoiceActivity::class.java to "voice", FirmwareActivity::class.java to "firmware",
            VehicleSettingsActivity::class.java to "vehicle", ClusterActivity::class.java to "cluster",
            DisplayActivity::class.java to "display",
            UpdatesActivity::class.java to "updates",
        )
        for ((type, name) in routes) {
            val activity = launch(Intent(app, type))
            assertComposeContent(activity)
            if (type == DisplayActivity::class.java) {
                val deadline = SystemClock.uptimeMillis() + 8_000
                fun ready() = nodes().any { it.text?.toString()?.matches(Regex("[0-9]+ × [0-9]+ · [0-9]+ DPI")) == true }
                while (!ready() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
                assertTrue("The virtual display did not become ready", ready())
            }
            capture(name)
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
        val setup = launch(Intent(app, SetupActivity::class.java).putExtra("request", "d".repeat(32)))
        awaitText(UiText.get(R.string.setup_result), 45_000)
        assertComposeContent(setup)
        capture("setup")
        instrumentation.runOnMainSync { setup.finish() }
        val voice = launch(QuickVoiceActivity.intent(app))
        assertComposeContent(voice)
        capture("quick-voice")
    }

    fun testHomeTabsKeepComposeAfterRefreshingAndOpeningDialogs() {
        val activity = launch(Intent(app, MainActivity::class.java))
        clickText(UiText.get(R.string.cockpit_buttons))
        awaitText(UiText.get(R.string.ui_67))
        assertComposeContent(activity)
        capture("buttons-panel")
        scrollTo(UiText.get(R.string.oem_steering))
        capture("buttons-assignments")
        clickText(UiText.get(R.string.oem_steering))
        awaitText(UiText.get(R.string.oem_restore_keys))
        capture("steering-dialog")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        capture("after-steering-back")
        clickText(UiText.get(R.string.cockpit_settings))
        scrollTo(UiText.get(R.string.ui_59))
        clickText(UiText.get(R.string.ui_59))
        awaitText(UiText.get(R.string.ui_117))
        capture("timing-dialog")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        assertComposeContent(activity)
        capture("settings-controls")
        val recreation = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { activity.recreate() }
        active = checkNotNull(instrumentation.waitForMonitorWithTimeout(recreation, 5_000))
        instrumentation.removeMonitor(recreation)
        instrumentation.waitForIdleSync()
        awaitText(UiText.get(R.string.cockpit_settings))
        assertComposeContent(checkNotNull(active))
    }

    private fun launch(intent: Intent): Activity {
        val activity = instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        active = activity
        instrumentation.waitForIdleSync()
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!activity.window.decorView.isLaidOut && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(30)
        assertTrue("The activity did not lay out", activity.window.decorView.isLaidOut)
        // Let the finite window/page transition finish before inspecting the settled frame.
        SystemClock.sleep(350)
        return activity
    }

    private fun assertComposeContent(activity: Activity) {
        var compose = false
        instrumentation.runOnMainSync {
            fun visit(view: View) {
                if (view.javaClass.name.startsWith("androidx.compose.ui.platform.")) compose = true
                assertFalse("Legacy text/control in app content: ${view.javaClass.name}", view is TextView)
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(activity.findViewById(android.R.id.content))
        }
        assertTrue("No Compose content in ${activity.javaClass.simpleName}", compose)
    }

    // Traverse virtual nodes: Android 9's text lookup does not search Compose's node provider.
    private fun nodes(): Sequence<AccessibilityNodeInfo> {
        fun walk(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            // Refresh the parent before reading its child IDs. A cached subtree can retain the old
            // scroll position; refreshing only text matches cannot discover newly visible virtual nodes.
            if (!node.refresh()) return@sequence
            yield(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(walk(it)) }
        }
        val root = instrumentation.uiAutomation.windows.firstOrNull { it.isActive }?.root
            ?: instrumentation.uiAutomation.rootInActiveWindow ?: return emptySequence()
        if (!root.refresh()) return emptySequence()
        return walk(root)
    }

    private fun textNode(text: String): AccessibilityNodeInfo? = nodes()
        .firstOrNull { it.isVisibleToUser && it.text?.toString() == text && it.refresh() &&
            it.isVisibleToUser && it.text?.toString() == text }

    private fun anyTextNode(text: String): AccessibilityNodeInfo? = nodes()
        .firstOrNull { it.text?.toString() == text && it.refresh() && it.text?.toString() == text }

    private fun awaitText(text: String, timeout: Long = 5_000) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (textNode(text) == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        if (textNode(text) == null) failWithEvidence("missing-$text", "Visible text missing: $text")
    }

    private fun clickText(text: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        val observations = ArrayDeque<String>()
        while (SystemClock.uptimeMillis() < deadline) {
            // Scroll/page transitions can replace virtual nodes after their text first becomes visible.
            // Reacquire the current tree for every attempt and require an enabled advertised click action.
            var node = textNode(text)
            val chain = mutableListOf<String>()
            var depth = 0
            while (node != null && depth++ < 16) {
                if (!node.refresh()) { chain += "stale node"; break }
                chain += describe(node)
                val clickable = node.isEnabled && node.isVisibleToUser && node.isClickable &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                if (clickable) {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        instrumentation.waitForIdleSync()
                        return
                    }
                    chain += "ACTION_CLICK returned false"
                    break
                }
                node = node.parent
            }
            if (observations.size == 8) observations.removeFirst()
            observations.addLast(chain.ifEmpty { listOf("text not visible") }.joinToString(" -> "))
            SystemClock.sleep(50)
        }
        failWithEvidence("click-$text", "No current click action for $text\n" + observations.joinToString("\n"))
    }

    private fun scrollTo(text: String) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        val observations = ArrayDeque<String>()
        val visited = mutableSetOf<Pair<Int, String>>()
        val showAttempts = mutableSetOf<String>()
        val edges = mutableSetOf<Int>()
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        fun note(value: String) {
            if (observations.size == 12) observations.removeFirst()
            observations.addLast(value)
        }
        while (SystemClock.uptimeMillis() < deadline) {
            val before = settledTree(deadline)
            if (!before.settled) { note("tree still moving"); continue }
            if (textNode(text) != null) return

            val show = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
            val published = anyTextNode(text)
            if (published?.actionList?.any { it.id == show } == true && showAttempts.add(before.signature) && published.performAction(show)) {
                val shown = settledTree(deadline, before.signature)
                note("SHOW_ON_SCREEN changed=${shown.signature != before.signature} settled=${shown.settled}")
                if (shown.settled && textNode(text) != null) return
                if (!shown.settled || shown.signature != before.signature) continue
            }

            val target = dominantScrollTarget()
            if (target == null) {
                note("scroll node not published yet")
                SystemClock.sleep(40)
                continue
            }
            val scroll = target.node
            val acted = scroll.actionList.any { it.id == direction } && scroll.performAction(direction)
            instrumentation.waitForIdleSync()
            val after = settledTree(deadline, before.signature)
            note("scroll=$direction bounds=${target.bounds} area=${target.area} accepted=$acted " +
                "changed=${after.signature != before.signature} settled=${after.settled}")
            if (!after.settled) continue
            if (textNode(text) != null) return
            val progressed = acted && after.signature != before.signature && visited.add(direction to after.signature)
            if (!progressed) {
                edges += direction
                if (edges.size == 2) break
                direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
        }
        failWithEvidence("scroll-$text", "Could not bring $text into view\n" + observations.joinToString("\n"))
    }

    private data class ScrollTarget(val node: AccessibilityNodeInfo, val bounds: Rect, val area: Long)

    private fun dominantScrollTarget(): ScrollTarget? {
        val root = instrumentation.uiAutomation.windows.firstOrNull { it.isActive }?.root
            ?: instrumentation.uiAutomation.rootInActiveWindow ?: return null
        if (!root.refresh()) return null
        val viewport = Rect().also(root::getBoundsInScreen)
        // AnimatedContent can leave a narrow outgoing-page snapshot in Android's accessibility cache.
        // Select the dominant visible viewport, rather than the first node in transition traversal order.
        return nodes().filter { it.refresh() && it.isScrollable && it.isVisibleToUser && it.windowId == root.windowId }
            .mapNotNull { node ->
                val visible = Rect().also(node::getBoundsInScreen)
                if (!visible.intersect(viewport) || visible.isEmpty) null
                else ScrollTarget(node, visible, visible.width().toLong() * visible.height())
            }.maxByOrNull { it.area }
    }

    private data class SettledTree(val signature: String, val settled: Boolean)

    private fun settledTree(deadline: Long, previous: String? = null): SettledTree {
        val started = SystemClock.uptimeMillis()
        val end = minOf(deadline, started + 1_200)
        var signature = treeSignature()
        var changedAt = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(40)
            val current = treeSignature()
            val now = SystemClock.uptimeMillis()
            if (current != signature) { signature = current; changedAt = now }
            // Observe real semantic/bounds progress before accepting a scroll's settled frame.
            if (signature.isNotEmpty() && now - changedAt >= 120 &&
                (previous == null || signature != previous || now - started >= 300)) return SettledTree(signature, true)
        }
        return SettledTree(signature, false)
    }

    private fun treeSignature(): String = nodes().take(100).filter { it.refresh() && it.isVisibleToUser &&
        (it.text != null || it.isScrollable) }.joinToString("|") {
        val bounds = Rect().also(it::getBoundsInScreen)
        "${it.text}:${it.isEnabled}:${it.isScrollable}:$bounds:${it.actionList.map { action -> action.id }}"
    }

    private fun failWithEvidence(name: String, message: String): Nothing {
        val safeName = name.lowercase().replace(Regex("[^a-z0-9-]+"), "-").take(70).trim('-')
        val windows = instrumentation.uiAutomation.windows.joinToString("\n") {
            "window=${it.id} type=${it.type} active=${it.isActive} focused=${it.isFocused} title=${it.title}"
        }
        val tree = nodes().take(120).joinToString("\n", transform = ::describe)
        val evidence = "$message\n$windows\n$tree"
        println(evidence)
        runCatching {
            val output = File(app.getExternalFilesDir(null), "ui-audit").apply { mkdirs() }
            File(output, "failure-$safeName.txt").writeText(evidence)
            capture("failure-$safeName", settle = false)
        }.onFailure { println("Could not save failure evidence: ${it.javaClass.simpleName}") }
        throw junit.framework.AssertionFailedError(message)
    }

    private fun describe(node: AccessibilityNodeInfo): String {
        val bounds = Rect().also(node::getBoundsInScreen)
        return "text=${node.text?.toString()?.take(120)} class=${node.className} window=${node.windowId} " +
            "visible=${node.isVisibleToUser} enabled=${node.isEnabled} clickable=${node.isClickable} bounds=$bounds " +
            "actions=${node.actionList.map { it.id }}"
    }

    private fun capture(name: String, settle: Boolean = true) {
        if (settle) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300)
        }
        val output = File(app.getExternalFilesDir(null), "ui-audit").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
