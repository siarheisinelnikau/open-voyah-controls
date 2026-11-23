package org.openvoyah.controls.platform.vehicle.automation

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.data.apollo.ApolloCandidateEvidenceProbe
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignal
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignalKind
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.platform.vehicle.headlightDiagnosticReport

/** A bounded local snapshot. No factory Activity, setter, root shell or network request is used. */
internal suspend fun vehicleAutomationDiagnosticReport(
    context: Context,
    telemetry: VehicleTelemetry,
    lighting: HeadlightStatus,
    native: NativeVehicleDiagnosticState = NativeVehicleDiagnosticState(),
    runtime: VehicleRuntimeState? = null,
): String = withContext(Dispatchers.IO) {
    val now = SystemClock.elapsedRealtimeNanos()
    val base = JSONObject(headlightDiagnosticReport(context, lighting))
    val current = JSONObject()
    fun <T> signal(kind: VehicleSignalKind, sample: VehicleSignal<T>?, value: (T) -> Any) {
        if (sample == null) return
        current.put(kind.key, JSONObject()
            .put("value", value(sample.value))
            .put("source", sample.sourceId)
            .put("session", sample.sessionId)
            .put("observed_at_elapsed_ns", sample.observedAtElapsedNanos)
            .put("received_at_ms", sample.receivedAtMillis)
            .put("fresh", kind !in telemetry.unavailable && sample.isFreshFor(telemetry, now)))
    }
    signal(VehicleSignalKind.GEAR, telemetry.gear) { it.key }
    signal(VehicleSignalKind.SPEED, telemetry.speedKph) { it }
    signal(VehicleSignalKind.OUTSIDE_TEMPERATURE, telemetry.outsideCelsius) { it }
    signal(VehicleSignalKind.DRIVING_MODE, telemetry.drivingMode) { it.key }
    val doors = JSONObject()
    for (door in VehicleDoor.entries) {
        val sample = telemetry.doors[door] ?: continue
        doors.put(door.key, JSONObject()
            .put("value", sample.value.key)
            .put("source", sample.sourceId)
            .put("session", sample.sessionId)
            .put("observed_at_elapsed_ns", sample.observedAtElapsedNanos)
            .put("received_at_ms", sample.receivedAtMillis)
            .put("fresh", VehicleSignalKind.DOORS !in telemetry.unavailable && sample.isFreshFor(telemetry, now)))
    }
    if (doors.length() > 0) current.put("doors", doors)

    val missing = JSONArray()
    fun missing(kind: VehicleSignalKind, present: Boolean, requirement: String) {
        if (!present) missing.put(JSONObject().put("signal", kind.key).put("required_evidence", requirement))
    }
    fun <T> fresh(kind: VehicleSignalKind, sample: VehicleSignal<T>?) =
        kind !in telemetry.unavailable && sample?.isFreshFor(telemetry, now) == true
    missing(VehicleSignalKind.GEAR, fresh(VehicleSignalKind.GEAR, telemetry.gear),
        "Exact H97D signed CAN service and onGearStatusChanged P/N/R/D plus sleep/wake trace; getGearStatus is a cached snapshot.")
    missing(VehicleSignalKind.SPEED, fresh(VehicleSignalKind.SPEED, telemetry.speedKph),
        "H97D speed source with independently visible validity and units. The reviewed service emits integer zero for invalid input as well as standstill; callback 16 cannot establish stationary telemetry.")
    missing(VehicleSignalKind.DOORS, VehicleSignalKind.DOORS !in telemetry.unavailable && VehicleDoor.entries.all {
        telemetry.doors[it]?.isFreshFor(telemetry, now) == true
    }, "H97D per-door position producer and fresh open/closed feedback. The pinned service accepts only position deltas after a baseline, never lock-only callbacks or unchanged shared fields.")
    missing(VehicleSignalKind.OUTSIDE_TEMPERATURE, fresh(VehicleSignalKind.OUTSIDE_TEMPERATURE, telemetry.outsideCelsius),
        "H97D AirCondition.airTempOutCar producer and wake trace. The pinned service uses callback 4, not ambient callback 14: raw byte / 2 - 40, invalid -9999. Only field deltas after a baseline are accepted.")
    missing(VehicleSignalKind.DRIVING_MODE, fresh(VehicleSignalKind.DRIVING_MODE, telemetry.drivingMode),
        "H97D DRIVING_MODE_SET_FB mapping and controller trace. The pinned service decodes VCU feedback values 1..6; DRIVING_MODE_SET and setter receipts cannot update actual mode.")

    val raw = JSONObject()
    for ((key, sample) in native.callbacks.toSortedMap()) {
        val decoded = JSONObject()
        when (key) {
            "gear_callback" -> decoded.put("gear", PinnedVehicleCallbacks.gear(sample.values[1], sample.values[2])?.key ?: JSONObject.NULL)
            "driving_mode_feedback" -> decoded.put("mode", PinnedVehicleCallbacks.drivingMode(sample.values)?.key ?: JSONObject.NULL)
            "doors_cache" -> PinnedVehicleCallbacks.doorPositions(sample.values)?.forEach { (door, state) ->
                decoded.put(door.key, state?.key ?: JSONObject.NULL)
            }
            "air_condition_cache" -> decoded.put("outside_celsius", PinnedVehicleCallbacks.outsideTemperature(sample.values) ?: JSONObject.NULL)
                .put("outside_raw", sample.values[PinnedVehicleCallbacks.OUTSIDE_TEMPERATURE_INDEX])
            "speed_callback" -> decoded.put("reported_integer", sample.values.single())
                .put("zero_is_ambiguous", true)
        }
        raw.put(key, JSONObject()
            .put("raw_integers", JSONArray(sample.values))
            .put("decoded_fields", decoded)
            .put("received_at_ms", sample.receivedAtMillis)
            .put("received_at_elapsed_ns", sample.receivedAtElapsedNanos)
            .put("count_in_session", sample.count)
            .put("qualified_for_actions", false))
    }
    val cached = native.cachedGear?.let {
        JSONObject().put("ordinal", it.ordinal).put("value", it.value)
            .put("queried_at_ms", it.queriedAtMillis).put("fresh", false)
    }
    val reader = JSONObject()
        .put("state", native.readerState?.name?.lowercase() ?: "not_started")
        .put("connected", native.connected)
        .put("session", native.sessionId ?: JSONObject.NULL)
        .put("getGearStatus_outcome", native.cachedGearOutcome)
        .put("cached_gear", cached ?: JSONObject.NULL)
        .put("callbacks", raw)
        .put("timestamp_basis", "callback_arrival_only_no_controller_timestamp")
        .put("shared_field_freshness", "first_callback_seeds_only_then_position_or_outside_temperature_deltas_never_unchanged_fields")
        .put("speed_validity", "not_observable_zero_can_mean_invalid_input")
        .put("h97d_service_admitted", false)
    base.put("vehicle_automation", JSONObject()
        .put("schema", 1)
        .put("captured_at_elapsed_ns", now)
        .put("session", telemetry.sessionId)
        .put("session_started_at_elapsed_ns", telemetry.sessionStartedAtElapsedNanos)
        .put("identity", JSONObject().put("product", telemetry.identity.product)
            .put("fingerprint", telemetry.identity.fingerprint).put("sdk", telemetry.identity.sdk).put("abi", telemetry.identity.abi))
        .put("signals", current)
        .put("runtime", runtime?.let {
            JSONObject().put("background_service_connected", it.backgroundConnected)
                .put("automatic_features_configured", it.automaticConfigured)
                .put("automatic_monitor_running", it.automaticRunning)
                .put("foreground_only", it.foregroundOnly)
                .put("screen_off_policy", "live_signals_revoked_no_pending_edge_replay")
        } ?: JSONObject.NULL)
        .put("native_reader", reader)
        .put("missing_signals", missing)
        .put("action_admission", "separate_exact_firmware_and_capability_review_required")
        .put("additional_required_evidence", JSONArray(listOf(
            "Fresh READY/ignition trip boundary and actual mode before sleep and after wake.",
            "Factory lighting setter plus independent selector/lamp feedback for each requested mode.",
            "Factory climate power, temperature, fan and selected equipment feedback for each seasonal action.",
            "Apollo: actual factory product, exact firmware and the original readOnlineConfigItem(ASC) and readOnlineConfigItem(SDB) results. The stock H97D N2 Baidu branch requires the matching platform and ASC=1/SDB=1; N1 and N2 Hikvision are different branches. No configuration is overridden.",
            "Apollo: ADAS software, stock enrollment/training/subscription status and the result of opening factory Driving assistance.",
        ))))
    base.put("apollo_candidate", JSONObject(ApolloCandidateEvidenceProbe(context).report()))
    base.toString(2)
}
