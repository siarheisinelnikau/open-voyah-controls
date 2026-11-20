package org.openvoyah.controls.platform.vehicle.automation

import android.test.AndroidTestCase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.HeadlightObservation
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.domain.vehicle.automation.VehicleIdentity
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry

class VehicleAutomationDiagnosticReportTest : AndroidTestCase() {
    private val unknown = VehicleTelemetry(VehicleIdentity("f6808", "fixture/h97d", 30, "x86_64"), "fixture", 1)

    fun testMissingSignalsAndExistingHeadlightHistorySurviveTheDiagnosticSnapshot() = runBlocking {
        val lighting = HeadlightStatus(lastConfirmed = HeadlightObservation(HeadlightMode.AUTOMATIC, 1_000))
        val report = JSONObject(vehicleAutomationDiagnosticReport(context, unknown, lighting))
        assertEquals("automatic", report.getJSONObject("last_confirmed").getJSONObject("selector").getString("value"))
        val automation = report.getJSONObject("vehicle_automation")
        assertEquals("f6808", automation.getJSONObject("identity").getString("product"))
        assertEquals(5, automation.getJSONArray("missing_signals").length())
        assertEquals(0, automation.getJSONObject("signals").length())
        assertFalse(automation.getJSONObject("native_reader").getBoolean("h97d_service_admitted"))
    }

    fun testCachedParkingGetterNeverBecomesCurrentTelemetry() = runBlocking {
        val native = NativeVehicleDiagnosticState(connected = true,
            cachedGear = CachedNativeGear(0, 0, 1_000), cachedGearOutcome = "cached_snapshot_only")
        val automation = JSONObject(vehicleAutomationDiagnosticReport(context, unknown, HeadlightStatus(), native))
            .getJSONObject("vehicle_automation")
        assertFalse(automation.getJSONObject("signals").has("gear"))
        assertFalse(automation.getJSONObject("native_reader").getJSONObject("cached_gear").getBoolean("fresh"))
        assertEquals(5, automation.getJSONArray("missing_signals").length())
    }

    fun testRuntimeLeaseIsReportedSeparatelyFromFirmwareAndActionAdmission() = runBlocking {
        val runtime = VehicleRuntimeState(backgroundConnected = false, automaticConfigured = true, automaticRunning = true)
        val automation = JSONObject(vehicleAutomationDiagnosticReport(context, unknown, HeadlightStatus(), runtime = runtime))
            .getJSONObject("vehicle_automation")
        assertTrue(automation.getJSONObject("runtime").getBoolean("foreground_only"))
        assertFalse(automation.getJSONObject("runtime").getBoolean("background_service_connected"))
        assertEquals(0, automation.getJSONObject("signals").length())
        assertFalse(automation.getJSONObject("native_reader").getBoolean("h97d_service_admitted"))
    }

    fun testDecodedFactoryCachesRemainSeparateFromFreshSignalsAndStationaryAdmission() = runBlocking {
        val climate = MutableList(82) { 0 }.also { it[0] = 1; it[36] = -9999 }
        val native = NativeVehicleDiagnosticState(callbacks = mapOf(
            "air_condition_cache" to NativeCallbackSample(climate, 1_000, 100, 1),
            "speed_callback" to NativeCallbackSample(listOf(0), 1_000, 100, 2),
            "driving_mode_feedback" to NativeCallbackSample(listOf(1, 0x238, 0x313, 3), 1_000, 100, 1)))
        val automation = JSONObject(vehicleAutomationDiagnosticReport(context, unknown, HeadlightStatus(), native))
            .getJSONObject("vehicle_automation")
        val callbacks = automation.getJSONObject("native_reader").getJSONObject("callbacks")
        val outside = callbacks.getJSONObject("air_condition_cache").getJSONObject("decoded_fields")
        assertEquals(-9999, outside.getInt("outside_raw"))
        assertTrue(outside.isNull("outside_celsius"))
        assertTrue(callbacks.getJSONObject("speed_callback").getJSONObject("decoded_fields").getBoolean("zero_is_ambiguous"))
        assertEquals("sport", callbacks.getJSONObject("driving_mode_feedback").getJSONObject("decoded_fields").getString("mode"))
        assertEquals(0, automation.getJSONObject("signals").length())
        assertFalse(callbacks.getJSONObject("driving_mode_feedback").getBoolean("qualified_for_actions"))
    }
}
