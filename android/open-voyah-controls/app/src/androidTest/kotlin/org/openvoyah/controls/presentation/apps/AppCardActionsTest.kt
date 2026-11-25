package org.openvoyah.controls.presentation.apps

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.abs
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.InstalledApps
import org.openvoyah.controls.MainActivity
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.appWindowConfiguration
import org.openvoyah.controls.domain.apps.AppControlRefusal
import org.openvoyah.controls.platform.apps.AndroidAppAccess
import org.openvoyah.controls.platform.apps.AppPermissionControl
import org.openvoyah.controls.platform.apps.AppPermissions
import org.openvoyah.controls.platform.apps.AppRemoveControl
import org.openvoyah.controls.platform.apps.AppStop
import org.openvoyah.controls.platform.apps.AppStopControl
import org.openvoyah.controls.platform.apps.InstallOrigin
import org.openvoyah.controls.platform.apps.InstalledAppIdentity
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.platform.apps.PermissionChange
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.ControlsTheme

/**
 * The actions at the top of an app's card stay where they are. The real page is drawn with explicit states at the
 * full page width (the car's 1280 dp canvas) and narrower widths: each action keeps its bounds on screen, in one fixed
 * order, and an action whose own look did not change draws exactly the same pixels whatever else changed on the card.
 *
 * Crops of the top of the card for every state land in files/ui-audit/app-card next to a text report, for review.
 */
class AppCardActionsTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext
    private var activity: MainActivity? = null
    private var savedLanguage: String? = null
    private var savedUpdateCheck: Boolean? = null
    private var shown by mutableStateOf<Shown?>(null)

    private class Shown(val card: Card, val width: Dp?)

    private class Card(
        val name: String,
        val state: AppSettingsViewModel.State,
        val shortcut: AppShortcutGridState?,
        val movable: Boolean = true,
    )

    /** What one action showed: where it was and how it looked to accessibility. */
    private data class Seen(val bounds: Rect, val text: String, val enabled: Boolean, val checked: Boolean)

    override fun setUp() {
        super.setUp()
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        savedLanguage = app.getSharedPreferences("ui", 0).getString("language", null)
        // The update check at start can open a dialog over the card, which would dim every screenshot.
        val updates = app.getSharedPreferences("updates", 0)
        savedUpdateCheck = if (updates.contains("check_on_startup")) updates.getBoolean("check_on_startup", true) else null
        check(updates.edit().putBoolean("check_on_startup", false).commit())
        val launched = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.SETTINGS)) as MainActivity
        activity = launched
        val model = AppSettingsViewModel(appWindowConfiguration(app), NoPermissions, NoStop, AppRemoveControl.None)
        instrumentation.runOnMainSync {
            launched.setContent {
                ControlsTheme {
                    val current = shown ?: return@ControlsTheme
                    val frame = current.width?.let { Modifier.fillMaxHeight().width(it) } ?: Modifier
                    Box(frame) {
                        AppSettingsScreen(current.card.state, model, { emptyList() }, { false },
                            shortcut = current.card.shortcut, setPinned = {}, setHidden = {},
                            move = if (current.card.movable) ({}) else null,
                            open = { _, _ -> }, openAdjacent = {}, appInfo = null, specialAccess = { null })
                    }
                }
            }
        }
    }

    override fun tearDown() {
        try {
            check(app.getSharedPreferences("ui", 0).edit().apply {
                savedLanguage?.let { putString("language", it) } ?: remove("language")
            }.commit())
            check(app.getSharedPreferences("updates", 0).edit().apply {
                savedUpdateCheck?.let { putBoolean("check_on_startup", it) } ?: remove("check_on_startup")
            }.commit())
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            instrumentation.waitForIdleSync()
        } finally { super.tearDown() }
    }

    fun testEveryActionKeepsItsPlaceInEveryState() {
        // Controls draws on a canvas of at least 1280 x 480 dp whatever the system density, so both are named.
        val drawn = checkNotNull(activity).resources.displayMetrics.densityDpi
        val density = "sys${Resources.getSystem().displayMetrics.densityDpi}-ovc$drawn"
        val output = File(app.filesDir, "ui-audit/app-card").apply { check(isDirectory || mkdirs()) }
        val report = StringBuilder()
        val failures = mutableListOf<String>()
        for (language in listOf("ru", "en")) {
            // Titles are read while composing, and the app recreates its screens on a language change: start afresh.
            show(null)
            UiText.language(language)
            val slots = slots()
            // Russian has the longest titles and is the car's language; English checks the full width only.
            val widths = if (language == "ru") listOf(null, 1100.dp, 900.dp, 700.dp) else listOf(null)
            for (width in widths) {
                val where = "$language ${width?.value?.toInt() ?: "full"}dp @${density}dpi"
                var reference: Map<String, Seen>? = null
                var referencePixels: Map<String, Bitmap>? = null
                for (card in cards()) {
                    show(Shown(card, width))
                    val seen = observe(slots)
                    val screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        report.append("$where ${card.name}:\n")
                        slots.keys.forEach { slot ->
                            val action = seen[slot]
                            report.append("  $slot ${action?.let { "${it.bounds.toShortString()} enabled=${it.enabled} " +
                                "checked=${it.checked} '${it.text}'" } ?: "absent"}\n")
                        }
                        saveTop(screen, seen.values.map(Seen::bounds),
                            File(output, "${density}dpi-$language-${width?.value?.toInt() ?: "full"}-${card.name}.png"))
                        val missing = slots.keys - seen.keys
                        if (missing.isNotEmpty()) failures += "$where ${card.name}: missing $missing"
                        // Reading order: line by line, left to right within a line.
                        val order = seen.entries.sortedWith { a, b ->
                            val first = a.value.bounds
                            val second = b.value.bounds
                            val sameLine = abs(first.centerY() - second.centerY()) < minOf(first.height(), second.height()) / 2
                            if (sameLine) first.left - second.left else first.top - second.top
                        }.map { it.key }
                        if (order != slots.keys.filter { it in seen }) failures += "$where ${card.name}: order $order"
                        val pixels = seen.mapValues { (_, action) -> crop(screen, action.bounds) }
                        val first = reference
                        if (first == null) {
                            reference = seen
                            referencePixels = pixels
                        } else {
                            for ((slot, action) in seen) {
                                val before = first[slot] ?: continue
                                if (action.bounds != before.bounds) {
                                    failures += "$where ${card.name}: $slot moved ${before.bounds.toShortString()} -> " +
                                        action.bounds.toShortString()
                                } else if (action.copy(bounds = before.bounds) == before &&
                                    !checkNotNull(referencePixels)[slot]!!.sameAs(pixels[slot])) {
                                    failures += "$where ${card.name}: $slot looks different although its state did not change"
                                }
                            }
                            pixels.values.forEach(Bitmap::recycle)
                        }
                    } finally {
                        screen.recycle()
                    }
                }
                referencePixels?.values?.forEach(Bitmap::recycle)
            }
        }
        File(output, "report-${density}dpi.txt").writeText(report.toString() + "\nFailures:\n" + failures.joinToString("\n"))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun show(next: Shown?) {
        instrumentation.runOnMainSync { shown = next }
        instrumentation.waitForIdleSync()
        // The switch thumb and the colors of a disabled button animate for a moment after a change.
        SystemClock.sleep(SETTLE_MS)
        instrumentation.waitForIdleSync()
    }

    /** The actions on screen, by slot; the same action found twice is a failure of its own. */
    private fun observe(slots: Map<String, Set<String>>): Map<String, Seen> {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (true) {
            val found = mutableMapOf<String, Seen>()
            var heading = false
            for ((node, path) in nodes()) {
                val text = node.text?.toString() ?: continue
                if (text.startsWith(LABEL)) heading = true
                val slot = slots.entries.firstOrNull { text in it.value }?.key ?: continue
                check(slot !in found) { "Two '$slot' actions on the card" }
                // The label is a child of its button or switch row, which holds the bounds and the enabled state.
                val action = path.lastOrNull { it.isClickable || it.isCheckable } ?: node
                found[slot] = Seen(Rect().also(action::getBoundsInScreen), text, action.isEnabled, action.isChecked)
            }
            if ((heading && found.size == slots.size) || SystemClock.uptimeMillis() > deadline) return found
            SystemClock.sleep(50)
        }
    }

    /** Every node of the app's windows with the nodes above it, outermost first. */
    private fun nodes(): Sequence<Pair<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>> {
        // Nodes come from a cache that lags behind Compose; a refresh reads the current text and state.
        fun walk(node: AccessibilityNodeInfo, path: List<AccessibilityNodeInfo>):
            Sequence<Pair<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>> = sequence {
            if (!node.refresh()) return@sequence
            yield(node to path)
            val below = path + node
            for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(walk(it, below)) }
        }
        val roots = instrumentation.uiAutomation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }.filter { it.packageName == app.packageName }
        return roots.asSequence().flatMap { walk(it, emptyList()) }
    }

    private fun crop(screen: Bitmap, bounds: Rect): Bitmap =
        Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())

    /** The top of the card down to the lowest action, as the owner sees it. */
    private fun saveTop(screen: Bitmap, bounds: List<Rect>, file: File) {
        val bottom = ((bounds.maxOfOrNull { it.bottom } ?: (screen.height / 4)) + 12).coerceAtMost(screen.height)
        val top = Bitmap.createBitmap(screen, 0, 0, screen.width, bottom)
        file.outputStream().use { check(top.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        top.recycle()
    }

    private fun slots(): Map<String, Set<String>> = linkedMapOf(
        "close" to setOf(UiText.get(R.string.apps_stop)),
        "uninstall" to setOf(UiText.get(R.string.apps_remove)),
        "hide" to setOf(UiText.get(R.string.apps_hide)),
        "pin" to setOf(R.string.apps_pin, R.string.apps_unpin, R.string.apps_desktop_pin, R.string.apps_desktop_unpin)
            .mapTo(LinkedHashSet(), UiText::get),
        "move" to setOf(UiText.get(R.string.apps_move)),
        "display" to setOf(UiText.get(R.string.choose_display)),
        "open" to setOf(UiText.get(R.string.open_app)),
    )

    private fun cards(): List<Card> {
        val running = AppSettingsViewModel.State(app(LABEL, launchable = true), AppSettingsViewModel.Window(COMPONENT),
            AppSettingsViewModel.Permissions(details(null)),
            AppSettingsViewModel.Stop(available = true), AppSettingsViewModel.Remove(available = true))
        val grid = AppShortcutGridState(pinned = false, hidden = false, isFavorites = false, working = false, notice = null)
        return listOf(
            Card("running", running, grid),
            Card("not-running", running.copy(stop = AppSettingsViewModel.Stop()), grid),
            Card("closing", running.copy(stop = AppSettingsViewModel.Stop(available = true, busy = true)), grid),
            Card("closed", running.copy(stop = AppSettingsViewModel.Stop(result = AppStop.Stopped)), grid),
            Card("firmware-app", running.copy(permissions = AppSettingsViewModel.Permissions(details(AppControlRefusal.PREINSTALLED)),
                stop = AppSettingsViewModel.Stop(), remove = AppSettingsViewModel.Remove()), grid),
            Card("reading", running.copy(permissions = AppSettingsViewModel.Permissions(busy = true)), grid),
            Card("pinned", running, grid.copy(pinned = true)),
            Card("hidden", running, grid.copy(hidden = true)),
            Card("favorites", running, grid.copy(isFavorites = true)),
            Card("favorites-pinned", running, grid.copy(isFavorites = true, pinned = true)),
            Card("grid-loading", running, grid.copy(pinned = null, hidden = null)),
            Card("grid-saving", running, grid.copy(working = true)),
            Card("off-grid", running, grid, movable = false),
            Card("no-grid", running, null, movable = false),
            Card("no-launcher", running.copy(app = app(LABEL, launchable = false)), null, movable = false),
            Card("long-name", running.copy(app = app(LONG_LABEL, launchable = true)), grid),
            Card("notice", running.copy(notice = R.string.apps_remove_refused), grid),
        )
    }

    private fun app(label: String, launchable: Boolean) = LocalApp(COMPONENT, PACKAGE, label,
        if (launchable) InstalledApps.Entry(ComponentName(PACKAGE, "$PACKAGE.Main"), label) else null, null, InstallOrigin.ADB)

    private fun details(refusal: AppControlRefusal?) = AppPermissions(IDENTITY,
        if (refusal == null) InstallOrigin.ADB else InstallOrigin.SYSTEM, refusal, emptyList(), emptyList(),
        hasUnavailableDeclarations = false)

    private object NoPermissions : AppPermissionControl {
        override fun read(packageName: String): AppPermissions? = null
        override fun set(identity: InstalledAppIdentity, name: String, granted: Boolean) = PermissionChange.UNCONFIRMED
        override fun setSpecialAccess(identity: InstalledAppIdentity, access: AndroidAppAccess, allowed: Boolean) =
            PermissionChange.UNCONFIRMED
    }

    private object NoStop : AppStopControl {
        override fun canStop(packageName: String) = false
        override fun stop(identity: InstalledAppIdentity): AppStop = AppStop.Refused
    }

    private companion object {
        const val PACKAGE = "org.openvoyah.fixture.cardactions"
        const val COMPONENT = "$PACKAGE/$PACKAGE.Main"
        const val LABEL = "Fixture Navigator"
        const val LONG_LABEL = "Fixture Navigator with a name far too long to fit beside the actions of its card"
        const val SETTLE_MS = 450L
        val IDENTITY = InstalledAppIdentity(PACKAGE, 10_322, 1L, 2L, 3L, setOf("0".repeat(64)))
    }
}
