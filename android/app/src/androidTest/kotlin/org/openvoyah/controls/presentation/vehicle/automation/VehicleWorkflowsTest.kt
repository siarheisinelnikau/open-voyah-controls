package org.openvoyah.controls.presentation.vehicle.automation

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.MainActivity
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.climate.LocalSeasonalClimatePreferences
import org.openvoyah.controls.data.dock.QuickActionPreferences
import org.openvoyah.controls.data.routines.RoutinePreferences
import org.openvoyah.controls.data.rules.ConditionalRulesPreferences
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.data.vehicle.automation.LocalVehicleAutomationPreferences
import org.openvoyah.controls.domain.rules.ConditionalRuleLibrary
import org.openvoyah.controls.domain.rules.DoorRequirement
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.routines.RoutineLibrary
import org.openvoyah.controls.domain.routines.RoutinePlan
import org.openvoyah.controls.domain.routines.RoutineTarget
import org.openvoyah.controls.domain.vehicle.automation.HeadlightGearAction
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsNavigationViewModel
import org.openvoyah.controls.presentation.navigation.ControlsRoute

/** Bounded real-app interactions. Run only on the explicitly selected 1920 × 720 test emulator. */
class VehicleWorkflowsTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext
    private var active: MainActivity? = null
    private var previousStores: Map<String, Map<String, *>> = emptyMap()
    private var previousLanguage: String? = null
    private var previousClipboard: ClipData? = null

    override fun setUp() {
        super.setUp()
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val stores = listOf("updates", "ui", AppearancePreferences.STORE, QuickActionPreferences.STORE,
            RoutinePreferences.STORE, LocalVehicleAutomationPreferences.STORE, LocalSeasonalClimatePreferences.STORE,
            ConditionalRulesPreferences.STORE)
        previousStores = stores.associateWith { app.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
        previousLanguage = UiText.language()
        check(app.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putBoolean("check_on_startup", false).commit())
        UiText.language("en")
        AppearancePreferences(app).restoreSettings(JSONObject().put("animateRoutes", false).put("palette", "dark"))
        QuickActionPreferences(app).restoreSettings(JSONObject().put("enabled", false))
        LocalVehicleAutomationPreferences(app).restoreSettings(JSONObject("""{
            "schema":1,"headlights":{"enabled":false,"onDrive":"automatic","onPark":"keep"},
            "drivingMode":{"enabled":false}
        }"""))
        val climates = LocalSeasonalClimatePreferences(app)
        climates.restoreSettings(climates.exportSettings().apply {
            getJSONObject("summer").put("enabled", false).put("outsideThresholdCelsius", 24).put("driverCelsius", 22)
                .put("passengerCelsius", 22).put("fanLevel", 3).put("driverSeatLevel", 0).put("passengerSeatLevel", 0)
                .put("gearCondition", "park").put("requireDoorsClosed", true)
            getJSONObject("winter").put("enabled", false).put("outsideThresholdCelsius", 5).put("driverCelsius", 24)
                .put("passengerCelsius", 24).put("fanLevel", 3).put("driverSeatLevel", 0).put("passengerSeatLevel", 0)
                .put("gearCondition", "park").put("requireDoorsClosed", true)
        })
        runBlocking {
            val initial = NamedRoutine("12345678-1234-4234-9234-123456789abc", "Park desktop", RoutinePlan(RoutineTarget.APP_GRID))
            val routine = NamedRoutine("12345678-1234-4234-9234-123456789abd", ROUTINE_NAME, RoutinePlan(RoutineTarget.SETTINGS))
            RoutinePreferences(app).update { RoutineLibrary(listOf(initial, routine), startupEnabled = false) }
            ConditionalRulesPreferences(app).update { ConditionalRuleLibrary() }
        }
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        instrumentation.runOnMainSync {
            previousClipboard = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        }
    }

    override fun tearDown() {
        try {
            active?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            instrumentation.waitForIdleSync()
            previousLanguage?.let(UiText::language)
            previousStores.forEach { (name, values) ->
                val editor = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                values.forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                        else -> error("Unsupported fixture preference type")
                    }
                }
                check(editor.commit())
            }
            instrumentation.runOnMainSync {
                val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                previousClipboard?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
            }
        } finally {
            active = null
            super.tearDown()
        }
    }

    fun testVehicleWorkflowNavigationPersistenceAndClosedHardwareActionsAtHeadUnitSize() {
        try {
            val activity = launch(ControlsNavigation.intent(app, ControlsRoute.VEHICLE))
            awaitRoute(activity, ControlsRoute.VEHICLE)
            scrollTo(label(R.string.vehicle_automation_entry))
            capture("vehicle-settings")
            click(label(R.string.vehicle_automation_entry))
            awaitRoute(activity, ControlsRoute.VEHICLE_AUTOMATION)
            awaitText(label(R.string.vehicle_automation_lights_enabled))
            click(label(R.string.vehicle_automation_lights_enabled))
            await("The light preference was not saved") {
                LocalVehicleAutomationPreferences(app).exportSettings().getJSONObject("headlights").getBoolean("enabled")
            }
            click(label(R.string.vehicle_automation_on_drive))
            click(label(R.string.headlight_mode_low_beam))
            await("The Drive light action did not persist") {
                LocalVehicleAutomationPreferences(app).exportSettings().getJSONObject("headlights").getString("onDrive") == HeadlightGearAction.LOW_BEAM.key
            }
            instrumentation.runOnMainSync {
                val capabilities = activity.vehicleAutomationModel().state.value.capabilities
                assertTrue(capabilities.headlightActions.isEmpty())
                assertTrue(capabilities.drivingModes.isEmpty())
            }
            assertNoEnabledClick(label(R.string.vehicle_automation_restore))
            assertNoEnabledClick(label(R.string.vehicle_automation_remember_current))
            scrollTo(label(R.string.vehicle_automation_climate), backwards = true)
            capture("vehicle-automation")

            scrollTo(label(R.string.vehicle_automation_climate), backwards = true)
            click(label(R.string.vehicle_automation_climate))
            awaitRoute(activity, ControlsRoute.SEASONAL_CLIMATE)
            awaitText(label(R.string.seasonal_climate_summer))
            assertNoEnabledClick(label(R.string.seasonal_climate_apply))
            instrumentation.runOnMainSync { assertTrue(activity.seasonalClimateModel().state.value.approved.isEmpty()) }
            click(label(R.string.seasonal_climate_edit))
            awaitText(label(R.string.seasonal_climate_edit_title).format(label(R.string.seasonal_climate_summer)))
            setField(label(R.string.seasonal_climate_above_field), "27")
            setField(label(R.string.seasonal_climate_driver), "20")
            setField(label(R.string.seasonal_climate_fan), "5")
            scrollTo(label(R.string.seasonal_climate_save))
            capture("seasonal-climate-editor")
            click(label(R.string.seasonal_climate_save))
            await("The summer preset did not persist") {
                LocalSeasonalClimatePreferences(app).exportSettings().getJSONObject("summer").let {
                    it.getInt("outsideThresholdCelsius") == 27 && it.getInt("driverCelsius") == 20 && it.getInt("fanLevel") == 5
                }
            }
            click(label(R.string.cockpit_back))
            awaitText(label(R.string.seasonal_climate_summer))
            click(label(R.string.seasonal_climate_enabled))
            await("The summer scenario toggle did not persist") {
                LocalSeasonalClimatePreferences(app).exportSettings().getJSONObject("summer").getBoolean("enabled")
            }
            assertNoEnabledClick(label(R.string.seasonal_climate_apply))
            capture("seasonal-climate")
            click(label(R.string.cockpit_back))
            awaitRoute(activity, ControlsRoute.VEHICLE_AUTOMATION)

            scrollTo(label(R.string.vehicle_automation_rules), backwards = true)
            click(label(R.string.vehicle_automation_rules))
            awaitRoute(activity, ControlsRoute.CONDITIONAL_RULES)
            click(label(R.string.rules_create))
            awaitText(label(R.string.rules_name))
            setField(label(R.string.rules_name), RULE_NAME)
            click(label(R.string.rules_doors))
            click(label(R.string.rules_all_closed))
            click(label(R.string.rules_routine))
            click(ROUTINE_NAME)
            click(label(R.string.rules_enabled))
            scrollTo(label(R.string.rules_save))
            click(label(R.string.rules_save))
            await("The new rule did not persist") { ConditionalRulesPreferences(app).current().entries.size == 1 }
            val created = ConditionalRulesPreferences(app).current().entries.single()
            assertEquals(RULE_NAME, created.name)
            assertEquals(VehicleGear.PARK, created.gear)
            assertEquals(DoorRequirement.ALL_CLOSED, created.doors)
            assertEquals(ROUTINE_NAME, created.routine.name)
            assertTrue(created.enabled)
            awaitText(RULE_NAME)
            click(RULE_NAME)
            awaitText(label(R.string.rules_edit))
            click(label(R.string.rules_cooldown))
            click(label(R.string.rules_seconds).format(60))
            capture("rule-editor")
            click(label(R.string.rules_save))
            await("The edited rule did not persist") { ConditionalRulesPreferences(app).current().entries.single().cooldownSeconds == 60 }
            awaitText(RULE_NAME)
            capture("conditional-rules")
            click(label(R.string.cockpit_back))
            awaitRoute(activity, ControlsRoute.VEHICLE_AUTOMATION)

            scrollTo(label(R.string.vehicle_automation_apollo), backwards = true)
            click(label(R.string.vehicle_automation_apollo))
            awaitRoute(activity, ControlsRoute.FACTORY_ASSISTANCE)
            awaitText(label(R.string.factory_assistance_setup))
            assertNoEnabledClick(label(R.string.vehicle_settings_open))
            click(label(R.string.factory_assistance_check))
            awaitText(label(R.string.factory_assistance_no_provider), timeout = 10_000)
            scrollTo(label(R.string.factory_assistance_export))
            awaitText(label(R.string.factory_assistance_export))
            capture("factory-assistance")
            scrollTo(label(R.string.vehicle_automation_diagnostics))
            click(label(R.string.vehicle_automation_diagnostics))
            awaitText(label(R.string.vehicle_diagnostics_title))
            click(label(R.string.vehicle_diagnostics_copy))
            awaitText(label(R.string.vehicle_diagnostics_copied), timeout = 10_000)
            var report: String? = null
            instrumentation.runOnMainSync {
                report = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()
            }
            val diagnostic = JSONObject(checkNotNull(report)).getJSONObject("vehicle_automation")
            assertEquals("separate_exact_firmware_and_capability_review_required", diagnostic.getString("action_admission"))
            assertFalse(diagnostic.getJSONObject("native_reader").getBoolean("h97d_service_admitted"))
            assertEquals(5, diagnostic.getJSONArray("missing_signals").length())
            File(output(), "vehicle-diagnostics.json").writeText(checkNotNull(report), Charsets.UTF_8)
            capture("vehicle-diagnostics")
        } catch (failure: Throwable) {
            runCatching { capture("vehicle-workflows-failure") }
            throw failure
        }
    }

    private fun launch(intent: Intent): MainActivity {
        val activity = instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        active = activity
        await("The activity did not lay out") { activity.window.decorView.isLaidOut }
        instrumentation.waitForIdleSync()
        return activity
    }

    private fun awaitRoute(activity: MainActivity, expected: ControlsRoute) = await("Expected route $expected") {
        var route: ControlsRoute? = null
        instrumentation.runOnMainSync { route = ViewModelProvider(activity)[ControlsNavigationViewModel::class.java].state.value.route }
        route == expected
    }

    private fun nodes(): Sequence<AccessibilityNodeInfo> {
        fun visit(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            if (!node.refresh()) return@sequence
            yield(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(visit(it)) }
        }
        val automation = instrumentation.uiAutomation
        val root = automation.windows.firstOrNull { it.isActive }?.root ?: automation.rootInActiveWindow ?: return emptySequence()
        return visit(root)
    }

    private fun textNode(text: String) = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == text }
    private fun awaitText(text: String, timeout: Long = 5_000) = await("Visible text missing: $text", timeout) { textNode(text) != null }

    private fun click(text: String) {
        await("No current click action for $text") {
            var node = textNode(text)
            var depth = 0
            while (node != null && depth++ < 16) {
                if (!node.refresh()) break
                if (node.isVisibleToUser && node.isEnabled && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    instrumentation.waitForIdleSync()
                    return@await true
                }
                node = node.parent
            }
            false
        }
    }

    private fun setField(label: String, value: String) {
        scrollTo(label)
        await("The field $label did not accept input") {
            val bounds = textNode(label)?.let { Rect().also(it::getBoundsInScreen) } ?: return@await false
            val field = nodes().firstOrNull { node ->
                if (!node.isEditable || !node.isVisibleToUser) return@firstOrNull false
                val area = Rect().also(node::getBoundsInScreen)
                bounds.centerX() in area.left until area.right && bounds.bottom in area.top..area.bottom
            } ?: return@await false
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })
        }
        instrumentation.waitForIdleSync()
    }

    private fun assertNoEnabledClick(text: String) {
        scrollTo(text)
        val matches = nodes().filter { it.isVisibleToUser && it.text?.toString() == text }.toList()
        assertTrue("The disabled action $text is missing", matches.isNotEmpty())
        matches.forEach { label ->
            assertFalse("Unqualified action is enabled: $text", generateSequence(label) { it.parent }.take(16).any {
                it.refresh() && it.isEnabled && it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK }
            })
        }
    }

    private fun scrollTo(text: String, backwards: Boolean = false) {
        if (textNode(text) != null) return
        var direction = if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        var reversals = 0
        val deadline = SystemClock.uptimeMillis() + 6_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (textNode(text) != null) return
            val scroll = nodes().filter { it.isVisibleToUser && it.isScrollable }.maxByOrNull {
                Rect().also(it::getBoundsInScreen).let { bounds -> bounds.width().toLong() * bounds.height() }
            }
            val moved = scroll?.performAction(direction) == true
            instrumentation.waitForIdleSync()
            SystemClock.sleep(90)
            if (!moved && reversals++ == 0) direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            if (!moved && reversals > 1) break
        }
        assertNotNull("Could not scroll to $text", textNode(text))
    }

    private fun await(message: String, timeout: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        File(output(), "vehicle-workflows-failure.txt").writeText(nodes().joinToString("\n") {
            "text=${it.text} visible=${it.isVisibleToUser} enabled=${it.isEnabled} editable=${it.isEditable} actions=${it.actionList}"
        })
        fail(message)
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals("Test display width", 1920, bitmap.width)
            assertEquals("Test display height", 720, bitmap.height)
            File(output(), "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun output(): File = File(app.filesDir, "ui-audit").apply { check(isDirectory || mkdirs()) }
    private fun label(resource: Int): String = UiText.get(resource)

    private companion object {
        const val ROUTINE_NAME = "Cabin settings"
        const val RULE_NAME = "After parking"
    }
}
