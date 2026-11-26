package org.openvoyah.controls.presentation.updates

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.MainActivity
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.updates.ReleasePolicy
import org.openvoyah.controls.data.updates.DownloadProgress
import org.openvoyah.controls.data.updates.UpdateCheck
import org.openvoyah.controls.data.updates.UpdateCheckPhase
import org.openvoyah.controls.data.updates.UpdateProblem
import org.openvoyah.controls.domain.settings.AppAppearance
import org.openvoyah.controls.platform.updates.UpdateInstallPhase
import org.openvoyah.controls.platform.updates.UpdateInstallState
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.ControlsTheme
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/** Real Compose rendering and accessibility actions with explicit test-only state and callbacks. */
class UpdatesScreenTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext
    private var activity: MainActivity? = null
    private lateinit var fixture: Fixture
    private var savedLanguage: String? = null
    private var savedEffectiveLanguage: String? = null
    private var startupPreference: Boolean? = null

    override fun setUp() {
        super.setUp()
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        savedLanguage = app.getSharedPreferences("ui", 0).getString("language", null)
        savedEffectiveLanguage = UiText.language()
        val updates = app.getSharedPreferences("updates", 0)
        startupPreference = if (updates.contains("check_on_startup")) updates.getBoolean("check_on_startup", true) else null
        check(updates.edit().putBoolean("check_on_startup", false).commit())
        UiText.language("en")
        fixture = Fixture()
        val launched = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.UPDATES)) as MainActivity
        activity = launched
        instrumentation.runOnMainSync {
            // Replace only this test activity's content. No real repository, installer or settings callback is invoked.
            launched.setContent {
                key(fixture.language) {
                    ControlsTheme(appearance = AppAppearance(animateRoutes = false)) {
                        UpdatesScreen(fixture.state,
                            back = { fixture.back.incrementAndGet() },
                            check = { fixture.check.incrementAndGet() },
                            startup = { enabled ->
                                fixture.startup.incrementAndGet()
                                fixture.state = fixture.state.copy(checkOnStartup = enabled)
                            },
                            install = {
                                fixture.install.incrementAndGet()
                                fixture.state = fixture.state.copy(work = UpdateWork.DOWNLOADING,
                                    download = DownloadProgress(42, 100), problem = null, cancelled = false)
                            },
                            cancel = {
                                fixture.cancel.incrementAndGet()
                                fixture.state = fixture.state.copy(work = UpdateWork.IDLE, download = null, cancelled = true)
                            },
                            discard = {
                                fixture.discard.incrementAndGet()
                                fixture.state = fixture.state.copy(installation = UpdateInstallState(UpdateInstallPhase.CANCELLED))
                            },
                            allowInstallation = { fixture.permission.incrementAndGet() },
                            installKit = { fixture.installKit.incrementAndGet() })
                    }
                }
            }
        }
        scrollTo(label(R.string.updates_available_version).format("0.19.0"))
    }

    override fun tearDown() {
        try {
            try {
                // Restore before another app activity can resume. UiText reads this preference on every call.
                check(app.getSharedPreferences("ui", 0).edit().apply {
                    savedLanguage?.let { putString("language", it) } ?: remove("language")
                }.commit())
                check(app.getSharedPreferences("updates", 0).edit().apply {
                    startupPreference?.let { putBoolean("check_on_startup", it) } ?: remove("check_on_startup")
                }.commit())
                savedEffectiveLanguage?.let { assertEquals("UI language was not restored", it, UiText.language()) }
            } finally {
                activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
                instrumentation.waitForIdleSync()
            }
        } finally { super.tearDown() }
    }

    fun testAvailableDownloadAndCancelUseCorrectActions() {
        assertEnabled(R.string.updates_check, true)
        assertEnabled(R.string.updates_download_install, true)
        assertEquals(0, fixture.install.get())
        capture("fixture-updates-available")

        click(label(R.string.updates_download_install))
        await("download callback") { fixture.install.get() == 1 }
        assertEnabled(R.string.updates_check, false)
        assertEnabled(R.string.updates_download_install, false)
        scrollTo(label(R.string.updates_cancel_download))
        assertEnabled(R.string.updates_cancel_download, true)
        capture("fixture-updates-downloading")

        click(label(R.string.updates_cancel_download))
        await("cancel callback and result") {
            fixture.cancel.get() == 1 && textNode(label(R.string.updates_cancelled)) != null
        }
        scrollTo(label(R.string.updates_download_install))
        assertEnabled(R.string.updates_download_install, true)
        assertEquals(1, fixture.install.get())
        capture("fixture-updates-cancelled")
    }

    fun testInterruptedInstallOffersDiscardAndDisablesAnotherInstall() {
        show(available().copy(installation = UpdateInstallState(UpdateInstallPhase.INTERRUPTED)),
            guidance = R.string.updates_interrupted)
        assertEnabled(R.string.updates_download_install, false)
        scrollTo(label(R.string.updates_cancel_pending))
        assertEnabled(R.string.updates_cancel_pending, true)
        capture("fixture-updates-interrupted")
        click(label(R.string.updates_cancel_pending))
        await("discard callback and result") {
            fixture.discard.get() == 1 && textNode(label(R.string.updates_cancelled)) != null
        }
        assertEquals(0, fixture.install.get())
        scrollTo(label(R.string.updates_download_install))
        assertEnabled(R.string.updates_download_install, true)
    }

    fun testSignedKitHasAnExplicitActionAndKeepsApkActionAvailable() {
        val name = "OpenVoyahControlsKit-v0.20.0.zip"
        val tag = "fixture-v0.20.0"
        val kit = checkNotNull(ReleasePolicy.kitCandidate(tag, false, false, name,
            "${ReleasePolicy.DOWNLOAD_PREFIX}$tag/$name", 200, "sha256:" + "b".repeat(64)))
        val state = available()
        show(state.copy(check = state.check.copy(kit = kit)), guidance = R.string.updates_verify_before_install)
        val kitAction = label(R.string.updates_download_kit).format(kit.version)
        scrollTo(kitAction)
        click(kitAction)
        await("kit chosen") { fixture.installKit.get() == 1 }
        val appAction = label(R.string.updates_download_app).format(state.check.candidate?.version)
        scrollTo(appAction)
        assertTrue(control(appAction)?.isEnabled == true)
        assertEquals(0, fixture.install.get())
    }

    fun testPermissionRecoveryAndLongRussianTextRemainReachable() {
        show(available().copy(problem = UpdateProblem.INSTALL_PERMISSION), guidance = R.string.updates_error_permission)
        scrollTo(label(R.string.updates_allow_installation))
        assertEnabled(R.string.updates_allow_installation, true)
        capture("fixture-updates-permission-error")
        click(label(R.string.updates_allow_installation))
        await("permission callback") { fixture.permission.get() == 1 }
        assertEquals(0, fixture.install.get())

        show(available().copy(installation = UpdateInstallState(UpdateInstallPhase.INTERRUPTED)), "ru",
            guidance = R.string.updates_interrupted)
        scrollTo(label(R.string.updates_startup_toggle))
        click(label(R.string.updates_startup_toggle))
        await("Russian startup switch") {
            fixture.startup.get() == 1 && control(label(R.string.updates_startup_toggle))?.isChecked == true
        }
        scrollTo(label(R.string.updates_cancel_pending))
        assertEnabled(R.string.updates_cancel_pending, true)
        await("long Russian interruption guidance") { textNode(label(R.string.updates_interrupted)) != null }
        capture("fixture-updates-russian-interrupted")
        click(label(R.string.updates_cancel_pending))
        await("Russian discard callback") { fixture.discard.get() == 1 }
        assertEquals(0, fixture.install.get())
        assertEquals(1, fixture.permission.get())
    }

    private fun show(state: UpdatesState, language: String = "en", guidance: Int) {
        instrumentation.runOnMainSync {
            UiText.language(language)
            fixture.language = language
            fixture.state = state
        }
        // An unchanged screen title says nothing about whether the new card has composed and laid out.
        // The actual guidance belongs to the requested state; semantic scrolling also handles offscreen cards.
        scrollTo(label(guidance))
    }

    private fun assertEnabled(id: Int, expected: Boolean) {
        val text = label(id)
        scrollTo(text)
        await("$text enabled=$expected") { control(text)?.isEnabled == expected }
    }

    private fun click(text: String) {
        await("actionable $text") {
            val target = control(text) ?: return@await false
            target.isEnabled && target.isVisibleToUser && target.refresh() &&
                target.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } &&
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }

    private fun control(text: String): AccessibilityNodeInfo? {
        var node = textNode(text)
        var depth = 0
        while (node != null && depth++ < 16) {
            if (!node.refresh()) return null
            val role = node.className?.toString()
            if (node.isClickable || role == "android.widget.Button" || role == "android.widget.Switch" ||
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return node
            node = node.parent
        }
        return null
    }

    private fun textNode(text: String): AccessibilityNodeInfo? = nodes().firstOrNull {
        it.isVisibleToUser && it.text?.toString() == text && it.refresh() && it.isVisibleToUser && it.text?.toString() == text
    }

    private fun anyTextNode(text: String): AccessibilityNodeInfo? = nodes().firstOrNull {
        it.text?.toString() == text && it.refresh() && it.text?.toString() == text
    }

    private fun nodes(): Sequence<AccessibilityNodeInfo> {
        fun walk(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            yield(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(walk(it)) }
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptySequence()
        if (!root.refresh()) return emptySequence()
        return walk(root)
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
            val scroll = nodes().firstOrNull { it.refresh() && it.isScrollable && it.isVisibleToUser }
            if (scroll == null) {
                note("scroll node not published yet")
                SystemClock.sleep(40)
                continue
            }
            val acted = scroll.actionList.any { it.id == direction } && scroll.performAction(direction)
            instrumentation.waitForIdleSync()
            val after = settledTree(deadline, before.signature)
            note("scroll=$direction accepted=$acted changed=${after.signature != before.signature} settled=${after.settled}")
            if (!after.settled) continue
            if (textNode(text) != null) return
            val progressed = acted && after.signature != before.signature && visited.add(direction to after.signature)
            if (!progressed) {
                edges += direction
                if (edges.size == 2) {
                    // Compose can expose the previous layout while the next frame publishes semantics.
                    // Retry the complete viewport under the same deadline even after both edges were visited.
                    edges.clear()
                    visited.clear()
                    showAttempts.clear()
                    SystemClock.sleep(80)
                }
                direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
        }
        failEvidence("scroll target $text\n" + observations.joinToString("\n"))
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

    private fun await(description: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (ready()) return
            SystemClock.sleep(50)
        }
        failEvidence(description)
    }

    private fun failEvidence(description: String): Nothing {
        val evidence = nodes().take(100).joinToString("\n") {
            val bounds = Rect().also(it::getBoundsInScreen)
            "text=${it.text} enabled=${it.isEnabled} clickable=${it.isClickable} bounds=$bounds actions=${it.actionList.map { action -> action.id }}"
        }
        val message = "Updates fixture did not reach $description\n$evidence"
        println(message)
        runCatching {
            val filename = "fixture-updates-failure-${name.lowercase()}"
            val output = File(app.getExternalFilesDir(null), "ui-audit").apply { mkdirs() }
            File(output, "$filename.txt").writeText(message)
            capture(filename, settle = false)
        }
        throw junit.framework.AssertionFailedError("Updates fixture did not reach $description")
    }

    private fun capture(name: String, settle: Boolean = true) {
        // Shared window/page/section transitions last at most 240 ms; actions above use semantic postconditions.
        if (settle) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300)
        }
        val output = File(app.getExternalFilesDir(null), "ui-audit").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(output, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    private fun label(id: Int): String = UiText.get(id)

    private class Fixture {
        var state by mutableStateOf(available())
        var language by mutableStateOf("en")
        val back = AtomicInteger()
        val check = AtomicInteger()
        val startup = AtomicInteger()
        val install = AtomicInteger()
        val installKit = AtomicInteger()
        val cancel = AtomicInteger()
        val discard = AtomicInteger()
        val permission = AtomicInteger()
    }

    private companion object {
        fun available(): UpdatesState {
            val release = checkNotNull(ReleasePolicy.candidate("fixture-v0.19.0", false, false,
                "OpenVoyahControls-v0.19.0.apk", "${ReleasePolicy.DOWNLOAD_PREFIX}fixture-v0.19.0/OpenVoyahControls-v0.19.0.apk",
                100, "sha256:" + "a".repeat(64)))
            return UpdatesState(installedVersion = "0.18.0", checkOnStartup = false,
                check = UpdateCheck(UpdateCheckPhase.COMPLETE, release), installAvailable = true)
        }
    }
}
