package org.openvoyah.controls.platform.vehicle

import android.car.Car
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.test.InstrumentationTestCase
import android.util.Log
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.data.vehicle.LocalHeadlightHistory
import org.openvoyah.controls.domain.vehicle.ExteriorLamp.HEADLIGHTS
import org.openvoyah.controls.domain.vehicle.ExteriorLamp.HIGH_BEAM
import org.openvoyah.controls.domain.vehicle.HeadlightInput
import org.openvoyah.controls.domain.vehicle.HeadlightInputState
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.HeadlightMonitor
import org.openvoyah.controls.domain.vehicle.HeadlightSource
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.domain.vehicle.LampActivity

/** The emulator supplies simulated values while the normal Controls reader runs against CarService. */
class NativeAutomotiveHeadlightTest : InstrumentationTestCase() {
    fun testPlatformDeliversAndPersistsIndependentHeadlightReadings() {
        val app = instrumentation.targetContext.applicationContext
        val request = File(app.filesDir, "native-headlight-fixture.request")
        // The host arms the test when it can supply the Automotive samples.
        if (!request.isFile) return
        check(BuildConfig.DEBUG && Build.VERSION.SDK_INT == 35 && Build.TYPE == "userdebug" &&
            Build.HARDWARE == "ranchu" && Build.PRODUCT == "sdk_gcar_x86_64" &&
            app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
        check(request.length() in 1..64)
        val run = request.readText().trim()
        require(run.matches(Regex("[a-z0-9-]{1,32}")))
        check(request.delete())
        val receipt = File(app.filesDir, "native-headlight-fixture.json")
        if (receipt.exists()) check(receipt.delete())

        val preferences = app.getSharedPreferences(LocalHeadlightHistory.STORE, Context.MODE_PRIVATE)
        val original = preferences.all
        val history = LocalHeadlightHistory(app)
        val automation = instrumentation.uiAutomation
        val permissions = listOf("android.car.permission.CONTROL_CAR_EXTERIOR_LIGHTS", "android.car.permission.CAR_EXTERIOR_LIGHTS")
        val ordinaryPermissions = permissions.associateWith(app::checkSelfPermission)
        automation.adoptShellPermissionIdentity(*permissions.toTypedArray())
        try {
            for (permission in permissions) assertEquals(permission, PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(permission))
            runBlocking {
                withTimeout(8_000) {
                    AutomotiveHeadlightReader.android10Connections(app).first { connection ->
                        connection != null && connection.window.isOpen &&
                            connection.car.getCarManager(Car.PROPERTY_SERVICE) is CarPropertyManager
                    }
                }
            }
            check(preferences.edit().clear().commit())
            val readings = listOf(
                Reading(HeadlightMode.ON, LampActivity.OFF, LampActivity.ON),
                Reading(HeadlightMode.ON, LampActivity.OFF, LampActivity.OFF),
                Reading(HeadlightMode.ON, LampActivity.ON, LampActivity.OFF),
                Reading(HeadlightMode.AUTOMATIC, LampActivity.ON, LampActivity.OFF),
                Reading(HeadlightMode.AUTOMATIC, LampActivity.OFF, LampActivity.OFF),
            )
            var accepted = 0
            var previous: HeadlightStatus? = null
            var initial: JSONObject? = null
            val autoReports = JSONObject()
            val observingAt = System.currentTimeMillis()
            Log.i(TAG, "run=$run phase=observing")
            val latest = runBlocking {
                withTimeout(45_000) {
                    HeadlightMonitor(factoryHeadlightFeedback(app), history).observe().first { status ->
                        if (initial == null) {
                            if (status.inputStates[HeadlightInput.FACTORY_SELECTOR] != HeadlightInputState.MODEL_UNREVIEWED ||
                                HeadlightInput.entries.filter { it.source == HeadlightSource.ANDROID_AUTOMOTIVE }
                                    .any { status.inputStates[it] != HeadlightInputState.SUBSCRIBED }) return@first false
                            val selector = status.earlierReport ?: return@first false
                            val headlights = status.lamps[HEADLIGHTS]?.earlierReport ?: return@first false
                            val highBeam = status.lamps[HIGH_BEAM]?.earlierReport ?: return@first false
                            assertEquals(HeadlightMode.AUTOMATIC, selector.value)
                            assertEquals(LampActivity.ON, headlights.value)
                            assertEquals(LampActivity.ON, highBeam.value)
                            assertTrue(listOf(selector.reportedAtMillis, headlights.reportedAtMillis, highBeam.reportedAtMillis)
                                .all { it < observingAt })
                            assertNull(status.currentObservation)
                            assertNull(status.lastConfirmed)
                            assertTrue(status.lamps.values.all { it.current == null && it.lastConfirmed == null })
                            assertTrue(preferences.all.isEmpty())
                            initial = JSONObject().put("selector", selector.value.key).put("headlights", headlights.value.key)
                                .put("high_beam", highBeam.value.key).put("selector_time", selector.reportedAtMillis)
                                .put("headlights_time", headlights.reportedAtMillis).put("high_beam_time", highBeam.reportedAtMillis)
                                .put("factory_state", status.inputStates.getValue(HeadlightInput.FACTORY_SELECTOR).name)
                                .put("android_subscribed", 3)
                                .put("history_empty", true)
                            Log.i(TAG, "run=$run phase=initial_values")
                            return@first false
                        }
                        if (!readings[accepted].matches(status)) return@first false
                        previous?.let { verifyTransition(it, status) }
                        previous = status
                        accepted++
                        if (accepted == readings.size - 1 || accepted == readings.size) {
                            val label = if (accepted < readings.size) "before" else "after"
                            val report = JSONObject(headlightDiagnosticReport(app, status))
                            assertEquals(2, report.getInt("report_version"))
                            assertEquals(0, report.getJSONObject("earlier_android_reports").length())
                            val current = report.getJSONObject("current")
                            assertEquals("automatic", current.getJSONObject("selector").getString("value"))
                            assertEquals(if (label == "before") "on" else "off", current.getJSONObject("headlights").getString("value"))
                            assertEquals("off", current.getJSONObject("high_beam").getString("value"))
                            autoReports.put(label, report)
                        }
                        Log.i(TAG, "run=$run phase=reading sample=$accepted")
                        accepted == readings.size
                    }
                }
            }
            val lastSelector = checkNotNull(latest.currentObservation)
            val lastHeadlights = checkNotNull(latest.lamps[HEADLIGHTS]?.current)
            val lastHighBeam = checkNotNull(latest.lamps[HIGH_BEAM]?.current)
            for (source in listOf(lastSelector.source, lastHeadlights.source, lastHighBeam.source)) {
                assertEquals(HeadlightSource.ANDROID_AUTOMOTIVE, source)
            }

            val reopenedHistory = LocalHeadlightHistory(app)
            assertEquals(lastSelector, reopenedHistory.read())
            assertEquals(mapOf(HEADLIGHTS to lastHeadlights, HIGH_BEAM to lastHighBeam), reopenedHistory.readLamps())
            receipt.writeText(JSONObject().put("run", run).put("ordinary_permissions", JSONObject(ordinaryPermissions))
                .put("legacy_car_connection_checked", true)
                .put("samples_checked", accepted).put("initial_reports", checkNotNull(initial))
                .put("auto_reports", autoReports)
                .put("selector", lastSelector.mode.key).put("headlights", lastHeadlights.activity.key)
                .put("high_beam", lastHighBeam.activity.key).put("selector_time", lastSelector.confirmedAtMillis)
                .put("headlights_time", lastHeadlights.confirmedAtMillis).put("high_beam_time", lastHighBeam.confirmedAtMillis)
                .put("selector_source", checkNotNull(lastSelector.source).key)
                .put("headlights_source", checkNotNull(lastHeadlights.source).key)
                .put("high_beam_source", checkNotNull(lastHighBeam.source).key)
                .toString(2))
            // The host reads the preference file from a separate process before this test restores it.
            Log.i(TAG, "run=$run phase=stored")
            runBlocking { withTimeout(15_000) { while (receipt.exists()) delay(50) } }
        } finally {
            try {
                automation.dropShellPermissionIdentity()
            } finally {
                val restore = preferences.edit().clear()
                for ((key, value) in original) {
                    when (value) {
                        is String -> restore.putString(key, value)
                        is Long -> restore.putLong(key, value)
                        else -> error("Unexpected headlight preference type for $key")
                    }
                }
                check(restore.commit())
            }
        }
        for ((permission, result) in ordinaryPermissions) assertEquals(permission, result, app.checkSelfPermission(permission))
    }

    private fun verifyTransition(before: HeadlightStatus, after: HeadlightStatus) {
        val beforeMode = checkNotNull(before.currentObservation)
        val afterMode = checkNotNull(after.currentObservation)
        if (beforeMode.mode == afterMode.mode) assertEquals(beforeMode, afterMode)
        else assertTrue(afterMode.confirmedAtMillis > beforeMode.confirmedAtMillis)
        for (lamp in listOf(HEADLIGHTS, HIGH_BEAM)) {
            val beforeLamp = checkNotNull(before.lamps[lamp]?.current)
            val afterLamp = checkNotNull(after.lamps[lamp]?.current)
            if (beforeLamp.activity == afterLamp.activity) assertEquals(beforeLamp, afterLamp)
            else assertTrue(afterLamp.confirmedAtMillis > beforeLamp.confirmedAtMillis)
        }
    }

    private data class Reading(val mode: HeadlightMode, val headlights: LampActivity, val highBeam: LampActivity) {
        fun matches(status: HeadlightStatus): Boolean = status.currentObservation?.mode == mode &&
            status.lamps[HEADLIGHTS]?.current?.activity == headlights && status.lamps[HIGH_BEAM]?.current?.activity == highBeam
    }

    private companion object {
        const val TAG = "ControlsAaosFixture"
    }
}
