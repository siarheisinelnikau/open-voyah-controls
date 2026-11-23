package org.openvoyah.controls.platform.vehicle.automation

import android.Manifest
import android.app.UiAutomation
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.test.InstrumentationTestCase
import org.json.JSONObject
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.ButtonAccessibilityService
import org.openvoyah.controls.MainActivity
import org.openvoyah.controls.data.climate.LocalSeasonalClimatePreferences
import org.openvoyah.controls.data.rules.ConditionalRulesPreferences
import org.openvoyah.controls.data.vehicle.automation.LocalVehicleAutomationPreferences
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsRoute

/** Uses the real granted service on an isolated emulator; never selects a head unit. */
class VehicleWorkflowRuntimeTest : InstrumentationTestCase() {
    fun testAccessibilityOwnerKeepsAutomaticRuntimeAfterActivityClosesAndRevokesOnDisconnect() {
        check(BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 29 && Build.HARDWARE in setOf("ranchu", "goldfish") &&
            Build.PRODUCT.startsWith("sdk_"))
        val app = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver = app.contentResolver
        val services = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val accessibility = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        val stores = listOf(LocalVehicleAutomationPreferences.STORE, LocalSeasonalClimatePreferences.STORE,
            ConditionalRulesPreferences.STORE, "updates")
        val before = stores.associateWith { app.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
        var activity: MainActivity? = null
        try {
            check(app.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putBoolean("check_on_startup", false).commit())
            LocalVehicleAutomationPreferences(app).restoreSettings(JSONObject("""{
                "schema":1,"headlights":{"enabled":true,"onDrive":"automatic","onPark":"keep"},
                "drivingMode":{"enabled":false}
            }"""))
            val component = "${app.packageName}/${ButtonAccessibilityService::class.java.name}"
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, component))
                check(Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1))
            } finally { automation.dropShellPermissionIdentity() }
            await("Accessibility service did not connect") { ButtonAccessibilityService.isConnected() }
            val runtime = VehicleWorkflowRuntime.get(app)
            await("Background runtime did not start") { runtime.state.value.automaticRunning && runtime.state.value.backgroundConnected }
            val controller = runtime.controller
            val opened = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.VEHICLE_AUTOMATION)) as MainActivity
            activity = opened
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { opened.finish() }
            instrumentation.waitForIdleSync()
            assertSame(controller, VehicleWorkflowRuntime.get(app).controller)
            assertTrue(runtime.state.value.backgroundConnected)
            assertTrue(runtime.state.value.automaticRunning)
            assertTrue(runtime.controller.state.value.capabilities.headlightActions.isEmpty())
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ""))
                check(Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0))
            } finally { automation.dropShellPermissionIdentity() }
            await("Disconnected owner retained automatic work") { !runtime.state.value.automaticRunning && !ButtonAccessibilityService.isConnected() }
            await("Controller kept a live observation after its final owner stopped") { controller.state.value.telemetry == null }
        } finally {
            activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            before.forEach { (name, values) ->
                val editor = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                values.forEach { (key, value) -> when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> error("Unsupported fixture preference type")
                } }
                check(editor.commit())
            }
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services)
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, accessibility)
            } finally { automation.dropShellPermissionIdentity() }
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val limit = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < limit) SystemClock.sleep(25)
        assertTrue(message, condition())
    }
}
