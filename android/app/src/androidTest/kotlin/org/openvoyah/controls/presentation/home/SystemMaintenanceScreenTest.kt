package org.openvoyah.controls.presentation.home

import android.os.Build
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.util.concurrent.atomic.AtomicInteger
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.MainActivity
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.ControlsTheme

/** Real Compose rendering of the last Settings section with explicit state; nothing is restarted. */
class SystemMaintenanceScreenTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext
    private var activity: MainActivity? = null
    private var savedLanguage: String? = null
    private var working by mutableStateOf<SystemOperation?>(null)
    private val copies = AtomicInteger()
    private val appRestarts = AtomicInteger()
    private val mediaRestarts = AtomicInteger()
    private val reboots = AtomicInteger()
    private val version = listOf("Open Voyah Controls 0.20.1 (24)", "Build: debug · commit 3752fe3a+", "Installed: Nov 22, 2026, 3:18 PM")

    override fun setUp() {
        super.setUp()
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        savedLanguage = app.getSharedPreferences("ui", 0).getString("language", null)
        UiText.language("en")
        val launched = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.SETTINGS)) as MainActivity
        activity = launched
        instrumentation.runOnMainSync {
            launched.setContent {
                ControlsTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SystemMaintenance(version, working, copied = false,
                            copy = { copies.incrementAndGet() },
                            restartApp = { appRestarts.incrementAndGet() },
                            restartMedia = { mediaRestarts.incrementAndGet() },
                            reboot = { reboots.incrementAndGet() })
                    }
                }
            }
        }
        await("the section title") { text(label(R.string.system_section_title)) != null }
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

    fun testTheVersionIsShownAndEveryActionReachesItsCallback() {
        await("the version lines") { text(version.joinToString("\n")) != null }
        click(label(R.string.system_version_copy))
        click(label(R.string.system_restart_app))
        click(label(R.string.system_restart_media))
        click(label(R.string.system_reboot))
        await("every callback") { copies.get() == 1 && appRestarts.get() == 1 && mediaRestarts.get() == 1 && reboots.get() == 1 }
        assertNotNull(text(label(R.string.system_restart_app_hint)))
    }

    fun testRestartsAreDisabledWhileAnOperationRuns() {
        instrumentation.runOnMainSync { working = SystemOperation.MEDIA }
        await("the working hint") { text(label(R.string.system_restart_media_working)) != null }
        for (id in listOf(R.string.system_reboot, R.string.system_restart_media, R.string.system_restart_app)) {
            await("${label(id)} disabled") { control(label(id))?.isEnabled == false }
        }
        // Copying the version stays available for a bug report.
        await("copy enabled") { control(label(R.string.system_version_copy))?.isEnabled == true }
        instrumentation.runOnMainSync { working = null }
        await("the restarts enabled again") { control(label(R.string.system_reboot))?.isEnabled == true }
        await("the working hint gone") {
            nodes().none { it.refresh() && it.text?.toString() == label(R.string.system_restart_media_working) }
        }
    }

    private fun click(text: String) {
        await("actionable $text") {
            val target = control(text) ?: return@await false
            if (!target.isVisibleToUser) target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            target.isEnabled && target.isVisibleToUser && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }

    private fun control(text: String): AccessibilityNodeInfo? {
        var node = text(text)
        var depth = 0
        while (node != null && depth++ < 16) {
            if (!node.refresh()) return null
            if (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return node
            node = node.parent
        }
        return null
    }

    /** The section is taller than the 480 dp emulator window, and Compose reports only nodes on screen. */
    private fun text(value: String): AccessibilityNodeInfo? {
        nodes().firstOrNull { it.refresh() && it.text?.toString() == value }?.let { return it }
        val scroller = nodes().firstOrNull { it.isScrollable } ?: return null
        // Sweep down to the end and back up, so a label above or below the window is reached either way.
        if (!scroller.performAction(if (downward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            downward = !downward
        }
        return null
    }
    private var downward = true

    private fun nodes(): Sequence<AccessibilityNodeInfo> {
        fun walk(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            yield(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(walk(it)) }
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptySequence()
        return if (root.refresh()) walk(root) else emptySequence()
    }

    private fun await(description: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (ready()) return
            SystemClock.sleep(50)
        }
        val seen = nodes().take(80).joinToString("\n") { "text=${it.text} enabled=${it.isEnabled}" }
        throw junit.framework.AssertionFailedError("The system section did not reach $description\n$seen")
    }

    private fun label(id: Int): String = UiText.get(id)
}
