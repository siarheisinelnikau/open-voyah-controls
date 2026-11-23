package org.openvoyah.controls.presentation.keyboard

import android.os.Build
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.CopyOnWriteArrayList
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.MainActivity
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.keyboard.KeyboardAvailability
import org.openvoyah.controls.domain.keyboard.KeyboardFallback
import org.openvoyah.controls.domain.keyboard.KeyboardOption
import org.openvoyah.controls.domain.keyboard.KeyboardStatus
import org.openvoyah.controls.platform.keyboard.InstalledKeyboard
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.ControlsTheme

/** Real Compose rendering of the Keyboard page with explicit state; nothing reaches Android's keyboard settings. */
class KeyboardScreenTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext
    private var activity: MainActivity? = null
    private var savedLanguage: String? = null
    private var shown by mutableStateOf(state(KeyboardAvailability.READY, KeyboardStatus(active = AOSP)))
    private val actions = CopyOnWriteArrayList<String>()

    override fun setUp() {
        super.setUp()
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        savedLanguage = app.getSharedPreferences("ui", 0).getString("language", null)
        UiText.language("en")
        val launched = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.KEYBOARD)) as MainActivity
        activity = launched
        instrumentation.runOnMainSync {
            launched.setContent {
                ControlsTheme {
                    KeyboardScreen(shown, back = {}, select = { actions += "select $it" }, factory = { actions += "factory" })
                }
            }
        }
        await("the keyboard title") { text(label(R.string.keyboard_title)) != null }
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

    fun testTheFactoryKeyboardIsSelectedAndAnInstalledOneCanBeChosen() {
        await("the bound keyboard") { text(label(R.string.keyboard_active).format("Android Keyboard (AOSP)")) != null }
        await("the factory keyboard selected") {
            control(label(R.string.keyboard_factory))?.isChecked == true && control("Yandex Keyboard")?.isChecked == false
        }
        click("Yandex Keyboard")
        await("the choice callback") { actions == listOf("select $YANDEX") }
    }

    fun testWithoutTheWindowServiceOnlyTheFactoryKeyboardCanBeChosen() {
        instrumentation.runOnMainSync {
            shown = state(KeyboardAvailability.WINDOW_SERVICE_OFF, KeyboardStatus(choice = YANDEX, active = AOSP))
        }
        await("the window service hint") { text(label(R.string.keyboard_window_service_off)) != null }
        await("only the factory keyboard enabled") {
            control("Yandex Keyboard")?.isEnabled == false && control(label(R.string.keyboard_factory))?.isEnabled == true
        }
        click(label(R.string.keyboard_factory))
        await("the factory callback") { actions == listOf("factory") }
    }

    fun testADroppedKeyboardIsNamedWithItsReason() {
        instrumentation.runOnMainSync {
            shown = state(KeyboardAvailability.READY, KeyboardStatus(active = AOSP, passenger = PASSENGER,
                fallback = KeyboardFallback.CRASHED, fallbackId = YANDEX))
        }
        await("the crash notice") { text(label(R.string.keyboard_fallback_crashed).format("Yandex Keyboard")) != null }
        await("the passenger keyboard") { text(label(R.string.keyboard_passenger).format("Factory passenger keyboard")) != null }
    }

    fun testAConfirmedChoiceAndItsResultAreShown() {
        instrumentation.runOnMainSync {
            shown = state(KeyboardAvailability.READY, KeyboardStatus(choice = YANDEX, active = YANDEX))
                .copy(notice = KeyboardNotice(KeyboardNotice.Kind.APPLIED, YANDEX))
        }
        // The result line sits below the fold of the landscape page.
        await("the applied notice") {
            nodes().firstOrNull { it.refresh() && it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            present(label(R.string.keyboard_applied).format("Yandex Keyboard"))
        }
        await("the chosen keyboard selected") {
            control("Yandex Keyboard")?.isChecked == true && control(label(R.string.keyboard_factory_detail))?.isChecked == false
        }
    }

    private fun click(text: String) {
        await("actionable $text") {
            val target = control(text) ?: return@await false
            target.isEnabled && target.isVisibleToUser && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }

    /** The selectable row holding [text]: rows keep their texts as children, which name the row. */
    private fun control(text: String): AccessibilityNodeInfo? = nodes().firstOrNull { node ->
        node.refresh() && node.isCheckable && (0 until node.childCount).any { node.getChild(it)?.text?.toString() == text }
    } ?: text(text)?.takeIf { it.isClickable }

    private fun text(value: String): AccessibilityNodeInfo? =
        nodes().firstOrNull { it.refresh() && it.isVisibleToUser && it.text?.toString() == value }

    private fun present(value: String): Boolean = nodes().any { it.refresh() && it.text?.toString() == value }

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
        val seen = nodes().take(80).joinToString("\n") { "text=${it.text} checked=${it.isChecked} enabled=${it.isEnabled}" }
        throw junit.framework.AssertionFailedError("The keyboard page did not reach $description\n$seen")
    }

    private fun label(id: Int): String = UiText.get(id)

    private companion object {
        const val YANDEX = "ru.yandex.androidkeyboard/com.android.inputmethod.latin.LatinIME"
        const val AOSP = "com.android.inputmethod.latin/.LatinIME"
        const val PASSENGER = "com.qinggan.app.qgime.second/com.qinggan.app.qgime.QingganIME"

        fun state(availability: KeyboardAvailability, status: KeyboardStatus) = KeyboardViewModel.State(
            loading = false,
            availability = availability,
            keyboards = listOf(InstalledKeyboard(KeyboardOption(YANDEX, "Yandex Keyboard", "ru.yandex.androidkeyboard"), null)),
            labels = mapOf(YANDEX to "Yandex Keyboard", AOSP to "Android Keyboard (AOSP)", PASSENGER to "Factory passenger keyboard"),
            status = status,
        )
    }
}
