package org.openvoyah.controls

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.test.InstrumentationTestCase
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.core.Action
import org.openvoyah.controls.core.display.DisplayEventBus
import org.openvoyah.controls.core.display.PlaybackProgress
import org.openvoyah.controls.core.voice.VoiceAudioOptions
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.data.apps.DesktopInfoPreferences
import org.openvoyah.controls.data.apps.DesktopLayoutsPreferences
import org.openvoyah.controls.data.apps.DesktopWallpaperPreferences
import org.openvoyah.controls.data.display.AppWindowPreferences
import org.openvoyah.controls.data.display.DisplayPreviewPreferences
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.data.settings.LocalSettingsArchive
import org.openvoyah.controls.data.settings.SeatMemoryPreferences
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.data.vehicle.HeadlightMonitoringPreferences
import org.openvoyah.controls.data.vehicle.LocalHeadlightHistory
import org.openvoyah.controls.data.vehicle.ServiceReminderPreferences
import org.openvoyah.controls.domain.apps.AppGridPins
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.platform.apps.InstallOrigin
import org.openvoyah.controls.platform.apps.InstalledAppPermissions
import org.openvoyah.controls.domain.display.AppWindowProfile
import org.openvoyah.controls.domain.display.DisplayPreviewArrangement
import org.openvoyah.controls.domain.display.DisplayPreviewContent
import org.openvoyah.controls.domain.display.DisplayPreviewDistanceUnits
import org.openvoyah.controls.domain.display.DisplayPreviewPalette
import org.openvoyah.controls.domain.display.DisplayPreviewPaneBalance
import org.openvoyah.controls.domain.display.DisplayPreviewPaneOrder
import org.openvoyah.controls.domain.display.DisplayPreviewRoadNames
import org.openvoyah.controls.domain.display.PreviewSeekStep
import org.openvoyah.controls.domain.seatmemory.SeatMemorySlot
import org.openvoyah.controls.domain.vehicle.EarlierHeadlightReport
import org.openvoyah.controls.domain.vehicle.ExteriorLamp
import org.openvoyah.controls.domain.vehicle.HeadlightFeedback
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.HeadlightMonitor
import org.openvoyah.controls.domain.vehicle.HeadlightObservation
import org.openvoyah.controls.domain.vehicle.HeadlightSource
import org.openvoyah.controls.domain.vehicle.LampActivity
import org.openvoyah.controls.domain.vehicle.LampObservation
import org.openvoyah.controls.domain.voice.VoiceOutcome
import org.openvoyah.controls.domain.voice.VoiceResult
import org.openvoyah.controls.platform.ui.ActionExecutor
import org.openvoyah.controls.platform.voice.VoiceUiActions
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsNavigationViewModel
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.DayCockpitColors
import org.openvoyah.controls.presentation.vehicle.VEHICLE_SETTINGS_MODEL_KEY
import org.openvoyah.controls.presentation.vehicle.VehicleSettingsViewModel
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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

    fun testNormalRoutesAndProtectedNativeActivitiesRenderCompose() {
        val routes = listOf(
            ControlsRoute.APPS to "home", ControlsRoute.APPS to "apps",
            ControlsRoute.VOICE to "voice", ControlsRoute.FIRMWARE to "firmware",
            ControlsRoute.VEHICLE to "vehicle", ControlsRoute.CLUSTER to "cluster",
            ControlsRoute.DISPLAY to "display", ControlsRoute.UPDATES to "updates",
        )
        for ((route, name) in routes) {
            val activity = launch(ControlsNavigation.intent(app, route)) as MainActivity
            assertEquals("$route did not resolve to the shared host", MainActivity::class.java, activity.javaClass)
            awaitRoute(activity, route)
            assertComposeContent(activity)
            if (route == ControlsRoute.APPS) assertAppGrid(activity)
            if (route == ControlsRoute.VOICE) awaitText(UiText.get(R.string.ui_185), 10_000)
            if (route == ControlsRoute.DISPLAY) {
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
        assertEquals("The ordinary app must not hold the privileged external-entry permission",
            PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS))
        val voiceAction = Action(Action.Kind.START_VOICE, "", UiText.get(R.string.quick_voice_title))
        assertTrue("The same UID must retain its protected voice action", ActionExecutor(app).canExecute(voiceAction))
        val voice = launch(QuickVoiceActivity.intent(app))
        assertTrue("The protected same-UID entry did not open its real activity", voice is QuickVoiceActivity)
        assertComposeContent(voice)
        capture("quick-voice")
    }

    fun testAppGridOpensControlsOnceAndKeepsAndroidSettingsInsideIt() {
        val activity = launch(MainActivity.appsIntent(app)) as MainActivity
        assertAppGrid(activity)
        val appName = UiText.get(R.string.app_name)
        assertEquals(1, nodes().count { it.isVisibleToUser && it.text?.toString() == appName })
        assertNull(textNode(UiText.get(R.string.ui_111)))
        val systemSettings = UiText.get(R.string.ui_61)
        assertNull(textNode(systemSettings))
        assertNull(textNode("Settings"))
        capture("apps-controls-entry")
        clickText(appName)
        awaitRoute(activity, ControlsRoute.SETTINGS)
        awaitText(UiText.get(R.string.cockpit_back))
        scrollTo(systemSettings)
        capture("settings")
        clickText(systemSettings)
        val systemSettingsPackage = checkNotNull(Intent(Settings.ACTION_SETTINGS).resolveActivity(app.packageManager)).packageName
        val automation = instrumentation.uiAutomation
        val settingsDeadline = SystemClock.uptimeMillis() + 5_000
        while (automation.rootInActiveWindow?.packageName != systemSettingsPackage && SystemClock.uptimeMillis() < settingsDeadline) {
            SystemClock.sleep(50)
        }
        assertEquals(systemSettingsPackage, automation.rootInActiveWindow?.packageName?.toString())
        backFromExternalApp()
        awaitText(UiText.get(R.string.cockpit_back))
        clickText(UiText.get(R.string.cockpit_back))
        assertAppGrid(activity)
    }

    fun testAppGridSettingsAndSubmenusKeepOneHostAfterRefreshAndDialogs() {
        val activity = launch(MainActivity.appsIntent(app)) as MainActivity
        assertAppGrid(activity)
        clickText(UiText.get(R.string.app_name))
        awaitRoute(activity, ControlsRoute.SETTINGS)
        awaitText(UiText.get(R.string.cockpit_back))
        capture("settings")
        scrollTo(UiText.get(R.string.cockpit_buttons))
        clickText(UiText.get(R.string.cockpit_buttons))
        awaitRoute(activity, ControlsRoute.BUTTONS)
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
        clickText(UiText.get(R.string.cockpit_back))
        awaitRoute(activity, ControlsRoute.SETTINGS)
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
        val recreated = checkNotNull(active) as MainActivity
        awaitRoute(recreated, ControlsRoute.SETTINGS)
        assertComposeContent(recreated)

        scrollTo(UiText.get(R.string.voice_options_title), towardsStart = true)
        clickText(UiText.get(R.string.voice_options_title))
        awaitRoute(recreated, ControlsRoute.VOICE)
        awaitText(UiText.get(R.string.ui_185), 10_000)
        awaitText(UiText.get(R.string.cockpit_back))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitRoute(recreated, ControlsRoute.SETTINGS)

        // Exercise Android's real CLEAR_TOP/SINGLE_TOP delivery to the existing normal host.
        instrumentation.runOnMainSync { recreated.startActivity(MainActivity.appsIntent(recreated)) }
        assertAppGrid(recreated)
        capture("apps-return")
    }

    fun testVoiceAudioOptionsPersistAcrossRecreation() {
        val preferences = app.getSharedPreferences("voice", 0)
        val original = preferences.all
        val settings = VoiceSettings(app)
        val audio = UiText.get(R.string.voice_audio_settings)
        val expected = VoiceAudioOptions(75, 60, true, 45, VoiceAudioOptions.FeedbackStyle.OUTCOME, VoiceAudioOptions.EndCue.SOFT)
        val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
        val sliderValues = listOf(R.string.voice_speech_rate to 75f, R.string.voice_speech_volume to 60f,
            R.string.voice_ready_cue_volume to 45f)

        fun openAudio() {
            scrollTo(audio)
            clickText(audio)
        }
        fun visibleSlider(resource: Int): AccessibilityNodeInfo {
            val label = UiText.get(resource)
            scrollTo(label)
            val deadline = SystemClock.uptimeMillis() + 5_000
            var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            while (SystemClock.uptimeMillis() < deadline) {
                val node = nodes().firstOrNull { it.contentDescription?.toString() == label &&
                    it.actionList.any { entry -> entry.id == action } }
                if (node?.isVisibleToUser == true && node.isEnabled) return node
                val show = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
                if (node?.actionList?.any { it.id == show } == true && node.performAction(show)) {
                    settledTree(deadline)
                    continue
                }
                val viewport = dominantScrollTarget()?.node
                if (viewport?.performAction(direction) != true) {
                    direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    } else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                settledTree(deadline)
            }
            failWithEvidence("voice-audio-slider", "Could not bring the $label slider into view")
        }
        fun setProgress(resource: Int, target: Float) {
            assertTrue(visibleSlider(resource).performAction(action, Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, target)
            }))
            instrumentation.waitForIdleSync()
            assertEquals(target, checkNotNull(visibleSlider(resource).rangeInfo).current, 0.01f)
        }
        fun awaitVoiceChoice(label: String, checked: Boolean) {
            scrollTo(label)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                val chip = generateSequence(textNode(label)) { it.parent }.take(16).firstOrNull {
                    it.refresh() && it.isVisibleToUser && it.isEnabled && it.isCheckable
                }
                if (chip != null && chip.isChecked == checked) return
                SystemClock.sleep(50)
            }
            failWithEvidence("voice-choice-state", "Expected '$label' to report checked=$checked")
        }
        try {
            settings.language("en")
            settings.audio(false, false, VoiceAudioOptions.DEFAULTS, "en", "saved-voice-fixture")
            val activity = launch(ControlsNavigation.intent(app, ControlsRoute.VOICE)) as MainActivity
            awaitRoute(activity, ControlsRoute.VOICE)
            openAudio()
            clickText(UiText.get(R.string.voice_spoken_feedback))
            setProgress(R.string.voice_speech_rate, 75f)
            setProgress(R.string.voice_speech_volume, 60f)
            val ready = UiText.get(R.string.voice_ready_cue)
            scrollTo(ready)
            clickText(ready)
            setProgress(R.string.voice_ready_cue_volume, 45f)
            val outcome = UiText.get(R.string.voice_feedback_outcome)
            scrollTo(outcome)
            clickText(outcome)
            val soft = UiText.get(R.string.voice_end_cue_soft)
            scrollTo(soft)
            clickText(soft)
            val save = UiText.get(R.string.ui_104)
            scrollTo(save)
            clickText(save)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (settings.audioOptions() != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertEquals(expected, VoiceSettings(app).audioOptions())
            assertEquals("saved-voice-fixture", settings.offlineVoice("en"))

            val recreation = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { activity.recreate() }
            active = checkNotNull(instrumentation.waitForMonitorWithTimeout(recreation, 5_000))
            instrumentation.removeMonitor(recreation)
            instrumentation.waitForIdleSync()
            awaitRoute(checkNotNull(active) as MainActivity, ControlsRoute.VOICE)
            openAudio()
            for ((resource, value) in sliderValues) {
                assertEquals(value, checkNotNull(visibleSlider(resource).rangeInfo).current, 0.01f)
            }
            awaitVoiceChoice(outcome, checked = true)
            awaitVoiceChoice(UiText.get(R.string.voice_feedback_readback), checked = false)
            awaitVoiceChoice(soft, checked = true)
            awaitVoiceChoice(UiText.get(R.string.voice_end_cue_off), checked = false)
            awaitVoiceChoice(UiText.get(R.string.voice_end_cue_standard), checked = false)
            capture("voice-audio-options")
            val voice = UiText.get(R.string.voice_offline_voice)
            scrollTo(voice)
            clickText(voice)
            awaitText(UiText.get(R.string.voice_offline_automatic))
            val fallback = setOf(UiText.get(R.string.voice_offline_unavailable), UiText.get(R.string.voice_offline_saved_unavailable))
            val fallbackDeadline = SystemClock.uptimeMillis() + 5_000
            while (fallback.none { textNode(it) != null } && SystemClock.uptimeMillis() < fallbackDeadline) SystemClock.sleep(60)
            if (fallback.none { textNode(it) != null }) failWithEvidence("voice-offline-fallback", "Offline voice availability is unclear")
            capture("voice-offline-fallback")
        } finally {
            instrumentation.runOnMainSync { active?.finish() }
            assertTrue(preferences.edit().clear().apply {
                for ((key, value) in original) if (value is String) putString(key, value)
            }.commit())
        }
    }

    fun testWindowLayoutSavesAndRestoresLocally() {
        if (Build.VERSION.SDK_INT !in setOf(28, 30)) return
        check(!FridaBridge.wasConfigured(app))
        val entry = InstalledApps.list(app).first { AppWindowProfile.eligible(it.component().flattenToString()) }
        val component = entry.component().flattenToString()
        val preferences = AppWindowPreferences(app)
        val snapshot = preferences.exportProfiles()
        try {
            preferences.reset(component)
            launch(MainActivity.appsIntent(app))
            scrollTo(entry.label())
            clickText(entry.label(), longPress = true)
            awaitText(UiText.get(R.string.app_window_panel_auto))
            capture("app-window-settings")

            val margin = UiText.get(R.string.app_window_panel_width)
            val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
            val slider = nodes().first { it.contentDescription?.toString() == margin &&
                it.isVisibleToUser && it.actionList.any { option -> option.id == action } }
            assertTrue(slider.performAction(action, Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 3f)
            }))
            awaitText(UiText.get(R.string.app_window_pixels).format(60))
            val save = UiText.get(R.string.ui_104)
            scrollTo(save)
            clickText(save)
            val saved = UiText.get(R.string.app_window_saved)
            scrollTo(saved)
            assertEquals(60, preferences.load(component).panelWidthPx)
            capture("app-window-saved")

            val reset = UiText.get(R.string.app_window_restore)
            scrollTo(reset)
            clickText(reset)
            scrollTo(UiText.get(R.string.app_window_panel_auto))
            assertEquals(0, preferences.load(component).panelWidthPx)
        } finally {
            preferences.restoreProfiles(snapshot)
        }
    }

    fun testAppGridPinSurvivesReopeningAndCanBeRemoved() {
        val preferences = AppGridPreferences(app)
        val original = preferences.exportSettings()
        val candidates = InstalledApps.list(app).filter { AppGridPins.supports(it.component().flattenToString()) }
            .distinctBy { it.label() }
        val normal = candidates.first()
        val target = candidates[1]
        val component = target.component().flattenToString()
        val pin = UiText.get(R.string.apps_pin)
        val unpin = UiText.get(R.string.apps_unpin)

        fun assertOrder(leading: String, trailing: String) {
            awaitText(leading)
            awaitText(trailing)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                val first = textNode(leading)
                val second = textNode(trailing)
                if (first != null && second != null) {
                    val a = Rect().also(first::getBoundsInScreen)
                    val b = Rect().also(second::getBoundsInScreen)
                    val sameRow = a.top < b.bottom && b.top < a.bottom
                    if (if (sameRow) a.left < b.left else a.top < b.top) return
                }
                SystemClock.sleep(50)
            }
            failWithEvidence("app-grid-order", "Expected $leading to precede $trailing in the app grid")
        }

        try {
            preferences.restoreSettings(JSONObject().put("pinnedApps", JSONArray())
                .put("tileSize", "standard").put("showSystemApps", true))
            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            assertAppGrid(activity)
            assertOrder(normal.label(), target.label())
            clickText(target.label(), longPress = true)
            clickText(pin)
            awaitText(unpin)
            assertEquals(component, preferences.exportSettings().getJSONArray("pinnedApps").getString(0))
            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(activity)
            assertOrder(target.label(), normal.label())
            capture("app-grid-pinned")

            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            val reopened = launch(MainActivity.appsIntent(app)) as MainActivity
            assertAppGrid(reopened)
            assertOrder(target.label(), normal.label())
            clickText(target.label(), longPress = true)
            clickText(unpin)
            awaitText(pin)
            assertEquals(0, preferences.exportSettings().getJSONArray("pinnedApps").length())
            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(reopened)
            assertOrder(normal.label(), target.label())
        } finally {
            preferences.restoreSettings(original)
        }
    }

    fun testManualAppOrderFromGridSettings() {
        val preferences = AppGridPreferences(app)
        val original = preferences.exportSettings()
        val language = UiText.language()
        try {
            UiText.language("en")
            val settings = UiText.get(R.string.app_name)
            val order = UiText.get(R.string.apps_grid_sort_order)
            val edit = UiText.get(R.string.apps_grid_edit_manual_order)
            val manual = UiText.get(R.string.apps_grid_sort_manual)
            val nameOrder = UiText.get(R.string.apps_grid_sort_name)
            val catalog = InstalledApps.list(app).filter { AppGridPins.supports(it.component().flattenToString()) }
                .distinctBy { it.component() }
            val duplicateLabels = catalog.groupingBy { it.label() }.eachCount()
            val pair = catalog.zipWithNext().firstOrNull { (before, after) ->
                duplicateLabels[before.label()] == 1 && duplicateLabels[after.label()] == 1 &&
                    before.label() != settings && after.label() != settings
            } ?: failWithEvidence("manual-order-fixture", "The fixture needs two adjacent launcher apps with distinct labels")
            val first = pair.first.label()
            val second = pair.second.label()
            val savedPosition = catalog.indexOf(pair.first)
            val expected = catalog.take(savedPosition).map { it.component().flattenToString() } +
                listOf(pair.second.component().flattenToString(), pair.first.component().flattenToString())
            val expectedOrder = JSONArray(expected).toString()

            fun awaitSavedOrder() {
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (SystemClock.uptimeMillis() < deadline) {
                    if (preferences.exportSettings().getJSONArray("manualAppOrder").toString() == expectedOrder) return
                    SystemClock.sleep(50)
                }
                failWithEvidence("manual-order-save", "The saved app order did not match the requested swap")
            }

            fun awaitOrder(leading: String, trailing: String) {
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (SystemClock.uptimeMillis() < deadline) {
                    val a = textNode(leading)?.let { Rect().also(it::getBoundsInScreen) }
                    val b = textNode(trailing)?.let { Rect().also(it::getBoundsInScreen) }
                    if (a != null && b != null) {
                        val sameRow = a.top < b.bottom && b.top < a.bottom
                        if (if (sameRow) a.left < b.left else a.top < b.top) return
                    }
                    SystemClock.sleep(60)
                }
                failWithEvidence("manual-app-order", "Expected $leading before $trailing")
            }

            fun chooseOrder(label: String, stored: String) {
                scrollTo(order)
                clickText(order)
                clickText(label)
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (SystemClock.uptimeMillis() < deadline) {
                    if (preferences.exportSettings().getString("sortOrder") == stored) return
                    SystemClock.sleep(50)
                }
                failWithEvidence("manual-order-mode", "The app order was not saved as $stored")
            }

            val fixture = JSONObject(original.toString()).put("pinnedApps", JSONArray()).put("manualAppOrder", JSONArray())
                .put("sortOrder", "name").put("tileSize", "compact").put("columns", "auto")
                .put("showAppLabels", true).put("showSystemApps", true).put("visibleShortcuts", JSONArray())
            fixture.getJSONObject("desktops").put("enabled", false)
            preferences.restoreSettings(fixture)
            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            assertAppGrid(activity)
            clickText(settings)
            awaitRoute(activity, ControlsRoute.SETTINGS)
            chooseOrder(manual, "manual")
            scrollTo(edit)
            clickText(edit)
            awaitOrder(first, second)
            clickText(UiText.get(R.string.apps_grid_reorder_up_for).format(second), contentDescription = true)
            awaitSavedOrder()
            awaitOrder(second, first)
            capture("app-grid-manual-order")
            clickText(UiText.get(R.string.apps_desktop_done))
            chooseOrder(nameOrder, "name")
            assertEquals(expectedOrder, preferences.exportSettings().getJSONArray("manualAppOrder").toString())
            chooseOrder(manual, "manual")
            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(activity)
            awaitOrder(second, first)
        } finally {
            UiText.language(language)
            preferences.restoreSettings(original)
        }
    }

    fun testHiddenAppLeavesAllAppsSearchAndCanBeShownAgain() {
        val preferences = AppGridPreferences(app)
        val original = preferences.exportSettings()
        val catalog = InstalledApps.list(app).filter { AppGridPins.supports(it.component().flattenToString()) }
        val settings = UiText.get(R.string.app_name)
        val target = catalog.first { candidate ->
            candidate.label() != settings && catalog.count { it.label() == candidate.label() } == 1 &&
                catalog.count { it.component().packageName.contains(candidate.component().packageName) } == 1
        }
        val label = target.label()
        val packageName = target.component().packageName
        val component = target.component().flattenToString()
        val search = UiText.get(R.string.ui_113)
        val allApps = UiText.get(R.string.apps_desktop_all)
        val favorites = UiText.get(R.string.apps_desktop_favorites_one)
        val showInAll = UiText.get(R.string.apps_grid_show_in_all)
        val hiddenShortcuts = UiText.get(R.string.apps_grid_hidden_apps)
        try {
            val desktops = JSONObject().put("enabled", true).put("defaultDesktop", "main")
                .put("favorites1", JSONArray(listOf(component))).put("favorites2", JSONArray())
            preferences.restoreSettings(JSONObject().put("pinnedApps", JSONArray(listOf(component)))
                .put("showAppLabels", true).put("showSystemApps", true).put("desktops", desktops)
                .put("hiddenApps", JSONArray()))
            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            assertAppGrid(activity)
            awaitSelectedTab(allApps)
            scrollTo(label)
            clickText(label, longPress = true)
            scrollTo(showInAll)
            clickText(showInAll)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (preferences.exportSettings().getJSONArray("hiddenApps").length() == 0 &&
                SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertEquals(JSONArray(listOf(component)).toString(), preferences.exportSettings().getJSONArray("hiddenApps").toString())
            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(activity)
            scrollTo(search)
            clickText(search)
            searchGridFor(packageName)
            scrollTo(UiText.get(R.string.apps_empty))
            assertNull("All apps search should omit the hidden shortcut", textNode(label))
            clickText(favorites)
            awaitSelectedTab(favorites)
            scrollTo(label)
            clickText(allApps)
            awaitSelectedTab(allApps)
            scrollTo(settings)
            clickText(settings)
            awaitRoute(activity, ControlsRoute.SETTINGS)
            scrollTo(hiddenShortcuts)
            clickText(hiddenShortcuts)
            awaitText(label)
            capture("app-grid-hidden-apps")
            clickText(UiText.get(R.string.apps_grid_show_app).format(label), contentDescription = true)
            awaitText(UiText.get(R.string.apps_grid_hidden_empty))
            assertEquals(0, AppGridPreferences(app).exportSettings().getJSONArray("hiddenApps").length())
            assertEquals(component, preferences.exportSettings().getJSONArray("pinnedApps").getString(0))
            clickText(UiText.get(R.string.apps_desktop_done))
            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(activity)
            scrollTo(label)
        } finally {
            preferences.restoreSettings(original)
        }
    }

    fun testAppGridColumnsShortcutsAndPinOrder() {
        val preferences = AppGridPreferences(app)
        val original = preferences.exportSettings()
        val candidates = InstalledApps.list(app).filter { AppGridPins.supports(it.component().flattenToString()) }
            .distinctBy { it.label() }.take(2)
        assertEquals("The fixture needs two launcher apps", 2, candidates.size)
        val components = candidates.map { it.component().flattenToString() }
        val settings = UiText.get(R.string.app_name)
        val columns = UiText.get(R.string.apps_grid_columns)
        val shortcuts = UiText.get(R.string.apps_grid_shortcuts)
        val display = UiText.get(R.string.virtual_display)
        val reorder = UiText.get(R.string.apps_grid_reorder_pins)
        val search = UiText.get(R.string.ui_113)
        val done = UiText.get(R.string.apps_desktop_done)

        fun settingsTileWidth(): Int {
            awaitText(settings)
            var tile = checkNotNull(textNode(settings))
            while (!tile.isClickable) tile = checkNotNull(tile.parent)
            return Rect().also(tile::getBoundsInScreen).width()
        }

        fun awaitSavedSetting(expected: (JSONObject) -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (expected(preferences.exportSettings())) return
                SystemClock.sleep(50)
            }
            failWithEvidence("app-grid-launcher-options", "The app grid setting was not saved")
        }

        try {
            val fixture = JSONObject(original.toString()).put("pinnedApps", JSONArray(components)).put("tileSize", "standard")
                .put("columns", "auto").put("showAppLabels", true).put("showSystemApps", true)
                .put("visibleShortcuts", JSONArray(listOf("display")))
            fixture.getJSONObject("desktops").put("enabled", false)
            preferences.restoreSettings(fixture)
            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            assertAppGrid(activity)
            val adaptiveWidth = settingsTileWidth()

            clickText(settings)
            awaitRoute(activity, ControlsRoute.SETTINGS)
            scrollTo(columns)
            clickText(columns)
            clickText("4")
            awaitSavedSetting { it.getString("columns") == "4" }
            scrollTo(shortcuts)
            clickText(shortcuts)
            clickText(display)
            awaitSavedSetting { it.getJSONArray("visibleShortcuts").length() == 0 }
            clickText(done)
            clickText(UiText.get(R.string.cockpit_back))

            assertAppGrid(activity)
            if (activity.resources.configuration.screenWidthDp >= 1150) {
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (settingsTileWidth() <= adaptiveWidth && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                assertTrue("The fixed four-column layout should widen a tile on the wide fixture", settingsTileWidth() > adaptiveWidth)
            }
            assertNull("A disabled shortcut remains visible", textNode(display))
            clickText(reorder)
            clickText(UiText.get(R.string.apps_grid_reorder_up_for).format(candidates[1].label()), contentDescription = true)
            awaitSavedSetting { it.getJSONArray("pinnedApps").getString(0) == components[1] }
            clickText(done)
            scrollTo(search)
            clickText(search)
            val deadline = SystemClock.uptimeMillis() + 5_000
            fun searching() = nodes().any { it.isEditable && it.isVisibleToUser }
            while (!searching() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertTrue("Search stays available with optional shortcuts hidden", searching())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            scrollTo(settings)
            capture("app-grid-launcher-options")
        } finally {
            preferences.restoreSettings(original)
        }
    }

    fun testAppGridSettingsPersistAndKeepPinnedSystemAppsReachable() {
        val preferences = AppGridPreferences(app)
        val original = preferences.exportSettings()
        val permissions = InstalledAppPermissions(app)
        val candidates = InstalledApps.list(app).filter {
            AppGridPins.supports(it.component().flattenToString()) && permissions.origin(it.component().packageName) == InstallOrigin.SYSTEM
        }.distinctBy { it.component().packageName }.distinctBy { it.label() }
        val pinned = candidates.first()
        val unpinned = candidates[1]
        val settings = UiText.get(R.string.app_name)
        val tileSize = UiText.get(R.string.apps_grid_tile_size)
        val compact = UiText.get(R.string.apps_grid_size_compact)
        val showSystem = UiText.get(R.string.apps_grid_show_system)
        val search = UiText.get(R.string.ui_113)

        fun settingsTileBounds(): Rect {
            awaitText(settings)
            var tile = checkNotNull(textNode(settings))
            while (!tile.isClickable) tile = checkNotNull(tile.parent)
            return Rect().also(tile::getBoundsInScreen)
        }

        try {
            preferences.restoreSettings(JSONObject().put("pinnedApps", JSONArray().put(pinned.component().flattenToString()))
                .put("tileSize", "standard").put("showSystemApps", true))
            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            assertAppGrid(activity)
            awaitText(unpinned.label())
            val standardBounds = settingsTileBounds()

            clickText(settings)
            awaitRoute(activity, ControlsRoute.SETTINGS)
            scrollTo(tileSize)
            clickText(tileSize)
            clickText(compact)
            scrollTo(showSystem)
            clickText(showSystem)
            awaitText(UiText.get(R.string.apps_grid_keeps_pins))
            val saved = AppGridPreferences(app).exportSettings()
            assertEquals("compact", saved.getString("tileSize"))
            assertFalse(saved.getBoolean("showSystemApps"))
            scrollTo(UiText.get(R.string.apps_grid_settings))
            capture("app-grid-settings")

            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(activity)
            awaitText(pinned.label())
            val compactBounds = settingsTileBounds()
            assertTrue("Compact mode should shrink the settings tile", compactBounds.height() < standardBounds.height())
            assertNull("An unpinned system app remained in the grid", textNode(unpinned.label()))
            capture("app-grid-compact")

            scrollTo(search)
            clickText(search)
            searchGridFor(unpinned.component().packageName)
            scrollTo(UiText.get(R.string.apps_empty))
            assertNull("Search should follow system visibility", textNode(unpinned.label()))
            searchGridFor(pinned.component().packageName)
            scrollTo(pinned.label())
            awaitText(pinned.label())
        } finally {
            preferences.restoreSettings(original)
        }
    }

    private fun searchGridFor(query: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val field = nodes().firstOrNull { it.isVisibleToUser && it.isEditable && it.refresh() }
            if (field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
                }) == true) return
            SystemClock.sleep(50)
        }
        failWithEvidence("app-grid-filter", "Search would not accept the package filter")
    }

    fun testFavoritesDesktopsKeepSeparatePinsAndOpenSavedDefault() {
        val preferences = AppGridPreferences(app)
        val original = preferences.exportSettings()
        val target = InstalledApps.list(app).first {
            it.component().packageName != app.packageName && AppGridPins.supports(it.component().flattenToString())
        }
        val settings = UiText.get(R.string.app_name)
        val desktopEnable = UiText.get(R.string.apps_desktop_enable)
        val desktopDefault = UiText.get(R.string.apps_desktop_default)
        val first = UiText.get(R.string.apps_desktop_favorites_one)
        val second = UiText.get(R.string.apps_desktop_favorites_two)
        val find = UiText.get(R.string.apps_desktop_find)
        val empty = UiText.get(R.string.apps_desktop_empty)
        try {
            val desktops = JSONObject().put("enabled", false).put("defaultDesktop", "main")
                .put("favorites1", JSONArray()).put("favorites2", JSONArray())
            preferences.restoreSettings(JSONObject().put("pinnedApps", JSONArray()).put("tileSize", "standard")
                .put("showSystemApps", true).put("desktops", desktops)
                .put("desktopNames", JSONObject().put("favorites1", "").put("favorites2", "")))
            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            clickText(settings)
            awaitRoute(activity, ControlsRoute.SETTINGS)
            scrollTo(desktopEnable)
            clickText(desktopEnable)
            scrollTo(desktopDefault)
            clickText(desktopDefault)
            clickText(second)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (preferences.exportSettings().getJSONObject("desktops").getString("defaultDesktop") != "favorites2" &&
                SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertEquals("favorites2", preferences.exportSettings().getJSONObject("desktops").getString("defaultDesktop"))
            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(activity)

            clickText(first)
            awaitSelectedTab(first)
            clickText(find)
            scrollTo(target.label())
            clickText(target.label())
            val component = target.component().flattenToString()
            val saveDeadline = SystemClock.uptimeMillis() + 5_000
            fun firstPin(): String = preferences.exportSettings().getJSONObject("desktops")
                .getJSONArray("favorites1").optString(0)
            while (firstPin() != component && SystemClock.uptimeMillis() < saveDeadline) SystemClock.sleep(50)
            assertEquals(component, firstPin())
            val done = UiText.get(R.string.apps_desktop_done)
            awaitText(done)
            if (!settledTree(SystemClock.uptimeMillis() + 5_000).settled) {
                failWithEvidence("favorites-picker", "The picker layout did not settle after saving the pin")
            }
            tapCurrentText(done)
            val closeDeadline = SystemClock.uptimeMillis() + 5_000
            while (textNode(done) != null && SystemClock.uptimeMillis() < closeDeadline) SystemClock.sleep(70)
            if (textNode(done) != null) failWithEvidence("favorites-picker", "The Done button kept the picker open")
            awaitText(target.label())
            capture("app-desktops-favorites")

            clickText(second)
            awaitSelectedTab(second)
            scrollTo(empty)
            assertNull("A pin from Favorites 1 leaked into Favorites 2", textNode(target.label()))
            clickText(first)
            awaitText(target.label())
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            val reopened = launch(MainActivity.appsIntent(app)) as MainActivity
            assertAppGrid(reopened)
            awaitSelectedTab(second)
            awaitText(find)
            scrollTo(empty)
            assertNull("A new entry should use the empty saved default desktop", textNode(target.label()))
        } finally {
            preferences.restoreSettings(original)
        }
    }

    fun testFavoritesBackBehaviorPreservesLocalOverlaysAndSystemBack() {
        val preferences = AppGridPreferences(app)
        val original = preferences.exportSettings()
        val settings = UiText.get(R.string.app_name)
        val favorite = UiText.get(R.string.apps_desktop_favorites_one)
        val all = UiText.get(R.string.apps_desktop_all)
        val backOption = UiText.get(R.string.apps_grid_favorites_back)
        val pinned = InstalledApps.list(app).first { AppGridPins.supports(it.component().flattenToString()) }

        fun back() = instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        fun editor(): AccessibilityNodeInfo? = nodes().firstOrNull { it.isVisibleToUser && it.isEditable }
        fun keyboardVisible(): Boolean = instrumentation.uiAutomation.windows.any {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        fun await(description: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                SystemClock.sleep(60)
            }
            failWithEvidence("favorites-back", description)
        }
        fun awaitExit(activity: MainActivity) {
            await("Android Back must leave the root grid") {
                activity.isFinishing || activity.lifecycle.currentState < Lifecycle.State.RESUMED
            }
        }
        try {
            val desktops = JSONObject().put("enabled", true).put("defaultDesktop", "favorites1")
                .put("favorites1", JSONArray().put(pinned.component().flattenToString())).put("favorites2", JSONArray())
            preferences.restoreSettings(JSONObject().put("pinnedApps", JSONArray()).put("desktops", desktops)
                .put("desktopNames", JSONObject().put("favorites1", "").put("favorites2", ""))
                .put("showAppLabels", true).put("showSystemApps", true)
                .put("smoothDesktopPaging", false).put("favoritesBackBehavior", "system"))
            val ordinary = launch(MainActivity.appsIntent(app)) as MainActivity
            awaitSelectedTab(favorite)
            back()
            awaitExit(ordinary)
            instrumentation.runOnMainSync { ordinary.finish() }
            instrumentation.waitForIdleSync()

            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            awaitSelectedTab(favorite)
            clickText(settings)
            awaitRoute(activity, ControlsRoute.SETTINGS)
            scrollTo(UiText.get(R.string.apps_grid_settings))
            scrollTo(backOption)
            clickText(backOption)
            clickText(UiText.get(R.string.apps_grid_favorites_back_main))
            await("The favorites Back choice must be saved") {
                preferences.exportSettings().getString("favoritesBackBehavior") == "mainDesktop"
            }
            clickText(UiText.get(R.string.cockpit_back))
            awaitSelectedTab(favorite)

            clickText(pinned.label(), longPress = true)
            awaitText(UiText.get(R.string.app_settings_title))
            back()
            awaitSelectedTab(favorite)

            clickText(UiText.get(R.string.apps_desktop_find))
            val done = UiText.get(R.string.apps_desktop_done)
            awaitText(done)
            back()
            awaitSelectedTab(favorite)
            assertNull("Back must close the favorites dialog before switching desktops", textNode(done))

            val search = UiText.get(R.string.ui_113)
            scrollTo(search)
            clickText(search)
            await("Search must open on the favorites desktop") { editor() != null }
            editor()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val keyboardDeadline = SystemClock.uptimeMillis() + 2_000
            while (!keyboardVisible() && SystemClock.uptimeMillis() < keyboardDeadline) SystemClock.sleep(60)
            if (keyboardVisible()) {
                back()
                await("Back must dismiss the Android keyboard first") { !keyboardVisible() }
                await("Dismissing the keyboard must keep search open") { editor() != null }
                awaitSelectedTab(favorite)
            }
            back()
            await("Back must close the local search before switching desktops") { editor() == null }
            awaitSelectedTab(favorite)

            back()
            awaitSelectedTab(all)
            assertAppGrid(activity)
            assertEquals("favorites1", preferences.exportSettings().getJSONObject("desktops").getString("defaultDesktop"))
            capture("favorites-back-main")
            back()
            awaitExit(activity)
        } finally {
            preferences.restoreSettings(original)
        }
    }

    fun testFavoriteDesktopNamesAreAccessibleAndResettable() {
        val preferences = AppGridPreferences(app)
        val original = preferences.exportSettings()
        val settings = UiText.get(R.string.app_name)
        val names = UiText.get(R.string.apps_grid_desktop_names)
        val default = UiText.get(R.string.apps_desktop_default)
        val allApps = UiText.get(R.string.apps_desktop_all)
        val first = UiText.get(R.string.apps_desktop_favorites_one)
        val save = UiText.get(R.string.apps_grid_names_save)
        val name = "Поездки по городу \uD83D\uDE97"
        val namedTab = UiText.get(R.string.apps_grid_names_with_slot).format(name, first)
        fun awaitSetting(expected: String, read: (JSONObject) -> String) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (read(preferences.exportSettings()) == expected) return
                SystemClock.sleep(50)
            }
            failWithEvidence("desktop-name", "The launcher did not save the expected desktop setting: $expected")
        }
        fun firstName(grid: JSONObject) = grid.getJSONObject("desktopNames").getString("favorites1")
        try {
            val desktops = JSONObject().put("enabled", true).put("defaultDesktop", "main")
                .put("favorites1", JSONArray()).put("favorites2", JSONArray())
            preferences.restoreSettings(JSONObject().put("pinnedApps", JSONArray()).put("desktops", desktops)
                .put("desktopNames", JSONObject().put("favorites1", "").put("favorites2", "")))
            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            clickText(settings)
            awaitRoute(activity, ControlsRoute.SETTINGS)
            scrollTo(names)
            clickText(names)
            val fieldDeadline = SystemClock.uptimeMillis() + 5_000
            var edited = false
            while (!edited && SystemClock.uptimeMillis() < fieldDeadline) {
                val fields = nodes().filter { it.isVisibleToUser && it.isEditable && it.refresh() }
                    .sortedBy { node -> Rect().also(node::getBoundsInScreen).top }.toList()
                edited = fields.size == 2 && fields.first().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "  $name  ")
                })
                if (!edited) SystemClock.sleep(50)
            }
            assertTrue("The first desktop name must be editable", edited)
            clickText(save)
            awaitSetting(name, ::firstName)

            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(activity)
            awaitSelectedTab(allApps)
            clickText(namedTab, contentDescription = true)
            awaitSelectedTab(namedTab, contentDescription = true)
            capture("app-grid-desktop-names")

            clickText(settings)
            awaitRoute(activity, ControlsRoute.SETTINGS)
            scrollTo(default)
            clickText(default)
            clickText(namedTab)
            awaitSetting("favorites1") { it.getJSONObject("desktops").getString("defaultDesktop") }
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            val reopened = launch(MainActivity.appsIntent(app)) as MainActivity
            assertAppGrid(reopened)
            awaitSelectedTab(namedTab, contentDescription = true)

            clickText(settings)
            awaitRoute(reopened, ControlsRoute.SETTINGS)
            scrollTo(names)
            clickText(names)
            clickText(UiText.get(R.string.apps_grid_names_reset_for).format(first), contentDescription = true)
            clickText(save)
            awaitSetting("", ::firstName)
            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(reopened)
            awaitSelectedTab(first)
            assertEquals("favorites1", preferences.exportSettings().getJSONObject("desktops").getString("defaultDesktop"))
        } finally {
            preferences.restoreSettings(original)
        }
    }

    fun testSavedDesktopLayoutsRestoreGridAndCards() {
        val archive = LocalSettingsArchive(app)
        val previous = archive.prepare(archive.exportJson())
        val layouts = DesktopLayoutsPreferences(app)
        val grid = AppGridPreferences(app)
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        val previousLanguage = UiText.language()
        val name = "Long trips"
        val copy = "Weekend trips"
        val clockTag = "desktop-info:clock"

        fun clockVisible() = nodes().any { it.isVisibleToUser && it.viewIdResourceName == clockTag }
        fun awaitClock() {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!clockVisible() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(60)
            if (!clockVisible()) failWithEvidence("saved-layout-clock", "The saved clock card did not return to the grid")
        }
        fun awaitHiddenClockAndFourColumns() {
            val cards = DesktopInfoPreferences(app)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (grid.exportSettings().getString("columns") == "4" &&
                    !cards.exportSettings().getJSONObject("main").has("clock")) return
                SystemClock.sleep(50)
            }
            failWithEvidence("changed-desktop-layout", "The two desktop adjustments did not save before applying the layout")
        }
        fun enterName(value: String) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                val field = nodes().firstOrNull { it.isVisibleToUser && it.isEditable && it.refresh() }
                if (field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }) == true) return
                SystemClock.sleep(70)
            }
            failWithEvidence("desktop-layout-name", "The layout name field must be editable")
        }

        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            UiText.language("en")
            layouts.restoreSettings(JSONObject())
            archive.applyDesktopLayout(
                JSONObject().put("pinnedApps", JSONArray()).put("tileSize", "compact").put("columns", "5"),
                JSONObject(),
                JSONObject("""{"main":{"clock":"compact"}}"""),
            )
            val columns = UiText.get(R.string.apps_grid_columns)
            val cards = UiText.get(R.string.desktop_info_settings)
            val layoutSettings = UiText.get(R.string.desktop_layouts_settings)
            val done = UiText.get(R.string.apps_desktop_done)
            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            assertAppGrid(activity)
            awaitClock()
            clickText(UiText.get(R.string.app_name))
            awaitRoute(activity, ControlsRoute.SETTINGS)
            scrollTo(layoutSettings)
            clickText(layoutSettings)
            clickText(UiText.get(R.string.desktop_layouts_save_current))
            enterName(name)
            clickText(UiText.get(R.string.apps_grid_names_save))
            awaitText(UiText.get(R.string.desktop_layouts_saved))
            awaitText(name)
            clickText(done)

            scrollTo(columns, towardsStart = true)
            clickText(columns)
            clickText("4")
            scrollTo(cards)
            clickText(cards)
            val clock = UiText.get(R.string.desktop_info_clock)
            awaitText(clock)
            val dialogWindows = automation.windows.map { it.id }.toSet() + checkNotNull(textNode(clock)).windowId
            clickText(clock)
            clickText(UiText.get(R.string.desktop_info_hidden), openedAfter = dialogWindows)
            awaitHiddenClockAndFourColumns()
            clickText(done)
            val changed = archive.captureDesktopLayout().toString()

            scrollTo(layoutSettings)
            clickText(layoutSettings)
            clickText(name)
            clickText(UiText.get(R.string.desktop_layouts_duplicate))
            enterName(copy)
            clickText(UiText.get(R.string.apps_grid_names_save))
            awaitText(UiText.get(R.string.desktop_layouts_duplicated))
            awaitText(name)
            awaitText(copy)
            assertEquals(changed, archive.captureDesktopLayout().toString())
            capture("desktop-layouts-copy")

            clickText(copy)
            clickText(UiText.get(R.string.desktop_layouts_apply))
            awaitText(UiText.get(R.string.desktop_layouts_apply_confirm).format(copy))
            clickText(UiText.get(R.string.desktop_layouts_apply_confirm_action))
            awaitText(UiText.get(R.string.desktop_layouts_applied))
            awaitText(name)
            capture("desktop-layouts")
            assertEquals("5", grid.exportSettings().getString("columns"))
            clickText(done)
            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(activity)
            awaitClock()
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            UiText.language(previousLanguage)
            archive.apply(previous)
        }
    }

    fun testPhoneFavoritesCardDialsSelectedContact() {
        val grid = AppGridPreferences(app)
        val cards = DesktopInfoPreferences(app)
        val voice = VoiceSettings(app)
        val previousGrid = grid.exportSettings()
        val previousCards = cards.exportSettings()
        val previousVoice = voice.exportJson()
        val previousLanguage = UiText.language()
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        val nameOne = "alex"
        val nameTwo = "garage"
        val idOne = "test-contact-Alex"
        val idTwo = "00000000-0000-4000-8000-000000000002"
        val numberTwo = "+12025550121"
        val intents = LinkedBlockingQueue<Intent>()
        var dialMonitorRegistered = false
        val dialMonitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_DIAL && intent.action != Intent.ACTION_CALL) return null
                intents.offer(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }

        fun expectFavorites(vararg ids: String) {
            val expected = JSONArray(ids.toList()).toString()
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (cards.exportSettings().getJSONObject("main").getJSONArray("phoneFavorites").toString() == expected) return
                SystemClock.sleep(50)
            }
            failWithEvidence("phone-favorites-order", "The desktop did not save its selected contact order")
        }

        try {
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            UiText.language("en")
            val contactValues = JSONObject()
                .put("contact.$idOne", JSONObject().put("name", nameOne).put("phone", "+12025550103").toString())
                .put("contact.$idTwo", JSONObject().put("name", nameTwo).put("phone", numberTwo).toString())
            voice.importJson(JSONObject().put("schema", 1).put("values", contactValues).toString())
            grid.restoreSettings(JSONObject().put("pinnedApps", JSONArray()).put("columns", "4")
                .put("tileSize", "compact").put("desktopCardPlacement", "beforeApps")
                .put("desktops", JSONObject().put("enabled", false).put("defaultDesktop", "main")
                    .put("favorites1", JSONArray()).put("favorites2", JSONArray())))
            cards.restoreSettings(JSONObject().put("main", JSONObject().put("phoneFavorites", JSONArray())))

            val desktopCards = UiText.get(R.string.desktop_info_settings)
            val phoneCard = UiText.get(R.string.desktop_info_phone)
            val edit = UiText.get(R.string.desktop_info_phone_edit)
            val add = UiText.get(R.string.desktop_info_phone_add)
            val done = UiText.get(R.string.apps_desktop_done)
            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            assertAppGrid(activity)
            clickText(UiText.get(R.string.app_name))
            awaitRoute(activity, ControlsRoute.SETTINGS)
            scrollTo(desktopCards)
            clickText(desktopCards)
            scrollTo(phoneCard)
            clickText(phoneCard)
            clickText(UiText.get(R.string.desktop_info_wide))
            scrollTo(edit)
            clickText(edit)
            clickText(add)
            clickText(nameOne)
            expectFavorites(idOne)
            clickText(add)
            clickText(nameTwo)
            expectFavorites(idOne, idTwo)
            clickText(UiText.get(R.string.desktop_info_phone_up).format(nameTwo), contentDescription = true)
            expectFavorites(idTwo, idOne)
            awaitText(nameTwo)
            capture("desktop-phone-favorites-settings")
            clickText(done)
            awaitText(desktopCards)
            clickText(done)
            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(activity)
            awaitText(phoneCard)

            val dialOne = UiText.get(R.string.desktop_info_phone_dial).format(nameOne)
            val dialTwo = UiText.get(R.string.desktop_info_phone_dial).format(nameTwo)
            fun dialButton(description: String): AccessibilityNodeInfo? = nodes().firstOrNull {
                it.isVisibleToUser && it.contentDescription?.toString() == description &&
                    generateSequence(it.parent) { ancestor -> ancestor.parent }.any { ancestor ->
                        ancestor.viewIdResourceName == "desktop-info:phone"
                    }
            }
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline && (dialButton(dialOne) == null || dialButton(dialTwo) == null)) {
                SystemClock.sleep(50)
            }
            val first = dialButton(dialTwo) ?: failWithEvidence("phone-favorite", "First favorite is missing from the desktop card")
            val second = dialButton(dialOne) ?: failWithEvidence("phone-favorite", "Second favorite is missing from the desktop card")
            val firstBounds = Rect().also(first::getBoundsInScreen)
            val secondBounds = Rect().also(second::getBoundsInScreen)
            assertTrue("The desktop must show the saved order", firstBounds.top < secondBounds.top ||
                firstBounds.top == secondBounds.top && firstBounds.left < secondBounds.left)
            capture("desktop-phone-favorites")
            instrumentation.addMonitor(dialMonitor)
            dialMonitorRegistered = true
            clickText(dialTwo, contentDescription = true)
            val request = intents.poll(5, TimeUnit.SECONDS)
            assertEquals(Intent.ACTION_DIAL, request?.action)
            assertEquals("tel", request?.data?.scheme)
            assertEquals(numberTwo, request?.data?.schemeSpecificPart)
            assertNull("One tap should request one dialer window", intents.poll())

            contactValues.remove("contact.$idTwo")
            voice.importJson(JSONObject().put("schema", 1).put("values", contactValues).toString())
            val refreshDeadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < refreshDeadline && dialButton(dialTwo) != null) SystemClock.sleep(50)
            assertNull("A removed contact must no longer appear on the live card", dialButton(dialTwo))
            assertNotNull("The remaining favorite must stay available", dialButton(dialOne))
            expectFavorites(idTwo, idOne)
        } finally {
            if (dialMonitorRegistered) instrumentation.removeMonitor(dialMonitor)
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            UiText.language(previousLanguage)
            cards.restoreSettings(previousCards)
            grid.restoreSettings(previousGrid)
            voice.importJson(previousVoice)
        }
    }

    fun testDesktopInfoCardsApplyToSelectedDesktop() {
        val grid = AppGridPreferences(app)
        val cards = DesktopInfoPreferences(app)
        val service = ServiceReminderPreferences(app)
        val previousGrid = grid.exportSettings()
        val previousCards = cards.exportSettings()
        val previousService = service.exportSettings()
        val previousLanguage = UiText.language()
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        val clockTag = "desktop-info:clock"
        val serviceTag = "desktop-info:service"

        fun expectSaved(desktop: String, setting: String, value: String) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (cards.exportSettings().optJSONObject(desktop)?.optString(setting) == value) return
                SystemClock.sleep(50)
            }
            failWithEvidence("desktop-info-setting", "Desktop $desktop did not save $setting as $value")
        }

        fun renderedCard(tag: String): AccessibilityNodeInfo? = nodes().firstOrNull {
            it.isVisibleToUser && it.viewIdResourceName == tag
        }

        fun waitForCard(tag: String, minWidth: Int = 1): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                val target = renderedCard(tag)
                if (target != null && Rect().also(target::getBoundsInScreen).width() >= minWidth) return target
                SystemClock.sleep(50)
            }
            failWithEvidence("desktop-info-layout", "Card $tag never reached width $minWidth")
        }

        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            UiText.language("ru")
            val settings = UiText.get(R.string.app_name)
            val desktopCards = UiText.get(R.string.desktop_info_settings)
            val clock = UiText.get(R.string.desktop_info_clock)
            val maintenance = UiText.get(R.string.desktop_info_service)
            val compact = UiText.get(R.string.desktop_info_compact)
            val wide = UiText.get(R.string.desktop_info_wide)
            val all = UiText.get(R.string.apps_desktop_all)
            val favorite = "Поездки"
            val namedFavorite = UiText.get(R.string.apps_grid_names_with_slot)
                .format(favorite, UiText.get(R.string.apps_desktop_favorites_one))
            val dueSoon = UiText.get(R.string.service_reminder_remaining).format("250")
            val desktops = JSONObject().put("enabled", true).put("defaultDesktop", "main")
                .put("favorites1", JSONArray()).put("favorites2", JSONArray())
            grid.restoreSettings(JSONObject().put("pinnedApps", JSONArray())
                .put("desktops", desktops).put("tileSize", "compact").put("columns", "4")
                .put("desktopNames", JSONObject().put("favorites1", favorite).put("favorites2", "")))
            cards.restoreSettings(JSONObject())
            service.restoreSettings(JSONObject()
                .put("enabled", false).put("intervalKm", 10_000).put("currentKm", 69_750).put("lastServiceKm", 60_000)
                .put("calendarIntervalMonths", JSONObject.NULL).put("lastServiceDate", JSONObject.NULL))

            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            assertAppGrid(activity)
            assertNull(renderedCard(clockTag))
            assertNull(renderedCard(serviceTag))
            clickText(settings)
            awaitRoute(activity, ControlsRoute.SETTINGS)
            scrollTo(desktopCards)
            clickText(desktopCards)
            clickText(clock)
            clickText(compact)
            expectSaved("main", "clock", "compact")
            clickText(UiText.get(R.string.desktop_info_clock_format))
            clickText(UiText.get(R.string.desktop_info_clock_format_24h))
            expectSaved("main", "clockFormat", "24Hour")
            clickText(maintenance)
            clickText(wide)
            expectSaved("main", "service", "wide")

            val mediaStyle = UiText.get(R.string.desktop_info_media_style)
            scrollTo(mediaStyle)
            clickText(mediaStyle)
            clickText(UiText.get(R.string.desktop_info_media_style_cover))
            expectSaved("main", "mediaStyle", "cover")
            assertFalse(cards.exportSettings().getJSONObject("main").has("media"))

            val desktop = UiText.get(R.string.desktop_info_desktop)
            scrollTo(desktop, towardsStart = true)
            clickText(desktop)
            clickText(namedFavorite)
            clickText(clock)
            clickText(wide)
            expectSaved("favorites1", "clock", "wide")
            expectSaved("favorites1", "clockFormat", "system")
            clickText(UiText.get(R.string.apps_desktop_done))
            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(activity)
            awaitSelectedTab(all)

            val mainClockBounds = Rect().also(waitForCard(clockTag)::getBoundsInScreen)
            val wideMinWidth = mainClockBounds.width() * 2 - 2
            val serviceBounds = Rect().also(waitForCard(serviceTag, wideMinWidth)::getBoundsInScreen)
            assertTrue("The compact clock and wide maintenance card must use distinct grid columns",
                serviceBounds.left >= mainClockBounds.right && serviceBounds.top == mainClockBounds.top)
            awaitText(dueSoon)
            capture("desktop-info-cards")

            clickText(namedFavorite, contentDescription = true)
            awaitText(UiText.get(R.string.apps_desktop_find))
            waitForCard(clockTag, wideMinWidth)
            assertNull("Maintenance should only appear on the desktop where it is enabled", renderedCard(serviceTag))

            clickText(all)
            awaitSelectedTab(all)
            val maintenanceCard = waitForCard(serviceTag, wideMinWidth)
            val restoredClockBounds = Rect().also(waitForCard(clockTag)::getBoundsInScreen)
            assertEquals(mainClockBounds.width(), restoredClockBounds.width())
            assertTrue("The maintenance card must open the service reminder",
                maintenanceCard.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitRoute(activity, ControlsRoute.SERVICE_REMINDER)
            awaitText(UiText.get(R.string.service_reminder_title))
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
            UiText.language(previousLanguage)
            service.restoreSettings(previousService)
            cards.restoreSettings(previousCards)
            grid.restoreSettings(previousGrid)
        }
    }

    fun testDesktopCardPresentationFromNormalGrid() {
        val grid = AppGridPreferences(app)
        val cards = DesktopInfoPreferences(app)
        val appearance = AppearancePreferences(app)
        val originalGrid = grid.exportSettings()
        val originalCards = cards.exportSettings()
        val originalAppearance = appearance.exportSettings()
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        val settings = UiText.get(R.string.app_name)
        val all = UiText.get(R.string.apps_desktop_all)
        val favorite = UiText.get(R.string.apps_desktop_favorites_one)
        val motionScale = android.provider.Settings.Global.getFloat(app.contentResolver, "animator_duration_scale", 1f)
        val appEntry = InstalledApps.list(app).first { AppGridPins.supports(it.component().flattenToString()) }

        fun clock(): AccessibilityNodeInfo? = nodes().firstOrNull {
            it.isVisibleToUser && it.viewIdResourceName == "desktop-info:clock"
        }
        fun precedes(first: Rect, second: Rect): Boolean = when {
            first.bottom <= second.top -> true
            second.bottom <= first.top -> false
            else -> first.left < second.left
        }
        fun awaitCard(outlined: Boolean, pinFirst: Boolean? = null) {
            val before = app.getColor(R.color.cockpit_stroke)
            val without = app.getColor(R.color.cockpit_surface_raised)
            val deadline = SystemClock.uptimeMillis() + 6_000
            while (SystemClock.uptimeMillis() < deadline) {
                val card = clock()?.let { Rect().also(it::getBoundsInScreen) }
                val pin = textNode(appEntry.label())?.let { Rect().also(it::getBoundsInScreen) }
                if (card != null && (pinFirst == null || pin != null &&
                        (if (pinFirst) precedes(pin, card) else precedes(card, pin)))) {
                    val frame = automation.takeScreenshot()
                    if (frame != null) {
                        try {
                            val x = card.centerX()
                            val rows = (card.top..card.top + 4).filter { it in 0 until frame.height }
                            val colors = if (x in 0 until frame.width) rows.map { frame.getPixel(x, it) } else emptyList()
                            if ((before in colors) == outlined && (outlined || without in colors)) return
                        } finally { frame.recycle() }
                    }
                }
                SystemClock.sleep(70)
            }
            failWithEvidence("desktop-card-presentation", "The card did not draw its expected outline and pin order")
        }
        fun selectFavorite(expectMotion: Boolean) {
            val stationary = Rect().also(checkNotNull(textNode(settings))::getBoundsInScreen).left
            val action = AccessibilityNodeInfo.ACTION_CLICK
            val tab = generateSequence(textNode(favorite)) { it.parent }.firstOrNull { node ->
                node.actionList.any { it.id == action }
            }
            assertTrue("The unselected desktop must have an accessibility action", tab?.performAction(action) == true)
            if (motionScale >= 3f) {
                var moved = false
                val deadline = SystemClock.uptimeMillis() + 280
                while (!moved && SystemClock.uptimeMillis() < deadline) {
                    val bounds = textNode(settings)?.let { Rect().also(it::getBoundsInScreen) }
                    moved = bounds != null && bounds.left > stationary + 4
                    if (!moved) SystemClock.sleep(16)
                }
                assertEquals("The desktop grid must follow the selected switching style", expectMotion, moved)
            }
            awaitSelectedTab(favorite)
            awaitText(UiText.get(R.string.apps_desktop_find))
        }
        fun awaitOption(key: String, expected: Any) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (grid.exportSettings().get(key) != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertEquals(expected, grid.exportSettings().get(key))
        }
        try {
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            appearance.restoreSettings(JSONObject().put("palette", "dark").put("highContrast", false).put("animateRoutes", true))
            val desktops = JSONObject().put("enabled", true).put("defaultDesktop", "main")
                .put("favorites1", JSONArray()).put("favorites2", JSONArray())
            grid.restoreSettings(JSONObject().put("pinnedApps", JSONArray().put(appEntry.component().flattenToString()))
                .put("desktops", desktops).put("desktopNames", JSONObject().put("favorites1", "").put("favorites2", ""))
                .put("tileSize", "compact").put("columns", "4").put("showAppLabels", true).put("visibleShortcuts", JSONArray())
                .put("showDesktopCardOutlines", true).put("smoothDesktopPaging", false).put("desktopCardPlacement", "beforeApps"))
            val clockSetting = JSONObject().put("clock", "compact")
            cards.restoreSettings(JSONObject().put("main", clockSetting).put("favorites1", clockSetting))
            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            awaitSelectedTab(all)
            awaitCard(outlined = true, pinFirst = false)
            selectFavorite(expectMotion = false)
            clickText(all)
            awaitSelectedTab(all)

            clickText(settings)
            awaitRoute(activity, ControlsRoute.SETTINGS)
            val paging = UiText.get(R.string.apps_grid_smooth_paging)
            scrollTo(UiText.get(R.string.apps_grid_settings))
            scrollTo(paging)
            clickText(paging)
            awaitOption("smoothDesktopPaging", true)
            val placement = UiText.get(R.string.apps_grid_card_placement)
            scrollTo(placement)
            clickText(placement)
            clickText(UiText.get(R.string.apps_grid_pins_first))
            awaitOption("desktopCardPlacement", "afterPinnedApps")
            val outlines = UiText.get(R.string.apps_grid_card_outlines)
            scrollTo(outlines)
            clickText(outlines)
            awaitOption("showDesktopCardOutlines", false)
            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(activity)
            awaitCard(outlined = false, pinFirst = true)
            capture("desktop-cards-pins-first")
            selectFavorite(expectMotion = true)
            awaitCard(outlined = false)
            assertNull("Pins must stay on their desktop", textNode(appEntry.label()))

            val search = UiText.get(R.string.ui_113)
            scrollTo(search)
            clickText(search)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (nodes().none { it.isEditable && it.isVisibleToUser } && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50)
            }
            assertTrue(nodes().any { it.isEditable && it.isVisibleToUser })
            assertNull("Desktop cards must be hidden during app search", clock())
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
            appearance.restoreSettings(originalAppearance)
            cards.restoreSettings(originalCards)
            grid.restoreSettings(originalGrid)
        }
    }

    fun testGridAppearanceAndDesktopWallpaper() {
        val grid = AppGridPreferences(app)
        val wallpapers = DesktopWallpaperPreferences(app)
        val appearance = AppearancePreferences(app)
        val oldGrid = grid.exportSettings()
        val oldWallpaper = wallpapers.exportSettings()
        val oldAppearance = appearance.exportSettings()
        val target = InstalledApps.list(app).first {
            it.component().packageName != app.packageName && AppGridPins.supports(it.component().flattenToString())
        }
        val favorite = UiText.get(R.string.apps_desktop_favorites_one)
        val second = UiText.get(R.string.apps_desktop_favorites_two)
        fun tint(): Int? {
            val frame = instrumentation.uiAutomation.takeScreenshot() ?: return null
            try {
                val corner = frame.getPixel(frame.width * 95 / 100, frame.height * 93 / 100)
                return Color.red(corner) - Color.blue(corner)
            } finally { frame.recycle() }
        }
        fun awaitTint(description: String, matches: (Int) -> Boolean): Int {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                val sampled = tint()
                if (sampled != null && matches(sampled)) return sampled
                SystemClock.sleep(120)
            }
            failWithEvidence("desktop-wallpaper", "Wallpaper did not settle at $description (sample ${tint()})")
        }
        try {
            appearance.restoreSettings(JSONObject().put("palette", "dark").put("wallpaperDimming", "standard"))
            val desktops = JSONObject().put("enabled", true).put("defaultDesktop", "main")
                .put("favorites1", JSONArray().put(target.component().flattenToString())).put("favorites2", JSONArray())
            grid.restoreSettings(JSONObject().put("pinnedApps", JSONArray()).put("tileSize", "standard")
                .put("showSystemApps", true).put("desktops", desktops)
                .put("desktopNames", JSONObject().put("favorites1", "").put("favorites2", ""))
                .put("iconStyle", "native").put("showAppLabels", true).put("sortOrder", "name"))
            wallpapers.set(AppGridDesktop.MAIN, null)
            wallpapers.set(AppGridDesktop.FAVORITES_ONE, wallpaperFixture())
            wallpapers.set(AppGridDesktop.FAVORITES_TWO, null)

            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            clickText(UiText.get(R.string.app_name))
            awaitRoute(activity, ControlsRoute.SETTINGS)
            val icons = UiText.get(R.string.apps_grid_icon_style)
            scrollTo(icons)
            clickText(icons)
            clickText(UiText.get(R.string.apps_grid_icon_monochrome))
            val labels = UiText.get(R.string.apps_grid_show_labels)
            scrollTo(labels)
            clickText(labels)
            val sort = UiText.get(R.string.apps_grid_sort_order)
            scrollTo(sort)
            clickText(sort)
            clickText(UiText.get(R.string.apps_grid_sort_recent))

            val wallpaper = UiText.get(R.string.apps_wallpaper_title)
            scrollTo(wallpaper)
            clickText(wallpaper)
            awaitText(UiText.get(R.string.apps_wallpaper_custom))
            capture("desktop-wallpaper-settings")
            clickText(UiText.get(R.string.apps_desktop_done))
            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(activity)

            val settings = grid.exportSettings()
            assertEquals("monochrome", settings.getString("iconStyle"))
            assertFalse(settings.getBoolean("showAppLabels"))
            assertEquals("recentlyInstalled", settings.getString("sortOrder"))
            clickText(favorite)
            val standard = awaitTint("standard dimming with the custom image") { it > 16 }
            assertNull("The wallpaper does not restore hidden app labels", textNode(target.label()))
            assertTrue("The icon must retain its accessible name", nodes().any {
                it.isVisibleToUser && it.contentDescription?.toString() == target.label()
            })
            capture("desktop-wallpaper-favorites")
            appearance.restoreSettings(JSONObject().put("wallpaperDimming", "light"))
            awaitTint("light dimming") { it > standard + 3 }
            appearance.restoreSettings(JSONObject().put("wallpaperDimming", "strong"))
            awaitTint("strong dimming") { it < standard - 6 && it > 5 }
            clickText(second)
            awaitTint("a desktop with no custom image") { it <= 0 }
        } finally {
            wallpapers.restoreSettings(oldWallpaper)
            grid.restoreSettings(oldGrid)
            appearance.restoreSettings(oldAppearance)
        }
    }

    fun testAllAppsCanKeepTheSearchFieldVisible() {
        val preferences = AppGridPreferences(app)
        val before = preferences.exportSettings()
        val setting = UiText.get(R.string.apps_grid_show_search_field)
        val settings = UiText.get(R.string.app_name)
        val empty = UiText.get(R.string.apps_empty)
        val clear = UiText.get(R.string.apps_grid_clear_search)
        val query = "open-voyah-test-query-with-no-matching-app"
        fun field() = nodes().firstOrNull { it.isVisibleToUser && it.isEditable && it.refresh() }
        fun awaitField(expected: String) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (field()?.text?.toString() == expected) return
                SystemClock.sleep(50)
            }
            failWithEvidence("all-apps-search-field", "All apps did not show an editor containing '$expected'")
        }

        try {
            val fixture = JSONObject(before.toString()).put("showSearchField", false)
            fixture.getJSONObject("desktops").put("enabled", false)
            preferences.restoreSettings(fixture)
            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            assertAppGrid(activity)
            assertNull(field())
            scrollTo(settings)
            clickText(settings)
            awaitRoute(activity, ControlsRoute.SETTINGS)
            scrollTo(setting)
            clickText(setting)
            val saved = SystemClock.uptimeMillis() + 5_000
            while (!preferences.exportSettings().getBoolean("showSearchField") && SystemClock.uptimeMillis() < saved) {
                SystemClock.sleep(50)
            }
            assertTrue(preferences.exportSettings().getBoolean("showSearchField"))
            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(activity)
            awaitField("")
            assertFalse("The persistent field should wait for the user's input", checkNotNull(field()).isFocused)
            capture("all-apps-search-field")
            searchGridFor(query)
            awaitField(query)
            scrollTo(empty)
            clickText(clear, contentDescription = true)
            awaitField("")

            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            val reopened = launch(MainActivity.appsIntent(app)) as MainActivity
            assertAppGrid(reopened)
            awaitField("")
            assertTrue(AppGridPreferences(app).exportSettings().getBoolean("showSearchField"))
        } finally {
            preferences.restoreSettings(before)
        }
    }

    fun testSearchSurvivesRecreationButAnExplicitAppsEntryClearsIt() {
        val activity = launch(MainActivity.appsIntent(app)) as MainActivity
        assertAppGrid(activity)
        val query = "ovc-navigation-search-${SystemClock.uptimeMillis()}"
        fun editor(): AccessibilityNodeInfo? = nodes().firstOrNull {
            it.isVisibleToUser && it.isEditable && it.refresh() && it.isVisibleToUser && it.isEditable
        }
        fun awaitEditor(expected: String): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                editor()?.let { if (it.text?.toString().orEmpty() == expected) return it }
                SystemClock.sleep(50)
            }
            failWithEvidence("search-editor", "No active search editor with the expected query: $expected")
        }

        scrollTo(UiText.get(R.string.ui_113))
        clickText(UiText.get(R.string.ui_113))
        val field = awaitEditor("")
        assertTrue("Search does not advertise text input",
            field.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })
        assertTrue("Search rejected its query", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query) }))
        awaitEditor(query)

        lateinit var navigation: ControlsNavigationViewModel
        var entry = 0L
        instrumentation.runOnMainSync {
            navigation = ViewModelProvider(activity)[ControlsNavigationViewModel::class.java]
            entry = navigation.state.value.entry
        }
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        val recreated = try {
            instrumentation.runOnMainSync { activity.recreate() }
            checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5_000)) as MainActivity
        } finally { instrumentation.removeMonitor(monitor) }
        active = recreated
        instrumentation.waitForIdleSync()
        awaitRoute(recreated, ControlsRoute.APPS)
        awaitEditor(query)
        instrumentation.runOnMainSync {
            val retained = ViewModelProvider(recreated)[ControlsNavigationViewModel::class.java]
            assertSame(navigation, retained)
            assertEquals("Recreation started a new entry", entry, retained.state.value.entry)
            retained.open(ControlsRoute.SETTINGS)
        }
        awaitRoute(recreated, ControlsRoute.SETTINGS)
        awaitText(UiText.get(R.string.cockpit_back))
        // Ensure the outgoing grid has left composition before its saved state is restored.
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        assertNull("Search remained active behind Settings", editor())

        instrumentation.runOnMainSync { recreated.startActivity(MainActivity.appsIntent(recreated)) }
        awaitRoute(recreated, ControlsRoute.APPS)
        val deadline = SystemClock.uptimeMillis() + 5_000
        while ((editor() != null || textNode(query) != null) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
        }
        assertNull("Explicit Apps entry restored the old search editor", editor())
        assertNull("Explicit Apps entry restored the obsolete query", textNode(query))
        scrollTo(UiText.get(R.string.app_name))
        assertAppGrid(recreated)

        // A hidden stale query would still filter apps; reopening Search must reveal an empty value.
        scrollTo(UiText.get(R.string.ui_113))
        clickText(UiText.get(R.string.ui_113))
        awaitEditor("")
    }

    fun testVirtualPreviewShowsOnlySelectedContent() {
        val store = app.getSharedPreferences(DisplayPreviewPreferences.STORE, 0)
        val original = store.getString("content", null)
        val options = DisplayPreviewPreferences(app)
        val events = UiText.displayEvents(app)
        val previous = events.snapshot()
        try {
            options.update { DisplayPreviewContent() }
            instrumentation.runOnMainSync {
                events.publishMedia(DisplayEventBus.Media("Night drive", "Demo artist", "", true))
                events.publishNavigation(DisplayEventBus.Navigation("Щёлковское шоссе", "Вуліца Ўсходняя", 400))
            }
            val activity = launch(ControlsNavigation.intent(app, ControlsRoute.DISPLAY)) as MainActivity
            awaitRoute(activity, ControlsRoute.DISPLAY)
            val ready = Regex("[0-9]+ × [0-9]+ · [0-9]+ DPI")
            val deadline = SystemClock.uptimeMillis() + 8_000
            while (nodes().none { it.text?.toString()?.matches(ready) == true } && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(100)
            }
            assertTrue("The virtual display did not become ready", nodes().any { it.text?.toString()?.matches(ready) == true })

            val media = UiText.get(R.string.display_preview_show_media)
            val route = UiText.get(R.string.display_preview_show_navigation)
            fun awaitOptions(target: DisplayPreviewContent) {
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (options.load() != target && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                assertEquals(target, DisplayPreviewPreferences(app).load())
            }
            fun choose(label: String, target: DisplayPreviewContent, screenshot: String) {
                scrollTo(label)
                clickText(label)
                awaitOptions(target)
                capture(screenshot)
            }
            fun selectOption(setting: Int, item: Int, target: DisplayPreviewContent) {
                val label = UiText.get(setting)
                scrollTo(label)
                clickText(label)
                clickText(UiText.get(item))
                awaitOptions(target)
                scrollTo(UiText.get(R.string.virtual_display))
            }
            fun awaitPreview(matches: (PreviewInk) -> Boolean): PreviewInk {
                val timeout = SystemClock.uptimeMillis() + 6_000
                while (SystemClock.uptimeMillis() < timeout) {
                    readPreviewSurface(activity)?.let { ink ->
                        if (ink.headingWidth > 0 && matches(ink)) return ink
                    }
                    SystemClock.sleep(80)
                }
                failWithEvidence("display-preview-layout", "The virtual preview did not draw the expected pane arrangement")
            }
            capture("display-preview-both")
            val originalPreview = awaitPreview { true }
            val stacked = DisplayPreviewContent(arrangement = DisplayPreviewArrangement.STACKED)
            selectOption(R.string.display_preview_arrangement, R.string.display_preview_arrangement_stacked, stacked)
            val stackedMedia = awaitPreview { !it.rightPane }
            capture("display-preview-stacked-media-first")
            val routeFirst = stacked.copy(paneOrder = DisplayPreviewPaneOrder.ROUTE_FIRST)
            selectOption(R.string.display_preview_pane_order, R.string.display_preview_route_first, routeFirst)
            val stackedRoute = awaitPreview { !it.rightPane && it.headingWidth < stackedMedia.headingWidth - 4 }
            capture("display-preview-stacked-route-first")
            val horizontal = routeFirst.copy(arrangement = DisplayPreviewArrangement.SIDE_BY_SIDE)
            selectOption(R.string.display_preview_arrangement, R.string.display_preview_arrangement_side_by_side, horizontal)
            if (originalPreview.rightPane) awaitPreview { it.rightPane && kotlin.math.abs(it.headingWidth - stackedRoute.headingWidth) <= 2 }
            capture("display-preview-side-by-side-route-first")

            val details = UiText.get(R.string.display_preview_details)
            scrollTo(details)
            clickText(details)
            val daylight = horizontal.copy(palette = DisplayPreviewPalette.DAYLIGHT)
            val daylightLabel = UiText.get(R.string.display_preview_palette_daylight)
            scrollTo(daylightLabel)
            clickText(daylightLabel)
            awaitOptions(daylight)
            val widerRoute = daylight.copy(paneBalance = DisplayPreviewPaneBalance.NAVIGATION)
            val widerRouteLabel = UiText.get(R.string.display_preview_pane_balance_navigation)
            scrollTo(widerRouteLabel)
            clickText(widerRouteLabel)
            awaitOptions(widerRoute)
            val customPreview = widerRoute.copy(distanceUnits = DisplayPreviewDistanceUnits.IMPERIAL)
            val imperial = UiText.get(R.string.display_preview_distance_units_imperial)
            scrollTo(imperial)
            clickText(imperial)
            awaitOptions(customPreview)
            capture("display-preview-details")
            clickText(UiText.get(R.string.display_preview_details_close))
            scrollTo(UiText.get(R.string.virtual_display), towardsStart = true)
            awaitPreview { it.background == Color.rgb(238, 242, 247) }
            capture("display-preview-daylight")

            val routeOnly = customPreview.copy(showMedia = false)
            choose(media, routeOnly, "display-preview-route")
            scrollTo(UiText.get(R.string.virtual_display), towardsStart = true)
            val twoRoads = awaitPreview { !it.rightPane }

            scrollTo(details)
            clickText(details)
            val latinOnly = routeOnly.copy(roadNames = DisplayPreviewRoadNames.LATIN)
            val latin = UiText.get(R.string.display_preview_road_names_latin)
            scrollTo(latin)
            clickText(latin)
            awaitOptions(latinOnly)
            val currentRoad = UiText.get(R.string.display_preview_show_current_road)
            scrollTo(currentRoad, towardsStart = true)
            clickText(currentRoad)
            val nextRoadOnly = latinOnly.copy(showCurrentRoad = false)
            awaitOptions(nextRoadOnly)
            capture("display-preview-road-options")
            clickText(UiText.get(R.string.display_preview_details_close))
            scrollTo(UiText.get(R.string.virtual_display), towardsStart = true)
            awaitPreview { !it.rightPane && it.contentHeight < twoRoads.contentHeight - 3 }
            capture("display-preview-route-latin")

            choose(route, nextRoadOnly.copy(showNavigation = false), "display-preview-empty")
            val mediaOnly = nextRoadOnly.copy(showMedia = true, showNavigation = false)
            choose(media, mediaOnly, "display-preview-media")
            instrumentation.runOnMainSync {
                events.publishMedia(DisplayEventBus.Media("Night drive", "Demo artist", "", false),
                    PlaybackProgress(240_000, 12_000, SystemClock.elapsedRealtime(), 0f))
            }
            choose(UiText.get(R.string.display_preview_show_playback_progress),
                mediaOnly.copy(showPlaybackProgress = true), "display-preview-progress")
        } finally {
            instrumentation.runOnMainSync {
                active?.finish()
                events.publishMedia(previous.media(), previous.playbackProgress())
                events.publishNavigation(previous.navigation())
            }
            assertTrue(store.edit().putString("content", original).commit())
        }
    }

    private data class PreviewInk(val headingWidth: Int, val rightPane: Boolean, val background: Int, val contentHeight: Int)

    private fun readPreviewSurface(activity: Activity): PreviewInk? {
        fun surface(view: View): SurfaceView? = when (view) {
            is SurfaceView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { surface(view.getChildAt(it)) }
            else -> null
        }
        val frames = LinkedBlockingQueue<Bitmap>()
        instrumentation.runOnMainSync {
            val view = surface(activity.findViewById(android.R.id.content))
            if (view != null && view.width > 0 && view.height > 0 && view.holder.surface.isValid) {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    PixelCopy.request(view, bitmap, { result ->
                        if (result == PixelCopy.SUCCESS) frames.add(bitmap) else bitmap.recycle()
                    }, Handler(Looper.getMainLooper()))
                } catch (_: IllegalArgumentException) {
                    bitmap.recycle()
                }
            }
        }
        val frame = frames.poll(700, TimeUnit.MILLISECONDS) ?: return null
        try {
            val width = frame.width
            val pixels = IntArray(width * frame.height)
            frame.getPixels(pixels, 0, width, 0, 0, width, frame.height)
            val background = pixels[0]
            var left = width
            var right = -1
            var firstRow = -1
            var lastRow = -1
            var headingEnded = false
            var rightPane = false
            for (y in 0 until frame.height) {
                var leftPane = false
                var nextPane = false
                for (x in 0 until width) {
                    if (pixels[y * width + x] == background) continue
                    if (x < width / 2) {
                        leftPane = true
                        if (!headingEnded) {
                            left = minOf(left, x)
                            right = maxOf(right, x)
                        }
                    } else nextPane = true
                }
                if (leftPane) {
                    if (firstRow < 0) firstRow = y
                    lastRow = y
                    if (!headingEnded && nextPane) rightPane = true
                } else if (right >= 0) headingEnded = true
            }
            val contentHeight = if (firstRow < 0) 0 else lastRow - firstRow + 1
            return PreviewInk((right - left + 1).coerceAtLeast(0), rightPane, background, contentHeight)
        } finally {
            frame.recycle()
        }
    }

    fun testVirtualMediaArtworkAndTransport() {
        val options = DisplayPreviewPreferences(app)
        val original = options.load()
        val desktopCards = DesktopInfoPreferences(app)
        val originalCards = desktopCards.exportSettings()
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        val calls = LinkedBlockingQueue<String>()
        val listener = ComponentName(app, MediaAccessService::class.java)
        val notifications = app.getSystemService(NotificationManager::class.java)
        val access = notifications.isNotificationListenerAccessGranted(listener)
        val coverColor = Color.rgb(146, 67, 85)
        val artwork = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(coverColor) }
        var session: MediaSession? = null

        fun listenerAccess(allow: Boolean) {
            val operation = if (allow) "allow_listener" else "disallow_listener"
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd notification $operation ${listener.flattenToString()}")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (notifications.isNotificationListenerAccessGranted(listener) != allow && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(70)
            }
            assertEquals(allow, notifications.isNotificationListenerAccessGranted(listener))
        }
        fun chooseSwitch(id: Int) {
            val name = UiText.get(id)
            scrollTo(name)
            clickText(name)
        }
        fun pressAction(id: Int, step: PreviewSeekStep? = null) {
            val name = UiText.get(id).let { if (step == null) it else it.format(step.seconds) }
            scrollTo(name)
            clickText(name)
        }

        try {
            if (!access) listenerAccess(true)
            options.update { DisplayPreviewContent(showNavigation = false, showPlaybackProgress = true) }
            instrumentation.runOnMainSync {
                session = MediaSession(app, "Controls preview instrumentation").apply {
                    setCallback(object : MediaSession.Callback() {
                        override fun onPlay() { calls.add("play") }
                        override fun onSkipToNext() { calls.add("next") }
                        override fun onSeekTo(pos: Long) { calls.add("seek:$pos") }
                    }, Handler(Looper.getMainLooper()))
                    setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Harbor lights")
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, "Demo artist")
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, 240_000)
                        .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork).build())
                    setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_SKIP_TO_NEXT).setState(PlaybackState.STATE_PAUSED, 32_000, 0f).build())
                    isActive = true
                }
            }
            val activity = launch(ControlsNavigation.intent(app, ControlsRoute.DISPLAY)) as MainActivity
            awaitRoute(activity, ControlsRoute.DISPLAY)
            chooseSwitch(R.string.display_preview_show_album_artwork)
            chooseSwitch(R.string.display_preview_show_transport_controls)
            val source = UiText.get(R.string.display_preview_media_source)
            scrollTo(source)
            clickText(source)
            val label = app.applicationInfo.loadLabel(app.packageManager).toString()
            clickText(label)

            val deadline = SystemClock.uptimeMillis() + 5_000
            while (options.load().preferredMediaPackage != app.packageName && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(60)
            }
            val saved = options.load()
            assertTrue(saved.showAlbumArtwork && saved.showTransportControls)
            assertEquals(app.packageName, saved.preferredMediaPackage)
            scrollTo(UiText.get(R.string.display_preview_show_transport_controls))
            pressAction(R.string.display_preview_seek_forward, PreviewSeekStep.TEN)
            assertEquals("seek:42000", calls.poll(5, TimeUnit.SECONDS))
            val interval = UiText.get(R.string.display_preview_seek_step)
            scrollTo(interval)
            clickText(interval)
            clickText(UiText.get(R.string.display_preview_seek_seconds).format(30))
            val stepDeadline = SystemClock.uptimeMillis() + 5_000
            while (options.load().seekStep != PreviewSeekStep.THIRTY && SystemClock.uptimeMillis() < stepDeadline) {
                SystemClock.sleep(50)
            }
            assertEquals(PreviewSeekStep.THIRTY, options.load().seekStep)
            pressAction(R.string.display_preview_seek_forward, PreviewSeekStep.THIRTY)
            assertEquals("seek:62000", calls.poll(5, TimeUnit.SECONDS))
            pressAction(R.string.display_preview_seek_back, PreviewSeekStep.THIRTY)
            assertEquals("seek:2000", calls.poll(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                session?.setPlaybackState(PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_SKIP_TO_NEXT)
                    .setState(PlaybackState.STATE_PAUSED, 32_000, 0f).build())
            }
            val removed = UiText.get(R.string.display_preview_seek_forward).format(30)
            val unsupportedDeadline = SystemClock.uptimeMillis() + 5_000
            while (anyTextNode(removed) != null && SystemClock.uptimeMillis() < unsupportedDeadline) SystemClock.sleep(50)
            assertNull("Seek must disappear when the player withdraws it", anyTextNode(removed))
            pressAction(R.string.display_preview_play)
            assertEquals("play", calls.poll(5, TimeUnit.SECONDS))
            pressAction(R.string.display_preview_next)
            assertEquals("next", calls.poll(5, TimeUnit.SECONDS))
            capture("display-preview-source-options")
            scrollTo(UiText.get(R.string.virtual_display))
            capture("display-preview-transport")

            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            desktopCards.restoreSettings(JSONObject("""{"main":{"media":"wide","mediaStyle":"cover"}}"""))
            instrumentation.runOnMainSync { activity.startActivity(MainActivity.appsIntent(activity)) }
            assertAppGrid(activity)
            awaitText("Harbor lights")
            assertDesktopMediaArtwork(coverColor, visible = true)
            capture("desktop-media-cover")

            instrumentation.runOnMainSync {
                session?.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Morning ferry")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Demo artist").build())
            }
            awaitText("Morning ferry")
            assertDesktopMediaArtwork(coverColor, visible = false)
        } finally {
            instrumentation.runOnMainSync { session?.release() }
            artwork.recycle()
            options.update { original }
            desktopCards.restoreSettings(originalCards)
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            if (!access) listenerAccess(false)
        }
    }

    private fun assertDesktopMediaArtwork(artworkColor: Int, visible: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val card = nodes().firstOrNull { it.isVisibleToUser && it.viewIdResourceName == "desktop-info:media" }
            val frame = card?.let { instrumentation.uiAutomation.takeScreenshot() }
            if (card != null && frame != null) {
                try {
                    val region = Rect().also(card::getBoundsInScreen)
                    if (region.intersect(0, 0, frame.width, frame.height)) {
                        val found = (region.top until region.bottom step 8).any { y ->
                            (region.left until region.right step 8).any { x -> frame.getPixel(x, y) == artworkColor }
                        }
                        if (found == visible) return
                    }
                } finally { frame.recycle() }
            }
            SystemClock.sleep(80)
        }
        failWithEvidence("desktop-media-cover", "Expected cover pixels visible=$visible in the media card")
    }

    fun testAppearanceControlsApplyAndPersist() {
        val preferences = AppearancePreferences(app)
        val original = preferences.exportSettings()
        val appearance = UiText.get(R.string.appearance_title)
        fun choose(setting: Int, option: Int, field: String, expected: String) {
            val name = UiText.get(setting)
            scrollTo(name)
            clickText(name)
            clickText(UiText.get(option))
            awaitAppearanceSave(preferences, field, expected)
        }
        fun enable(setting: Int, field: String) {
            val name = UiText.get(setting)
            scrollTo(name)
            clickText(name)
            awaitAppearanceSave(preferences, field, true)
        }
        try {
            preferences.restoreSettings(JSONObject("""{"textSize":"standard","spacing":"standard","corners":"standard",
                "palette":"dark","highContrast":false,"animateRoutes":false,"accent":"default","wallpaperDimming":"standard",
                "fontFamily":"system","backgroundTreatment":"ambient","boldText":false,
                "navigationLabelSize":"follow","pageTitleSize":"follow"}"""))
            val activity = launch(ControlsNavigation.intent(app, ControlsRoute.SETTINGS)) as MainActivity
            awaitRoute(activity, ControlsRoute.SETTINGS)
            scrollTo(appearance)
            enable(R.string.appearance_high_contrast, "highContrast")
            choose(R.string.appearance_palette, R.string.appearance_palette_day, "palette", "day")
            choose(R.string.appearance_accent, R.string.appearance_accent_teal, "accent", "teal")
            choose(R.string.appearance_background, R.string.appearance_background_solid, "backgroundTreatment", "solid")
            choose(R.string.appearance_wallpaper_dimming, R.string.appearance_wallpaper_dimming_strong,
                "wallpaperDimming", "strong")
            choose(R.string.appearance_text_size, R.string.appearance_text_extra_large, "textSize", "extra_large")
            choose(R.string.appearance_navigation_label_size, R.string.appearance_option_standard,
                "navigationLabelSize", "standard")
            choose(R.string.appearance_page_title_size, R.string.appearance_text_large, "pageTitleSize", "large")
            choose(R.string.appearance_font_family, R.string.appearance_font_golos, "fontFamily", "golosText")
            enable(R.string.appearance_bold_text, "boldText")
            capture("appearance-options")
            choose(R.string.appearance_spacing, R.string.appearance_spacing_compact, "spacing", "compact")
            choose(R.string.appearance_corners, R.string.appearance_corners_subtle, "corners", "subtle")

            val saved = AppearancePreferences(app).exportSettings()
            assertEquals("day", saved.getString("palette"))
            assertEquals("extra_large", saved.getString("textSize"))
            assertEquals("standard", saved.getString("navigationLabelSize"))
            assertEquals("large", saved.getString("pageTitleSize"))
            assertEquals("compact", saved.getString("spacing"))
            assertEquals("subtle", saved.getString("corners"))
            assertEquals("teal", saved.getString("accent"))
            assertEquals("strong", saved.getString("wallpaperDimming"))
            assertEquals("golosText", saved.getString("fontFamily"))
            assertEquals("solid", saved.getString("backgroundTreatment"))
            assertTrue(saved.getBoolean("boldText"))
            scrollTo(appearance, towardsStart = true)
            capture("appearance-daylight")
            instrumentation.runOnMainSync {
                val window = activity.window
                val bars = WindowCompat.getInsetsController(window, window.decorView)
                assertTrue(bars.isAppearanceLightStatusBars)
                assertTrue(bars.isAppearanceLightNavigationBars)
                if (Build.VERSION.SDK_INT < 35) {
                    @Suppress("DEPRECATION")
                    assertEquals(DayCockpitColors.background.toArgb(), window.statusBarColor)
                    @Suppress("DEPRECATION")
                    assertEquals(DayCockpitColors.background.toArgb(), window.navigationBarColor)
                }
            }
        } finally {
            preferences.restoreSettings(original)
        }
    }

    fun testScreenTransitionSpeedSettingAndNavigation() {
        val preferences = AppearancePreferences(app)
        val original = preferences.exportSettings()
        val animate = UiText.get(R.string.appearance_animate_routes)
        val speed = UiText.get(R.string.appearance_transition_speed)
        fun chooseSpeed(option: Int, expected: String) {
            scrollTo(speed)
            clickText(speed)
            clickText(UiText.get(option))
            awaitAppearanceSave(preferences, "transitionSpeed", expected)
        }
        try {
            preferences.restoreSettings(JSONObject(
                """{"animateRoutes":true,"transitionSpeed":"standard","launcherEntry":"apps"}""",
            ))
            val activity = launch(ControlsNavigation.intent(app, ControlsRoute.SETTINGS)) as MainActivity
            awaitRoute(activity, ControlsRoute.SETTINGS)
            chooseSpeed(R.string.appearance_transition_smooth, "smooth")

            scrollTo(animate)
            clickText(animate)
            awaitAppearanceSave(preferences, "animateRoutes", false)
            assertEquals("smooth", preferences.exportSettings().getString("transitionSpeed"))
            clickText(animate)
            awaitAppearanceSave(preferences, "animateRoutes", true)
            chooseSpeed(R.string.appearance_transition_short, "short")
            capture("appearance-transition-speed")

            val vehicle = UiText.get(R.string.vehicle_settings_title)
            scrollTo(vehicle, towardsStart = true)
            clickText(vehicle)
            awaitRoute(activity, ControlsRoute.VEHICLE)
            clickText(UiText.get(R.string.cockpit_back))
            awaitRoute(activity, ControlsRoute.SETTINGS)
            assertEquals("short", AppearancePreferences(app).exportSettings().getString("transitionSpeed"))
        } finally {
            preferences.restoreSettings(original)
        }
    }

    fun testLauncherMediaVolumeChoiceStaysSavedWhileDisabled() {
        val preferences = AppearancePreferences(app)
        val original = preferences.exportSettings()
        val control = UiText.get(R.string.appearance_limit_media_on_launch)
        val limit = UiText.get(R.string.appearance_launch_media_limit)
        try {
            preferences.restoreSettings(JSONObject().put("limitMediaVolumeOnLaunch", false).put("launchMediaVolumePercent", 40))
            val activity = launch(ControlsNavigation.intent(app, ControlsRoute.SETTINGS)) as MainActivity
            awaitRoute(activity, ControlsRoute.SETTINGS)
            scrollTo(control)
            clickText(control)
            awaitAppearanceSave(preferences, "limitMediaVolumeOnLaunch", true)
            scrollTo(limit)
            clickText(limit)
            val option = UiText.get(R.string.appearance_media_volume_percent).format(Locale.ROOT, 30)
            scrollTo(option)
            clickText(option)
            awaitAppearanceSave(preferences, "launchMediaVolumePercent", 30)
            scrollTo(limit)
            awaitText(option)
            capture("launcher-media-volume-settings")

            scrollTo(control, towardsStart = true)
            clickText(control)
            awaitAppearanceSave(preferences, "limitMediaVolumeOnLaunch", false)
            assertEquals(30, preferences.exportSettings().getInt("launchMediaVolumePercent"))
        } finally {
            preferences.restoreSettings(original)
        }
    }

    private fun awaitAppearanceSave(preferences: AppearancePreferences, field: String, expected: Any) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (preferences.exportSettings().get(field) != expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(60)
        }
        assertEquals(expected, preferences.exportSettings().get(field))
    }

    fun testVisibleHeadlightCardUsesCurrentObservationAndKeepsHistoryAfterDisconnect() {
        val cards = DesktopInfoPreferences(app)
        val previousCards = cards.exportSettings()
        val grid = AppGridPreferences(app)
        val previousGrid = grid.exportSettings()
        val monitoring = HeadlightMonitoringPreferences(app)
        val previousMonitoring = monitoring.exportSettings()
        val history = LocalHeadlightHistory(app)
        val previousHistory = app.getSharedPreferences(LocalHeadlightHistory.STORE, 0).all
        val feedback = MutableSharedFlow<HeadlightFeedback>()
        fun awaitSubscribers(count: Int) {
            val observed = runBlocking { withTimeoutOrNull(5_000) { feedback.subscriptionCount.first { it == count } } }
            if (observed == null) {
                failWithEvidence("desktop-headlight-subscriptions", "Expected $count headlight subscriptions. Observed ${feedback.subscriptionCount.value}.")
            }
        }
        fun report(value: HeadlightFeedback) = runBlocking { withTimeout(5_000) { feedback.emit(value) } }
        val off = UiText.get(R.string.headlight_mode_off)
        val position = UiText.get(R.string.headlight_mode_position)
        val historical = UiText.get(R.string.desktop_info_headlights_last_confirmed)
        val current = UiText.get(R.string.desktop_info_headlights_current)
        try {
            monitoring.restoreSettings(JSONObject().put("whileControlsOpen", false))
            val singleGrid = JSONObject(previousGrid.toString()).apply {
                getJSONObject("desktops").put("enabled", false).put("defaultDesktop", "main")
            }
            grid.restoreSettings(singleGrid)
            cards.restoreSettings(JSONObject("{}"))
            check(history.save(HeadlightObservation(HeadlightMode.OFF, System.currentTimeMillis() - 60_000)))
            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            instrumentation.runOnMainSync {
                val fixture = VehicleSettingsViewModel({ false }, HeadlightMonitor(feedback, history))
                val installed = ViewModelProvider(activity, viewModelFactory { initializer { fixture } })[
                    VEHICLE_SETTINGS_MODEL_KEY, VehicleSettingsViewModel::class.java
                ]
                assertSame("Install the fixture before a production read is started", fixture, installed)
            }
            assertAppGrid(activity)
            assertEquals(0, feedback.subscriptionCount.value)
            cards.restoreSettings(JSONObject("""{"main":{"headlights":"compact","headlightContent":"switch"}}"""))
            awaitText(off)
            awaitText(historical)
            assertNull(textNode(current))
            awaitSubscribers(1)

            val now = HeadlightObservation(HeadlightMode.POSITION, System.currentTimeMillis())
            report(HeadlightFeedback.Confirmed(now, SystemClock.elapsedRealtimeNanos()))
            awaitText(position)
            awaitText(current)
            assertEquals(now, history.read())
            capture("desktop-headlight-live-fixture")

            clickText(UiText.get(R.string.desktop_info_headlights))
            awaitRoute(activity, ControlsRoute.VEHICLE)
            val vehicleTitle = UiText.get(R.string.vehicle_settings_title)
            val confirmation = UiText.get(R.string.headlight_mode_confirmed).format("")
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (true) {
                // Each virtual node in this snapshot has already been refreshed by nodes().
                val labels = nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }.toSet()
                if (vehicleTitle in labels && position in labels && labels.any { it.startsWith(confirmation) }) break
                if (SystemClock.uptimeMillis() >= deadline) {
                    failWithEvidence("vehicle-headlight-confirmation", "The vehicle page did not show the live $position reading.")
                }
                SystemClock.sleep(100)
            }
            assertEquals(1, feedback.subscriptionCount.value)
            report(HeadlightFeedback.Unavailable)
            awaitText(UiText.get(R.string.headlight_mode_unavailable))
            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(activity)
            awaitText(position)
            awaitText(historical)
            assertNull(textNode(current))
            capture("desktop-headlight-history")

            clickText(UiText.get(R.string.app_name))
            awaitRoute(activity, ControlsRoute.SETTINGS)
            awaitSubscribers(0)
        } finally {
            cards.restoreSettings(previousCards)
            grid.restoreSettings(previousGrid)
            restoreHeadlightTestHistory(previousHistory)
            monitoring.restoreSettings(previousMonitoring)
        }
    }

    fun testDesktopMediaCardOpensVirtualDisplayWhenThePlayerIsUnknown() {
        val cards = DesktopInfoPreferences(app)
        val previousCards = cards.exportSettings()
        val grid = AppGridPreferences(app)
        val previousGrid = grid.exportSettings()
        val events = UiText.displayEvents(app)
        val previousMedia = events.snapshot()
        try {
            val layout = JSONObject(previousGrid.toString()).put("showSearchField", false)
            layout.getJSONObject("desktops").put("enabled", false)
            grid.restoreSettings(layout)
            cards.restoreSettings(JSONObject("""{"main":{"media":"wide","mediaStyle":"text","mediaOpenTarget":"player"}}"""))
            instrumentation.runOnMainSync {
                events.publishMedia(DisplayEventBus.Media("Demo track", "Demo artist", "", true))
            }
            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            assertAppGrid(activity)
            val openDisplay = UiText.get(R.string.desktop_info_media_open_display_action)
            scrollTo(openDisplay)
            awaitText("Demo track")
            capture("desktop-media-card-open")
            clickText(openDisplay)
            awaitRoute(activity, ControlsRoute.DISPLAY)
        } finally {
            instrumentation.runOnMainSync {
                events.publishMedia(previousMedia.media())
                events.publishNavigation(previousMedia.navigation())
            }
            cards.restoreSettings(previousCards)
            grid.restoreSettings(previousGrid)
        }
    }

    fun testDesktopHeadlightCardCanShowLampActivity() {
        val grid = AppGridPreferences(app)
        val cards = DesktopInfoPreferences(app)
        val monitoring = HeadlightMonitoringPreferences(app)
        val history = LocalHeadlightHistory(app)
        val historyStore = app.getSharedPreferences(LocalHeadlightHistory.STORE, 0)
        val previousGrid = grid.exportSettings()
        val previousCards = cards.exportSettings()
        val previousMonitoring = monitoring.exportSettings()
        val previousHistory = historyStore.all
        val previousLanguage = UiText.language()
        val events = MutableSharedFlow<HeadlightFeedback>()
        fun awaitMonitor() = runBlocking { withTimeout(5_000) { events.subscriptionCount.first { it == 1 } } }
        fun send(feedback: HeadlightFeedback) = runBlocking { withTimeout(5_000) { events.emit(feedback) } }
        fun lampLabel(lamp: Int, value: Int): String =
            UiText.get(R.string.headlight_activity_summary).format(UiText.get(lamp), UiText.get(value))

        try {
            UiText.language("ru")
            monitoring.restoreSettings(JSONObject().put("whileControlsOpen", false))
            check(historyStore.edit().clear().commit())
            grid.restoreSettings(JSONObject().put("pinnedApps", JSONArray()).put("columns", "4")
                .put("tileSize", "compact").put("desktopCardPlacement", "beforeApps")
                .put("desktops", JSONObject().put("enabled", false).put("defaultDesktop", "main")
                    .put("favorites1", JSONArray()).put("favorites2", JSONArray())))
            cards.restoreSettings(JSONObject())
            val activity = launch(MainActivity.appsIntent(app)) as MainActivity
            instrumentation.runOnMainSync {
                ViewModelProvider(activity, viewModelFactory { initializer {
                    VehicleSettingsViewModel({ false }, HeadlightMonitor(events, history))
                } })[VEHICLE_SETTINGS_MODEL_KEY, VehicleSettingsViewModel::class.java]
            }
            assertAppGrid(activity)
            cards.restoreSettings(JSONObject("""{"main":{"headlights":"compact","headlightContent":"switch"}}"""))
            awaitMonitor()
            val selector = HeadlightObservation(HeadlightMode.AUTOMATIC, System.currentTimeMillis() - 60_000)
            val automatic = UiText.get(R.string.headlight_mode_automatic)
            val earlier = UiText.get(R.string.desktop_info_headlights_earlier_report)
            send(HeadlightFeedback.EarlierSelectorReport(EarlierHeadlightReport(HeadlightMode.AUTOMATIC, selector.confirmedAtMillis)))
            awaitText(earlier)
            awaitText(automatic)
            assertNull(history.read())
            send(HeadlightFeedback.Confirmed(selector, SystemClock.elapsedRealtimeNanos()))
            awaitText(UiText.get(R.string.desktop_info_headlights_current))
            assertNull(textNode(earlier))
            awaitText(automatic)

            val editor = UiText.get(R.string.desktop_info_settings)
            val lampContent = UiText.get(R.string.desktop_info_headlights_content_lamps)
            val contentSetting = UiText.get(R.string.desktop_info_headlights_content)
            clickText(UiText.get(R.string.app_name))
            awaitRoute(activity, ControlsRoute.SETTINGS)
            scrollTo(editor)
            clickText(editor)
            scrollTo(contentSetting)
            clickText(contentSetting)
            clickText(lampContent)
            val reopened = DesktopInfoPreferences(app)
            val deadline = SystemClock.uptimeMillis() + 5_000
            fun savedContent() = reopened.exportSettings().getJSONObject("main").getString("headlightContent")
            while (SystemClock.uptimeMillis() < deadline && savedContent() != "lamps") SystemClock.sleep(50)
            assertEquals("lamps", savedContent())
            capture("desktop-headlight-content-setting")
            clickText(UiText.get(R.string.apps_desktop_done))
            clickText(UiText.get(R.string.cockpit_back))
            assertAppGrid(activity)
            awaitText(lampContent)
            val unconfirmed = R.string.desktop_info_headlights_lamps_unconfirmed
            awaitText(lampLabel(R.string.headlight_activity_main, unconfirmed))
            awaitText(lampLabel(R.string.headlight_activity_high, unconfirmed))
            assertNull(textNode(automatic))

            awaitMonitor()
            val now = System.currentTimeMillis()
            val headlights = LampObservation(LampActivity.ON, now - 20_000)
            val highBeam = LampObservation(LampActivity.OFF, now - 10_000)
            val on = lampLabel(R.string.headlight_activity_main, R.string.headlight_activity_on)
            val highOff = lampLabel(R.string.headlight_activity_high, R.string.headlight_activity_off)
            send(HeadlightFeedback.EarlierLampReport(ExteriorLamp.HEADLIGHTS, EarlierHeadlightReport(LampActivity.OFF, now - 60_000)))
            send(HeadlightFeedback.EarlierLampReport(ExteriorLamp.HIGH_BEAM, EarlierHeadlightReport(LampActivity.ON, now - 70_000)))
            awaitText(lampLabel(R.string.headlight_activity_main, R.string.headlight_activity_off))
            awaitText(lampLabel(R.string.headlight_activity_high, R.string.headlight_activity_on))
            assertEquals(2, nodes().count { it.isVisibleToUser && it.text?.toString() == earlier })
            assertTrue(history.readLamps().isEmpty())
            capture("desktop-headlight-initial-fixture")
            send(HeadlightFeedback.ActivityConfirmed(ExteriorLamp.HEADLIGHTS, headlights, SystemClock.elapsedRealtimeNanos()))
            send(HeadlightFeedback.ActivityConfirmed(ExteriorLamp.HIGH_BEAM, highBeam, SystemClock.elapsedRealtimeNanos()))
            awaitText(on)
            awaitText(highOff)
            val historical = UiText.get(R.string.desktop_info_headlights_lamps_last_confirmed)
            assertNull(textNode(historical))
            send(HeadlightFeedback.ActivityUnavailable(ExteriorLamp.HEADLIGHTS))
            awaitText(historical)
            assertNotNull(textNode(on))
            assertNotNull(textNode(highOff))
            assertEquals(1, nodes().count { it.isVisibleToUser && it.text?.toString() == historical })
            capture("desktop-headlight-lamps-fixture")
            assertEquals(mapOf(ExteriorLamp.HEADLIGHTS to headlights, ExteriorLamp.HIGH_BEAM to highBeam),
                LocalHeadlightHistory(app).readLamps())
        } finally {
            UiText.language(previousLanguage)
            cards.restoreSettings(previousCards)
            grid.restoreSettings(previousGrid)
            restoreHeadlightTestHistory(previousHistory)
            monitoring.restoreSettings(previousMonitoring)
        }
    }

    fun testHeadlightSwitchAndLampActivityAreConfirmedSeparately() {
        val history = LocalHeadlightHistory(app)
        val preferences = app.getSharedPreferences(LocalHeadlightHistory.STORE, 0)
        val previous = preferences.all
        val monitoring = HeadlightMonitoringPreferences(app)
        val previousMonitoring = monitoring.exportSettings()
        val previousLanguage = UiText.language()
        val events = MutableSharedFlow<HeadlightFeedback>()
        val time = System.currentTimeMillis()
        val selector = HeadlightObservation(HeadlightMode.AUTOMATIC, time - 60_000)
        val headlights = LampObservation(LampActivity.ON, time - 120_000)
        val highBeam = LampObservation(LampActivity.OFF, time - 180_000)
        fun send(feedback: HeadlightFeedback) = runBlocking { withTimeout(5_000) { events.emit(feedback) } }
        fun activityLabel(lamp: Int, activity: Int) =
            UiText.get(R.string.headlight_activity_summary).format(UiText.get(lamp), UiText.get(activity))
        try {
            UiText.language("ru")
            check(preferences.edit().clear().commit())
            monitoring.restoreSettings(JSONObject().put("whileControlsOpen", false))
            val activity = launch(MainActivity.settingsIntent(app)) as MainActivity
            instrumentation.runOnMainSync {
                val fixture = VehicleSettingsViewModel({ false }, HeadlightMonitor(events, history))
                val installed = ViewModelProvider(activity, viewModelFactory { initializer { fixture } })[
                    VEHICLE_SETTINGS_MODEL_KEY, VehicleSettingsViewModel::class.java
                ]
                assertSame("Install the fixture before a production read is started", fixture, installed)
                ViewModelProvider(activity)[ControlsNavigationViewModel::class.java].open(ControlsRoute.VEHICLE)
            }
            awaitRoute(activity, ControlsRoute.VEHICLE)
            runBlocking { withTimeout(5_000) { events.subscriptionCount.first { it == 1 } } }
            val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(Locale.forLanguageTag(UiText.language())).withZone(ZoneId.systemDefault())
            fun reportTime(resource: Int, atMillis: Long): String =
                UiText.get(resource).format(formatter.format(Instant.ofEpochMilli(atMillis)))
            val selectorLabel = UiText.get(R.string.headlight_selector_label)
            val initialTime = time - 240_000
            val initialHeadlights = initialTime - 60_000
            val initialHighBeam = initialTime - 120_000
            val earlier = R.string.headlight_mode_earlier_report
            val initialSelectorRows = listOf(selectorLabel, UiText.get(R.string.headlight_mode_off), reportTime(earlier, initialTime))
            val initialHeadlightsRows = listOf(activityLabel(R.string.headlight_activity_main, R.string.headlight_activity_off),
                reportTime(earlier, initialHeadlights))
            send(HeadlightFeedback.EarlierSelectorReport(EarlierHeadlightReport(HeadlightMode.OFF, initialTime)))
            send(HeadlightFeedback.EarlierLampReport(ExteriorLamp.HEADLIGHTS, EarlierHeadlightReport(LampActivity.OFF, initialHeadlights)))
            awaitHeadlightViewport("missing-reading", initialSelectorRows + initialHeadlightsRows +
                activityLabel(R.string.headlight_activity_high, R.string.headlight_activity_unavailable))
            send(HeadlightFeedback.EarlierLampReport(ExteriorLamp.HIGH_BEAM, EarlierHeadlightReport(LampActivity.ON, initialHighBeam)))
            awaitHeadlightViewport("earlier-readings", initialSelectorRows + initialHeadlightsRows + listOf(
                activityLabel(R.string.headlight_activity_high, R.string.headlight_activity_on), reportTime(earlier, initialHighBeam)))
            assertNull(textNode(reportTime(R.string.headlight_mode_last_confirmed, initialTime)))
            assertTrue(preferences.all.isEmpty())
            capture("headlight-initial-reading")
            send(HeadlightFeedback.Confirmed(selector, SystemClock.elapsedRealtimeNanos()))
            send(HeadlightFeedback.ActivityConfirmed(ExteriorLamp.HEADLIGHTS, headlights, SystemClock.elapsedRealtimeNanos()))
            send(HeadlightFeedback.ActivityConfirmed(ExteriorLamp.HIGH_BEAM, highBeam, SystemClock.elapsedRealtimeNanos()))
            val on = activityLabel(R.string.headlight_activity_main, R.string.headlight_activity_on)
            val highOff = activityLabel(R.string.headlight_activity_high, R.string.headlight_activity_off)
            val confirmed = R.string.headlight_mode_confirmed
            awaitHeadlightViewport("confirmed-readings", listOf(selectorLabel, UiText.get(R.string.headlight_mode_automatic),
                reportTime(confirmed, selector.confirmedAtMillis), on, reportTime(confirmed, headlights.confirmedAtMillis),
                highOff, reportTime(confirmed, highBeam.confirmedAtMillis)))
            capture("headlight-activity-live-fixture")

            send(HeadlightFeedback.SelectorUnavailable)
            scrollTo(UiText.get(R.string.headlight_selector_unavailable), towardsStart = true)
            awaitText(UiText.get(R.string.headlight_selector_unavailable))
            awaitText(on)
            send(HeadlightFeedback.Unavailable)
            for (confirmedAtMillis in listOf(selector.confirmedAtMillis, headlights.confirmedAtMillis, highBeam.confirmedAtMillis)) {
                val timestamp = formatter.format(Instant.ofEpochMilli(confirmedAtMillis))
                val historical = UiText.get(R.string.headlight_mode_last_confirmed).format(timestamp)
                scrollTo(historical)
                awaitText(historical)
                assertNull(textNode(UiText.get(R.string.headlight_mode_confirmed).format(timestamp)))
            }
            capture("headlight-activity-history-fixture")
            assertEquals(selector, LocalHeadlightHistory(app).read())
            assertEquals(mapOf(ExteriorLamp.HEADLIGHTS to headlights, ExteriorLamp.HIGH_BEAM to highBeam),
                LocalHeadlightHistory(app).readLamps())
            assertEquals(1, JSONObject(LocalSettingsArchive(app).exportJson()).getJSONObject("headlightMonitoring").length())
        } finally {
            UiText.language(previousLanguage)
            restoreHeadlightTestHistory(previous)
            monitoring.restoreSettings(previousMonitoring)
        }
    }

    private fun awaitHeadlightViewport(stage: String, expectedReadings: List<String>) {
        val pageTitle = UiText.get(R.string.vehicle_settings_title)
        val expected = expectedReadings + UiText.get(R.string.headlight_mode_title)
        val margin = kotlin.math.ceil(app.resources.displayMetrics.density).toInt()
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (true) {
            // nodes() already refreshes all virtual nodes, including their parents on Android 9.
            val visible = nodes().filter { it.isVisibleToUser }.toList()
            val vehicleVisible = visible.any { it.text?.toString() == pageTitle }
            val viewport = visible.filter { it.className?.toString() == "android.widget.ScrollView" }
                .map { Rect().also(it::getBoundsInScreen) }
                .maxByOrNull { it.width().toLong() * it.height() }
                ?.apply { inset(margin, margin) }
            val bounds = visible.filter { it.text?.toString() in expected }.groupBy(
                keySelector = { it.text.toString() }, valueTransform = { Rect().also(it::getBoundsInScreen) })
            val outside = expected.filter { label ->
                val area = bounds[label]?.singleOrNull()
                area == null || area.isEmpty || viewport?.contains(area) != true
            }
            if (vehicleVisible && outside.isEmpty()) return
            if (SystemClock.uptimeMillis() >= deadline) {
                failWithEvidence("headlight-viewport-$stage", "Expected each heading, reading and timestamp once inside the initial " +
                    "Vehicle viewport $viewport. Missing or clipped rows $outside. Observed bounds $bounds.")
            }
            SystemClock.sleep(100)
        }
    }

    fun testHeadlightSourcesShowsUnavailableReadersAndCopiesConfirmedHistory() {
        val preferences = app.getSharedPreferences(LocalHeadlightHistory.STORE, 0)
        val previousHistory = preferences.all
        val monitoring = HeadlightMonitoringPreferences(app)
        val previousMonitoring = monitoring.exportSettings()
        val previousLanguage = UiText.language()
        val clipboard = checkNotNull(app.getSystemService(ClipboardManager::class.java))
        var previousClip: ClipData? = null
        var copied = false
        val saved = HeadlightObservation(HeadlightMode.POSITION, 1_700_000_000_000, HeadlightSource.FACTORY)
        try {
            UiText.language("ru")
            check(preferences.edit().clear().commit())
            check(LocalHeadlightHistory(app).save(saved))
            monitoring.restoreSettings(JSONObject().put("whileControlsOpen", false))
            val activity = launch(ControlsNavigation.intent(app, ControlsRoute.VEHICLE)) as MainActivity
            awaitRoute(activity, ControlsRoute.VEHICLE)
            val title = UiText.get(R.string.headlight_sources_title)
            scrollTo(title)
            clickText(title)
            awaitText(UiText.get(R.string.headlight_sources_model_unreviewed))
            awaitText(UiText.get(R.string.headlight_sources_platform_unavailable))
            scrollTo(UiText.get(R.string.headlight_sources_android_high_beam))
            val source = UiText.get(R.string.headlight_activity_summary).format(
                UiText.get(R.string.headlight_selector_label), UiText.get(R.string.headlight_sources_factory),
            )
            scrollTo(source)
            awaitText(source)
            capture("headlight-sources")

            instrumentation.runOnMainSync { previousClip = clipboard.primaryClip }
            clickText(UiText.get(R.string.headlight_sources_copy))
            copied = true
            scrollTo(UiText.get(R.string.headlight_sources_copied))
            var payload = ""
            instrumentation.runOnMainSync { payload = clipboard.primaryClip?.getItemAt(0)?.text.toString() }
            val report = JSONObject(payload)
            assertEquals(2, report.getInt("report_version"))
            assertTrue(report.getLong("captured_at_ms") > saved.confirmedAtMillis)
            assertEquals(Build.VERSION.SDK_INT, report.getInt("android_api"))
            assertEquals(Build.PRODUCT, report.getString("android_product"))
            val automotive = report.getJSONObject("android_automotive")
            assertFalse(automotive.getBoolean("advertised"))
            val permissions = automotive.getJSONObject("permissions")
            assertEquals(3, permissions.length())
            for (permission in permissions.keys()) assertFalse(permissions.getBoolean(permission))
            val packages = report.getJSONObject("factory_packages")
            assertEquals(setOf("com.qinggan.canbus.service", "com.qinggan.app.vehicle", "com.qinggan.app.vehiclesetting"),
                packages.keys().asSequence().toSet())
            for (name in packages.keys()) assertEquals("not_visible_or_absent", packages.getJSONObject(name).getString("status"))
            val entryPoints = report.getJSONObject("factory_entry_points")
            for (entry in listOf("lighting", "seats")) assertEquals("unavailable", entryPoints.getJSONObject(entry).getString("status"))
            assertEquals(0, report.getJSONObject("current").length())
            assertEquals(0, report.getJSONObject("earlier_android_reports").length())
            val inputs = report.getJSONObject("lighting_inputs")
            assertEquals("model_unreviewed", inputs.getString("factory_selector"))
            for (input in listOf("android_selector", "android_headlamps", "android_high_beam")) {
                assertEquals("platform_unavailable", inputs.getString(input))
            }
            val history = report.getJSONObject("last_confirmed").getJSONObject("selector")
            assertEquals(saved.mode.key, history.getString("value"))
            assertEquals(saved.confirmedAtMillis, history.getLong("confirmed_at_ms"))
            assertEquals(saved.source?.key, history.getString("source"))
            clickText(UiText.get(R.string.headlight_sources_done))
            awaitRoute(activity, ControlsRoute.VEHICLE)
        } finally {
            if (copied) instrumentation.runOnMainSync {
                previousClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
            }
            UiText.language(previousLanguage)
            restoreHeadlightTestHistory(previousHistory)
            monitoring.restoreSettings(previousMonitoring)
        }
    }

    fun testVehicleLightingAndSeatMemory() {
        val history = LocalHeadlightHistory(app)
        val historyPreferences = app.getSharedPreferences(LocalHeadlightHistory.STORE, 0)
        val previousHistory = historyPreferences.all
        val monitoring = HeadlightMonitoringPreferences(app)
        val previousMonitoring = monitoring.exportSettings()
        val seats = SeatMemoryPreferences(app)
        val previousSeats = seats.exportSettings()
        val appearance = AppearancePreferences(app)
        val previousAppearance = appearance.exportSettings()
        val previousLanguage = UiText.language()
        try {
            UiText.language("ru")
            appearance.restoreSettings(JSONObject().put("textSize", "standard").put("spacing", "standard")
                .put("pageTitleSize", "follow").put("fontFamily", "system").put("boldText", false))
            check(historyPreferences.edit().clear().commit())
            monitoring.restoreSettings(JSONObject().put("whileControlsOpen", false))
            seats.restoreSettings(JSONObject("""{"selectedSlot":1,"slotNames":["","Legacy name",""],"openFactorySeatsOnTap":false}"""))
            val activity = launch(ControlsNavigation.intent(app, ControlsRoute.VEHICLE)) as MainActivity
            val unavailable = UiText.get(R.string.headlight_mode_unavailable)
            awaitText(unavailable)
            assertNull(textNode(UiText.get(R.string.headlight_mode_automatic)))
            capture("vehicle-lighting")

            val seatMemory = UiText.get(R.string.vehicle_seat_memory)
            scrollTo(seatMemory)
            clickText(seatMemory)
            awaitRoute(activity, ControlsRoute.SEAT_MEMORY)
            for (index in 1..3) awaitText(UiText.get(R.string.seat_memory_slot).format(index))
            assertNull(textNode("Legacy name"))
            assertDriverCardsInColumns()
            capture("seat-memory")

            val factoryPath = UiText.get(R.string.seat_memory_factory_path)
            fun chooseDriverAction(number: Int, action: Int, guidance: Int) {
                val driver = UiText.get(R.string.seat_memory_slot).format(number)
                clickText(UiText.get(action).format(driver), contentDescription = true)
                awaitText(factoryPath)
                awaitText(UiText.get(guidance).format(number))
                if (number == 1 && action == R.string.seat_memory_save_driver) capture("seat-memory-save-guide")
                clickText(UiText.get(R.string.seat_memory_close))
            }
            for (slot in SeatMemorySlot.entries) {
                chooseDriverAction(slot.number, R.string.seat_memory_save_driver, R.string.seat_memory_factory_save)
                assertEquals(slot, seats.load().selectedSlot)
                chooseDriverAction(slot.number, R.string.seat_memory_restore_driver, R.string.seat_memory_factory_restore)
                assertEquals(slot, seats.load().selectedSlot)
            }
            val reopened = SeatMemoryPreferences(app).load()
            assertEquals(SeatMemorySlot.THREE, reopened.selectedSlot)
            assertEquals("Legacy name", reopened.slotNames[SeatMemorySlot.TWO])
            assertFalse(reopened.openFactorySeatsOnTap)
            val directSeats = UiText.get(R.string.seat_memory_open_factory_on_tap)
            scrollTo(directSeats)
            clickText(directSeats)
            val seatDeadline = SystemClock.uptimeMillis() + 5_000
            while (!seats.load().openFactorySeatsOnTap && SystemClock.uptimeMillis() < seatDeadline) SystemClock.sleep(50)
            assertTrue(SeatMemoryPreferences(app).load().openFactorySeatsOnTap)
            scrollTo(UiText.get(R.string.seat_memory_slot).format(3), towardsStart = true)
            chooseDriverAction(3, R.string.seat_memory_restore_driver, R.string.seat_memory_factory_restore)

            val recorded = HeadlightObservation(HeadlightMode.POSITION, System.currentTimeMillis() - 60_000L)
            check(history.save(recorded))
            clickText(UiText.get(R.string.cockpit_back))
            awaitRoute(activity, ControlsRoute.VEHICLE)
            scrollTo(unavailable, towardsStart = true)
            awaitText(unavailable)
            awaitText(UiText.get(R.string.headlight_mode_position))
            val historicalLabel = UiText.get(R.string.headlight_mode_last_confirmed).substringBefore('%')
            assertTrue(nodes().any { it.isVisibleToUser && it.text?.toString()?.startsWith(historicalLabel) == true })
            assertEquals(recorded, LocalHeadlightHistory(app).read())

            val voiceUi = VoiceUiActions(activity, { "" }, split = { false }, events = UiText.displayEvents(app))
            for (slot in listOf(SeatMemorySlot.TWO, SeatMemorySlot.ONE)) {
                val deadline = SystemClock.uptimeMillis() + 5_000
                var result: VoiceResult? = null
                while (result == null && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        if (activity.hasWindowFocus() && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            result = voiceUi.openDriverMemory(slot)
                        }
                    }
                    if (result == null) SystemClock.sleep(50)
                }
                assertEquals("Could not open the foreground voice entry for driver $slot", VoiceOutcome.LOCAL_REQUESTED, result?.outcome)
                assertEquals(UiText.get(R.string.seat_memory_voice_opening).format(Locale.ROOT, slot.number), result?.message)
                awaitRoute(activity, ControlsRoute.SEAT_MEMORY)
                awaitText(factoryPath)
                awaitText(UiText.get(R.string.seat_memory_factory_restore).format(slot.number))
                if (slot == SeatMemorySlot.TWO) capture("seat-memory-voice-guide")
                clickText(UiText.get(R.string.seat_memory_close))
                assertEquals(SeatMemorySlot.THREE, seats.load().selectedSlot)
            }
        } finally {
            UiText.language(previousLanguage)
            appearance.restoreSettings(previousAppearance)
            seats.restoreSettings(previousSeats)
            restoreHeadlightTestHistory(previousHistory)
            monitoring.restoreSettings(previousMonitoring)
        }
    }

    fun testNumberedSeatGuideSurvivesRecreationAndReturnsToThePriorScreen() {
        val activity = launch(ControlsNavigation.intent(app, ControlsRoute.VEHICLE)) as MainActivity
        val guidanceTwo = UiText.get(R.string.seat_memory_factory_restore).format(Locale.ROOT, 2)
        instrumentation.runOnMainSync {
            activity.startActivity(ControlsNavigation.driverMemoryIntent(activity, SeatMemorySlot.TWO))
        }
        awaitRoute(activity, ControlsRoute.SEAT_MEMORY)
        awaitText(guidanceTwo)

        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        val recreated = try {
            instrumentation.runOnMainSync { activity.recreate() }
            checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5_000)) as MainActivity
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        active = recreated
        awaitText(guidanceTwo)

        val guidanceOne = UiText.get(R.string.seat_memory_factory_restore).format(Locale.ROOT, 1)
        instrumentation.runOnMainSync {
            recreated.startActivity(ControlsNavigation.driverMemoryIntent(recreated, SeatMemorySlot.ONE))
        }
        awaitText(guidanceOne)
        assertNull(textNode(guidanceTwo))

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        val driver = UiText.get(R.string.seat_memory_slot).format(Locale.ROOT, 1)
        awaitText(driver)
        awaitRoute(recreated, ControlsRoute.SEAT_MEMORY)
        assertNull(textNode(guidanceOne))

        clickText(UiText.get(R.string.cockpit_back))
        awaitRoute(recreated, ControlsRoute.VEHICLE)
        val seatMemory = UiText.get(R.string.vehicle_seat_memory)
        scrollTo(seatMemory)
        clickText(seatMemory)
        awaitRoute(recreated, ControlsRoute.SEAT_MEMORY)
        awaitText(driver)
        assertNull(textNode(guidanceOne))
    }

    fun testManualSeatGuideSurvivesRecreationAndClearsOnRouteExit() {
        val seats = SeatMemoryPreferences(app)
        val previous = seats.exportSettings()
        try {
            seats.setOpenFactorySeatsOnTap(false)
            val activity = launch(ControlsNavigation.intent(app, ControlsRoute.VEHICLE)) as MainActivity
            val seatMemory = UiText.get(R.string.vehicle_seat_memory)
            scrollTo(seatMemory)
            clickText(seatMemory)
            awaitRoute(activity, ControlsRoute.SEAT_MEMORY)

            val driverTwo = UiText.get(R.string.seat_memory_slot).format(Locale.ROOT, 2)
            val saveTwo = UiText.get(R.string.seat_memory_factory_save).format(Locale.ROOT, 2)
            clickText(UiText.get(R.string.seat_memory_save_driver).format(Locale.ROOT, driverTwo), contentDescription = true)
            awaitText(saveTwo)

            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            val recreated = try {
                instrumentation.runOnMainSync { activity.recreate() }
                checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5_000)) as MainActivity
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            active = recreated
            awaitText(saveTwo)
            val automation = instrumentation.uiAutomation
            val settingsPackage = "com.android.settings"
            instrumentation.runOnMainSync {
                recreated.startActivity(Intent(Settings.ACTION_SETTINGS).setPackage(settingsPackage))
            }
            val settingsDeadline = SystemClock.uptimeMillis() + 5_000
            while (automation.rootInActiveWindow?.packageName != settingsPackage && SystemClock.uptimeMillis() < settingsDeadline) {
                SystemClock.sleep(60)
            }
            assertEquals(settingsPackage, automation.rootInActiveWindow?.packageName)
            runBlocking {
                withTimeout(5_000) { recreated.lifecycle.currentStateFlow.first { !it.isAtLeast(Lifecycle.State.RESUMED) } }
            }
            backFromExternalApp()
            awaitText(saveTwo)
            capture("seat-memory-manual-return")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitText(driverTwo)
            assertNull(textNode(saveTwo))

            val driverThree = UiText.get(R.string.seat_memory_slot).format(Locale.ROOT, 3)
            val restoreThree = UiText.get(R.string.seat_memory_factory_restore).format(Locale.ROOT, 3)
            val restoreButton = UiText.get(R.string.seat_memory_restore_driver).format(Locale.ROOT, driverThree)
            clickText(restoreButton, contentDescription = true)
            awaitText(restoreThree)
            instrumentation.runOnMainSync {
                val navigation = ViewModelProvider(recreated)[ControlsNavigationViewModel::class.java]
                navigation.open(ControlsRoute.VEHICLE)
                navigation.open(ControlsRoute.SEAT_MEMORY)
            }
            awaitRoute(recreated, ControlsRoute.SEAT_MEMORY)
            awaitText(driverThree)
            assertNull(textNode(restoreThree))
            assertEquals(SeatMemorySlot.THREE, seats.load().selectedSlot)
        } finally {
            seats.restoreSettings(previous)
        }
    }

    private fun restoreHeadlightTestHistory(values: Map<String, *>) {
        val editor = app.getSharedPreferences(LocalHeadlightHistory.STORE, 0).edit().clear()
        for ((key, value) in values) {
            when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                else -> error("Unexpected headlight history type")
            }
        }
        check(editor.commit())
    }

    fun testServiceReminderFromVehicle() {
        val store = ServiceReminderPreferences(app)
        val previous = store.exportSettings()
        val previousLanguage = UiText.language()
        try {
            UiText.language("ru")
            store.restoreSettings(JSONObject("""{"enabled":false,"intervalKm":null,"currentKm":null,"lastServiceKm":null,
                "leadDistanceKm":null,"calendarIntervalMonths":null,"lastServiceDate":null}"""))
            val activity = launch(ControlsNavigation.intent(app, ControlsRoute.VEHICLE)) as MainActivity
            val reminder = UiText.get(R.string.service_reminder_title)
            scrollTo(reminder)
            clickText(reminder)
            awaitRoute(activity, ControlsRoute.SERVICE_REMINDER)
            awaitText(UiText.get(R.string.service_reminder_missing_readings))

            enterServiceField(R.string.service_reminder_interval, "10000")
            enterServiceField(R.string.service_reminder_current, "69750")
            enterServiceField(R.string.service_reminder_last, "60000")
            val save = UiText.get(R.string.service_reminder_save)
            scrollTo(save)
            clickText(save)
            val dueSoon = UiText.get(R.string.service_reminder_remaining).format("250")
            scrollTo(dueSoon)
            assertFalse(store.exportSettings().getBoolean("enabled"))
            assertEquals(69_750, store.exportSettings().getInt("currentKm"))

            val show = UiText.get(R.string.service_reminder_show_on_vehicle)
            clickText(show)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!store.exportSettings().getBoolean("enabled") && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(75)
            assertTrue(store.exportSettings().getBoolean("enabled"))
            capture("service-reminder")
            clickText(UiText.get(R.string.cockpit_back))
            awaitRoute(activity, ControlsRoute.VEHICLE)
            scrollTo(dueSoon)
            capture("vehicle-service-reminder")
        } finally {
            UiText.language(previousLanguage)
            store.restoreSettings(previous)
        }
    }

    fun testServiceReminderLeadDistanceUpdatesVehicleBadge() {
        val store = ServiceReminderPreferences(app)
        val previous = store.exportSettings()
        val previousLanguage = UiText.language()
        try {
            UiText.language("ru")
            store.restoreSettings(JSONObject("""{"enabled":true,"intervalKm":450,"currentKm":10075,"lastServiceKm":10000,
                "leadDistanceKm":null,"calendarIntervalMonths":null,"lastServiceDate":null}"""))
            val activity = launch(ControlsNavigation.intent(app, ControlsRoute.VEHICLE)) as MainActivity
            val reminder = UiText.get(R.string.service_reminder_title)
            val leadChoice = UiText.get(R.string.service_reminder_lead_title)
            val dueSoon = UiText.get(R.string.service_reminder_remaining).format("375")
            fun openReminder() {
                scrollTo(reminder)
                clickText(reminder)
                awaitRoute(activity, ControlsRoute.SERVICE_REMINDER)
            }
            fun returnToVehicle() {
                clickText(UiText.get(R.string.cockpit_back))
                awaitRoute(activity, ControlsRoute.VEHICLE)
            }
            fun choice(km: Int) = UiText.get(R.string.service_reminder_distance).format(km.toString())
            fun choose(km: Int) {
                scrollTo(leadChoice)
                clickText(leadChoice)
                clickText(choice(km))
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (SystemClock.uptimeMillis() < deadline) {
                    if (store.exportSettings().optInt("leadDistanceKm") == km) return
                    SystemClock.sleep(50)
                }
                failWithEvidence("service-reminder-lead-save", "The selected reminder distance $km km did not persist")
            }

            scrollTo(reminder)
            assertNull("The automatic 45 km threshold should leave the badge hidden at 375 km", textNode(dueSoon))
            openReminder()
            scrollTo(UiText.get(R.string.service_reminder_lead_auto_distance).format("45"))
            choose(500)
            val limited = UiText.get(R.string.service_reminder_lead_limited).format(choice(500), choice(450))
            scrollTo(limited)
            capture("service-reminder-lead")

            returnToVehicle()
            scrollTo(dueSoon)
            openReminder()
            scrollTo(limited)
            choose(250)
            scrollTo(choice(250))
            returnToVehicle()
            scrollTo(reminder)
            assertNull("The 250 km threshold should leave the badge hidden at 375 km", textNode(dueSoon))
            val saved = store.exportSettings()
            assertTrue(saved.getBoolean("enabled"))
            assertEquals(450, saved.getInt("intervalKm"))
            assertEquals(10_075, saved.getInt("currentKm"))
            assertEquals(10_000, saved.getInt("lastServiceKm"))
        } finally {
            UiText.language(previousLanguage)
            store.restoreSettings(previous)
        }
    }

    fun testServiceReminderCalendarScheduleAndLeadUpdateVehicleBadge() {
        val store = ServiceReminderPreferences(app)
        val previous = store.exportSettings()
        val previousLanguage = UiText.language()
        try {
            UiText.language("en")
            store.restoreSettings(JSONObject("""{"enabled":true,"intervalKm":10000,"currentKm":60010,"lastServiceKm":60000,
                "leadDistanceKm":null,"calendarIntervalMonths":null,"lastServiceDate":null,"calendarLeadDays":0}"""))
            val record = LocalDate.now().plusDays(5).minusMonths(12)
            val due = record.plusMonths(12).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.ENGLISH))
            val notice = UiText.get(R.string.service_reminder_calendar_due_date).format(due)
            val reminder = UiText.get(R.string.service_reminder_title)
            val activity = launch(ControlsNavigation.intent(app, ControlsRoute.VEHICLE)) as MainActivity
            fun openReminder() {
                scrollTo(reminder)
                clickText(reminder)
                awaitRoute(activity, ControlsRoute.SERVICE_REMINDER)
            }
            fun returnToVehicle() {
                clickText(UiText.get(R.string.cockpit_back))
                awaitRoute(activity, ControlsRoute.VEHICLE)
            }

            openReminder()
            enterServiceField(R.string.service_reminder_calendar_interval, "12")
            enterServiceField(R.string.service_reminder_calendar_last_date, record.toString())
            val save = UiText.get(R.string.service_reminder_calendar_save)
            scrollTo(save)
            clickText(save)
            scrollTo(notice)
            val calendar = store.exportSettings()
            assertEquals(12, calendar.getInt("calendarIntervalMonths"))
            assertEquals(record.toString(), calendar.getString("lastServiceDate"))

            returnToVehicle()
            scrollTo(reminder)
            assertNull("A due-date-only reminder should stay hidden before the due date", textNode(notice))
            openReminder()
            val lead = UiText.get(R.string.service_reminder_calendar_lead_title)
            scrollTo(lead)
            clickText(lead)
            clickText(UiText.get(R.string.service_reminder_calendar_lead_days).format(7))
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (store.exportSettings().getInt("calendarLeadDays") != 7 && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50)
            }
            assertEquals(7, store.exportSettings().getInt("calendarLeadDays"))
            returnToVehicle()
            scrollTo(notice)
            capture("vehicle-service-calendar-reminder")
        } finally {
            UiText.language(previousLanguage)
            store.restoreSettings(previous)
        }
    }

    private fun enterServiceField(resource: Int, value: String) {
        val label = UiText.get(resource)
        scrollTo(label)
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val bounds = textNode(label)?.let { Rect().also(it::getBoundsInScreen) }
            val field = bounds?.let { location ->
                nodes().firstOrNull { node ->
                    if (!node.isVisibleToUser || !node.isEditable || !node.refresh()) return@firstOrNull false
                    val area = Rect().also(node::getBoundsInScreen)
                    location.centerX() in area.left until area.right && location.bottom in area.top..area.bottom
                }
            }
            if (field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }) == true) return
            SystemClock.sleep(50)
        }
        failWithEvidence("service-field-$resource", "The service field $label did not accept input")
    }

    private fun assertDriverCardsInColumns() {
        if (!settledTree(SystemClock.uptimeMillis() + 5_000L).settled) {
            failWithEvidence("seat-memory-layout", "The driver cards did not settle")
        }
        data class Element(val text: String?, val description: String?, val bounds: Rect)
        data class Card(val region: Rect, val chair: Rect)
        val density = app.resources.displayMetrics.density
        val touchTarget = density * 48

        val visible = nodes().filter { it.isVisibleToUser }.map { node ->
            Element(node.text?.toString(), node.contentDescription?.toString(), Rect().also(node::getBoundsInScreen))
        }.toList()
        fun bounds(name: String, description: Boolean = false): Rect {
            val matching = visible.filter { (if (description) it.description else it.text) == name }
            val element = matching.singleOrNull()
            if (element == null || element.bounds.isEmpty) {
                failWithEvidence("seat-memory-layout", "Expected one visible element for $name. Found $matching")
            }
            return element.bounds
        }

        val cards = (1..3).map { number ->
            val driver = UiText.get(R.string.seat_memory_slot).format(number)
            val label = bounds(driver)
            val chair = bounds(UiText.get(R.string.seat_memory_driver_seat).format(driver), description = true)
            val restore = bounds(UiText.get(R.string.seat_memory_restore_driver).format(driver), description = true)
            val save = bounds(UiText.get(R.string.seat_memory_save_driver).format(driver), description = true)

            assertTrue("$driver needs the chair between its label and actions. Label $label, chair $chair, actions $restore and $save",
                label.bottom <= chair.top && chair.bottom <= minOf(restore.top, save.top))
            assertTrue("$driver needs a full-size illustration. Bounds $chair", chair.height() >= density * 140)
            assertTrue("$driver needs separate Restore and Save buttons on the same row. Restore $restore, Save $save",
                restore.right <= save.left && maxOf(restore.top, save.top) < minOf(restore.bottom, save.bottom))
            for (button in listOf(restore, save)) {
                assertTrue("$driver needs a fully visible touch target. Bounds $button, minimum $touchTarget",
                    button.width() >= touchTarget && button.height() >= touchTarget)
            }
            assertTrue("$driver needs the chair above its own actions. Chair $chair, actions $restore and $save",
                chair.centerX() in restore.left..save.right)

            val region = Rect(label).apply {
                union(chair)
                union(restore)
                union(save)
            }
            Card(region, chair)
        }
        assertTrue("Driver 1, 2 and 3 must occupy separate columns from left to right. Found $cards",
            cards.zipWithNext().all { (left, right) -> left.region.right <= right.region.left })
        assertTrue("All three chairs must share the same horizontal row. Found $cards",
            cards.maxOf { it.chair.top } < cards.minOf { it.chair.bottom })
    }

    private fun awaitRoute(activity: MainActivity, route: ControlsRoute) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var current: ControlsRoute? = null
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                current = ViewModelProvider(activity)[ControlsNavigationViewModel::class.java].state.value.route
            }
            if (current == route) return
            SystemClock.sleep(50)
        }
        failWithEvidence("route-${route.name}", "The existing host stayed at $current instead of $route")
    }

    private fun backFromExternalApp() {
        val automation = instrumentation.uiAutomation
        val backAt = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(backAt, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0)
            assertTrue(automation.injectInputEvent(event, true))
        }
    }

    private fun assertAppGrid(activity: MainActivity) {
        awaitRoute(activity, ControlsRoute.APPS)
        awaitText(UiText.get(R.string.app_name))
        val deadline = SystemClock.uptimeMillis() + 3_000
        while (textNode(UiText.get(R.string.cockpit_back)) != null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
        }
        assertNull("The app grid root must not show Back", textNode(UiText.get(R.string.cockpit_back)))
        assertFalse("The original normal host was replaced", activity.isFinishing || activity.isDestroyed)
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
    private fun nodes(openedAfter: Set<Int>? = null): Sequence<AccessibilityNodeInfo> {
        fun walk(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            // Refresh the parent before reading its child IDs. A cached subtree can retain the old
            // scroll position; refreshing only text matches cannot discover newly visible virtual nodes.
            if (!node.refresh()) return@sequence
            yield(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(walk(it)) }
        }
        val automation = instrumentation.uiAutomation
        val root = if (openedAfter == null) {
            automation.windows.firstOrNull { it.isActive }?.root ?: automation.rootInActiveWindow
        } else {
            automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.id !in openedAfter }
                .maxByOrNull { it.layer }?.root
        } ?: return emptySequence()
        if (!root.refresh()) return emptySequence()
        return walk(root)
    }

    private fun textNode(text: String, openedAfter: Set<Int>? = null): AccessibilityNodeInfo? = nodes(openedAfter)
        .firstOrNull { it.isVisibleToUser && it.text?.toString() == text && it.refresh() &&
            it.isVisibleToUser && it.text?.toString() == text }

    private fun anyTextNode(text: String): AccessibilityNodeInfo? = nodes()
        .firstOrNull { it.text?.toString() == text && it.refresh() && it.text?.toString() == text }

    private fun awaitText(text: String, timeout: Long = 5_000) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (textNode(text) == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        if (textNode(text) == null) failWithEvidence("missing-$text", "Visible text missing: $text")
    }

    private fun awaitSelectedTab(text: String, contentDescription: Boolean = false) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val selected = nodes().any { label ->
                val name = if (contentDescription) label.contentDescription else label.text
                label.isVisibleToUser && name?.toString() == text &&
                    generateSequence(label) { it.parent }.any { it.refresh() && it.isVisibleToUser && it.isSelected }
            }
            if (selected) return
            SystemClock.sleep(70)
        }
        failWithEvidence("desktop-tab-selection", "The desktop tab $text did not become selected")
    }

    private fun tapCurrentText(text: String) {
        val bounds = Rect().also(checkNotNull(textNode(text))::getBoundsInScreen)
        val downAt = SystemClock.uptimeMillis()
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun clickText(
        text: String, longPress: Boolean = false, contentDescription: Boolean = false, openedAfter: Set<Int>? = null,
    ) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        val observations = ArrayDeque<String>()
        val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        while (SystemClock.uptimeMillis() < deadline) {
            // Scroll/page transitions can replace virtual nodes after their text first becomes visible.
            // Reacquire the current tree for every attempt and require an enabled advertised click action.
            var node = if (contentDescription) {
                nodes(openedAfter).firstOrNull { it.isVisibleToUser && it.contentDescription?.toString() == text }
            } else textNode(text, openedAfter)
            val chain = mutableListOf<String>()
            var depth = 0
            while (node != null && depth++ < 16) {
                if (!node.refresh()) { chain += "stale node"; break }
                chain += describe(node)
                val clickable = node.isEnabled && node.isVisibleToUser && node.actionList.any { it.id == action }
                if (clickable) {
                    if (node.performAction(action)) {
                        instrumentation.waitForIdleSync()
                        return
                    }
                    chain += "Accessibility action returned false"
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

    private fun scrollTo(text: String, towardsStart: Boolean = false) {
        // One step scrolls a viewport and takes about 1.3 s to settle. The Settings page grew by a row of links with
        // the Diagnostic log, which put its last sections one step beyond what the former 8 s allowed at 1920 x 720.
        val deadline = SystemClock.uptimeMillis() + 14_000
        val observations = ArrayDeque<String>()
        val visited = mutableSetOf<Pair<Int, String>>()
        val showAttempts = mutableSetOf<String>()
        val edges = mutableSetOf<Int>()
        var direction = if (towardsStart) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        fun note(value: String) {
            if (observations.size == 12) observations.removeFirst()
            observations.addLast(value)
        }
        while (SystemClock.uptimeMillis() < deadline) {
            val before = settledTree(deadline)
            if (textNode(text) != null) return
            if (!before.settled) { note("tree still moving"); continue }

            val show = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
            val published = anyTextNode(text)
            if (published?.actionList?.any { it.id == show } == true && showAttempts.add(before.signature) && published.performAction(show)) {
                val shown = settledTree(deadline, before.signature)
                note("SHOW_ON_SCREEN changed=${shown.signature != before.signature} settled=${shown.settled}")
                if (textNode(text) != null) return
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
            if (textNode(text) != null) return
            if (!after.settled) continue
            val progressed = acted && after.signature != before.signature && visited.add(direction to after.signature)
            if (!progressed) {
                edges += direction
                if (edges.size == 2) break
                direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
        }
        if (textNode(text) != null) return
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
        val output = File(app.filesDir, "ui-audit").apply { check(isDirectory || mkdirs()) }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
