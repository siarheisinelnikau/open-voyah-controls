package org.openvoyah.controls.presentation.diagnostics

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
import org.openvoyah.controls.diagnostics.LogLevel
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.ControlsTheme
import java.util.concurrent.atomic.AtomicInteger

/** Real Compose rendering of the diagnostic log with explicit state; no journal file is read or cleared. */
class DiagnosticsScreenTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext
    private var activity: MainActivity? = null
    private var savedLanguage: String? = null
    private var shown by mutableStateOf(DiagnosticsViewModel.State(loading = false, entries = journal()))
    private val refreshes = AtomicInteger()
    private val copies = AtomicInteger()
    private val saves = AtomicInteger()
    private val clears = AtomicInteger()

    override fun setUp() {
        super.setUp()
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        savedLanguage = app.getSharedPreferences("ui", 0).getString("language", null)
        UiText.language("en")
        val launched = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.DIAGNOSTICS)) as MainActivity
        activity = launched
        instrumentation.runOnMainSync {
            launched.setContent {
                ControlsTheme {
                    DiagnosticsScreen(shown, back = {},
                        refresh = { refreshes.incrementAndGet() },
                        problemsOnly = { enabled -> shown = shown.copy(problemsOnly = enabled) },
                        copy = { copies.incrementAndGet() },
                        save = { saves.incrementAndGet() },
                        clear = { clears.incrementAndGet() })
                }
            }
        }
        await("the log title") { text(label(R.string.diagnostics_title)) != null }
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

    fun testEqualEntriesOfOneSecondAreBothListedAndActionsReachTheirCallbacks() {
        // Two identical lines of one second used to share a list key, which ends a lazy list with an exception.
        await("both equal entries") { described("Warning, 10:00:02, bridge connect").size == 2 }
        assertEquals(1, described("Error, 10:00:03, update install").size)
        assertEquals(1, described("Event, 10:00:01, process start").size)

        click(label(R.string.diagnostics_refresh))
        click(label(R.string.diagnostics_copy))
        click(label(R.string.diagnostics_save))
        click(label(R.string.diagnostics_clear))
        await("every action callback") {
            refreshes.get() == 1 && copies.get() == 1 && saves.get() == 1 && clears.get() == 1
        }
    }

    fun testProblemsOnlyHidesInformationAndNamesTheCount() {
        val switch = label(R.string.diagnostics_problems_only).format(3)
        click(switch)
        await("only warnings and errors") {
            shown.problemsOnly && described("Event, 10:00:01, process start").isEmpty() &&
                described("Warning, 10:00:02, bridge connect").size == 2 && described("Error, 10:00:03, update install").size == 1
        }
        instrumentation.runOnMainSync { shown = shown.copy(entries = shown.entries.filter { it.level == LogLevel.INFO }) }
        await("the no-problems hint") { text(label(R.string.diagnostics_no_problems)) != null }
    }

    fun testAnEmptyJournalExplainsItselfAndDisablesWhatNeedsEntries() {
        instrumentation.runOnMainSync { shown = DiagnosticsViewModel.State(loading = false) }
        await("the empty hint") { text(label(R.string.diagnostics_empty)) != null }
        assertEquals(true, control(label(R.string.diagnostics_refresh))?.isEnabled)
        for (id in listOf(R.string.diagnostics_copy, R.string.diagnostics_save, R.string.diagnostics_clear)) {
            assertEquals(label(id), false, control(label(id))?.isEnabled)
        }
        instrumentation.runOnMainSync { shown = DiagnosticsViewModel.State(loading = true) }
        await("the loading hint") { text(label(R.string.diagnostics_loading)) != null }
    }

    private fun click(text: String) {
        await("actionable $text") {
            val target = control(text) ?: return@await false
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

    private fun text(value: String): AccessibilityNodeInfo? =
        nodes().firstOrNull { it.refresh() && it.isVisibleToUser && it.text?.toString() == value }

    private fun described(value: String): List<AccessibilityNodeInfo> =
        nodes().filter { it.refresh() && it.isVisibleToUser && it.contentDescription?.toString() == value }.toList()

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
        val seen = nodes().take(80).joinToString("\n") { "text=${it.text} description=${it.contentDescription} enabled=${it.isEnabled}" }
        throw junit.framework.AssertionFailedError("The diagnostic log did not reach $description\n$seen")
    }

    private fun label(id: Int): String = UiText.get(id)

    private companion object {
        fun journal(): List<DiagnosticEntry> = listOf(
            "2026-01-05T10:00:03.120+03:00 E event=update_install outcome=failed error=IOException",
            "2026-01-05T10:00:02.900+03:00 W event=bridge_connect outcome=failed elapsed_ms=812",
            "2026-01-05T10:00:02.100+03:00 W event=bridge_connect outcome=failed elapsed_ms=812",
            "2026-01-05T10:00:01.000+03:00 I event=process_start version=0.19.0 sdk=30",
        ).map { checkNotNull(DiagnosticEntry.parse(it)) }
    }
}
