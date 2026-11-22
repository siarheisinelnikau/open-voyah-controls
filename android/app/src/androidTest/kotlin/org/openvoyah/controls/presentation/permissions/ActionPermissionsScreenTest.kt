package org.openvoyah.controls.presentation.permissions

import android.os.Build
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.MainActivity
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.permissions.ActionCatalog
import org.openvoyah.controls.domain.permissions.ActionItem
import org.openvoyah.controls.domain.permissions.ActionPermission
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.ControlsTheme

/** Real Compose rendering of Settings → Allowed actions with explicit state; nothing is stored. */
class ActionPermissionsScreenTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext
    private var activity: MainActivity? = null
    private var savedLanguage: String? = null
    private var states by mutableStateOf<Map<ActionItem, ActionPermission>>(emptyMap())
    private val changes = CopyOnWriteArrayList<Pair<ActionItem, ActionPermission>>()
    private val resets = AtomicInteger()
    private val reboot = ActionCatalog.operation(ActionCatalog.SYSTEM_REBOOT)
    private val lowBeam = ActionCatalog.voice("LowBeam")

    override fun setUp() {
        super.setUp()
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        savedLanguage = app.getSharedPreferences("ui", 0).getString("language", null)
        UiText.language("ru")
        val launched = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.SETTINGS)) as MainActivity
        activity = launched
        instrumentation.runOnMainSync {
            launched.setContent {
                ControlsTheme {
                    val rows = actionRows(setOf(ActionCatalog.SYSTEM_REBOOT)).filter { it.item == reboot } +
                        ActionRow(lowBeam, "Ближний свет", null, verified = false)
                    ActionPermissionsScreen(rows, states, back = {},
                        change = { item, state -> changes += item to state; states = states + (item to state) },
                        reset = { resets.incrementAndGet(); states = emptyMap() })
                }
            }
        }
        await("the title") { node(label(R.string.action_permissions_title)) != null }
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

    fun testEveryItemStartsAllowedAndAChipChangesOnlyItsItem() {
        val reboot = label(R.string.action_system_reboot)
        await("the reboot row is allowed") { node("$reboot, ${label(R.string.action_state_allowed)}")?.isChecked == true }
        await("the verified mark") { node(label(R.string.action_permissions_verified)) != null }
        await("reset disabled while everything is allowed") { control(label(R.string.action_permissions_reset))?.isEnabled == false }
        click("$reboot, ${label(R.string.action_state_unverified)}")
        click("Ближний свет, ${label(R.string.action_state_forbidden)}")
        await("both changes") { changes.toList() == listOf(this.reboot to ActionPermission.UNVERIFIED, lowBeam to ActionPermission.FORBIDDEN) }
        await("the forbidden chip selected") { node("Ближний свет, ${label(R.string.action_state_forbidden)}")?.isChecked == true }
        click(label(R.string.action_permissions_reset))
        await("reset") { resets.get() == 1 }
        await("allowed again") { node("$reboot, ${label(R.string.action_state_allowed)}")?.isChecked == true }
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
        var current = node(text)
        var depth = 0
        while (current != null && depth++ < 16) {
            if (!current.refresh()) return null
            if (current.isClickable || current.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return current
            current = current.parent
        }
        return null
    }

    /** Matches visible text or a content description; scrolls the page when the label is off screen. */
    private fun node(value: String): AccessibilityNodeInfo? {
        nodes().firstOrNull { it.refresh() && (it.text?.toString() == value || it.contentDescription?.toString() == value) }
            ?.let { return it }
        val scroller = nodes().firstOrNull { it.isScrollable } ?: return null
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
        val seen = nodes().take(80).joinToString("\n") { "text=${it.text} desc=${it.contentDescription} checked=${it.isChecked}" }
        throw junit.framework.AssertionFailedError("Allowed actions did not reach $description\n$seen")
    }

    private fun label(id: Int): String = UiText.get(id)
}
