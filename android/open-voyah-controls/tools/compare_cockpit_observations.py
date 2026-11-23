"""Compare two Controls lighting reports with independently recorded observations."""

import argparse
import json
from pathlib import Path
import re


CAN = "com.qinggan.canbus.service"
CONTROLS = "org.openvoyah.controls"
FACTORY_PACKAGES = (CAN, "com.qinggan.app.vehicle", "com.qinggan.app.vehiclesetting")
FACTORY_SELECTOR = {"automatic", "low_beam", "position", "off"}
ANDROID_SELECTOR = {"automatic", "on", "daytime_running", "off"}
SIGNALS = {
    "selector": FACTORY_SELECTOR | ANDROID_SELECTOR,
    "headlights": {"off", "on", "daytime_running"},
    "high_beam": {"off", "on"},
}
ANDROID_INPUTS = {
    "selector": "android_selector", "headlights": "android_headlamps", "high_beam": "android_high_beam",
}
ACTIVITY_PERMISSION = "android.car.permission.CAR_EXTERIOR_LIGHTS"
SELECTOR_PERMISSION = "android.car.permission.CONTROL_CAR_EXTERIOR_LIGHTS"
IDENTITY_FIELDS = ("android_build", "android_product", "android_api", "app_version")
MAX_INPUT_BYTES = 256 * 1024
MAX_TIME = (1 << 63) - 1


def require(condition, message):
    if not condition:
        raise ValueError(message)


def object_value(value, label):
    require(isinstance(value, dict), f"{label} must be an object")
    return value


def read_json(path):
    def unique_keys(pairs):
        obj = dict(pairs)
        require(len(obj) == len(pairs), "JSON object contains duplicate keys")
        return obj

    with path.open("rb") as stream:
        data = stream.read(MAX_INPUT_BYTES + 1)
    require(len(data) <= MAX_INPUT_BYTES, "JSON input exceeds 256 KiB")
    return object_value(json.loads(data.decode("utf-8-sig"), object_pairs_hook=unique_keys), "JSON input")


def valid_time(value):
    return type(value) is int and 0 < value <= MAX_TIME


def valid_sha(value):
    return isinstance(value, str) and re.fullmatch(r"[a-fA-F0-9]{64}", value) is not None


def valid_reading(signal, value, source):
    if not isinstance(value, str) or value not in SIGNALS[signal]:
        return False
    if source is None:
        return True
    if source == "qinggan":
        return signal == "selector" and value in FACTORY_SELECTOR
    return source == "android_automotive" and (signal != "selector" or value in ANDROID_SELECTOR)


def visible_packages(report):
    packages = object_value(report.get("factory_packages"), "factory_packages")
    visible = {}
    for package in FACTORY_PACKAGES:
        row = object_value(packages.get(package), package)
        status = row.get("status")
        if status in ("not_visible_or_absent", "query_failed"):
            continue
        require(status == "visible", f"Unexpected package status for {package}")
        version = row.get("version_code")
        signers = row.get("current_signer_sha256")
        require(type(version) is int and version >= 0, f"Invalid version code for {package}")
        require(isinstance(signers, list) and all(valid_sha(signer) for signer in signers),
                f"Invalid signers for {package}")
        fields = {"version_code": version, "signers": tuple(sorted(signer.lower() for signer in signers)) or None}
        for field in ("version_name", "enabled", "system_app", "split_apk_count"):
            fields[field] = row.get(field)
        if package == CAN and row.get("base_apk_fingerprint_status") == "read":
            digest = row.get("base_apk_sha256")
            require(valid_sha(digest), "Invalid CAN service base APK digest")
            fields["apk_sha256"] = digest.lower()
        visible[package] = fields
    return visible


def check_matching_fields(left, right, label):
    for field, value in left.items():
        other = right.get(field)
        require(value is None or other is None or (type(value) is type(other) and value == other),
                f"{label} differs between reports ({field})")


def validate_snapshot(report):
    require(type(report.get("report_version")) is int and report["report_version"] == 2,
            "Expected a Controls lighting report version 2")
    require(valid_time(report.get("captured_at_ms")), "Invalid Controls capture time")
    require(type(report.get("android_api")) is int and 28 <= report["android_api"] <= 999,
            "Invalid Controls Android API")
    for field in ("android_build", "android_product", "app_version"):
        value = report.get(field)
        require(isinstance(value, str) and 0 < len(value) <= 256 and value.strip() == value,
                f"Invalid Controls {field}")
    automotive = object_value(report.get("android_automotive"), "android_automotive")
    object_value(automotive.get("permissions"), "Automotive permissions")
    inputs = object_value(report.get("lighting_inputs"), "lighting_inputs")
    for key in ("factory_selector", *ANDROID_INPUTS.values()):
        state = inputs.get(key)
        require(state is None or (isinstance(state, str) and re.fullmatch(r"[a-z_]{1,48}", state)),
                f"Invalid lighting input state for {key}")
    failures = None
    if "history_write_failures" in report:
        failures = report["history_write_failures"]
        require(isinstance(failures, list) and len(failures) <= len(SIGNALS) and
                all(isinstance(signal, str) and signal in SIGNALS for signal in failures) and
                len(set(failures)) == len(failures), "Invalid history write failure list")
    for section, time_field in (("current", "confirmed_at_ms"), ("last_confirmed", "confirmed_at_ms"),
                                ("earlier_android_reports", "reported_at_ms")):
        for signal, row in object_value(report.get(section), section).items():
            require(signal in SIGNALS, f"Unexpected signal in {section}")
            row = object_value(row, section)
            source = row.get("source")
            require(valid_reading(signal, row.get("value"), source), f"Invalid {signal} reading in {section}")
            require(section != "earlier_android_reports" or source == "android_automotive",
                    "Unexpected source for earlier Android report")
            at = row.get(time_field)
            require(valid_time(at) and at <= report["captured_at_ms"], f"Impossible {signal} time in {section}")
            if section == "current" and "history_write_failed" in row:
                require(type(row["history_write_failed"]) is bool, f"Invalid {signal} history write result")
                require(failures is None or row["history_write_failed"] == (signal in failures),
                        f"Conflicting {signal} history write results")
    return visible_packages(report)


def checkpoint(report, signal, observed):
    keys = [ANDROID_INPUTS[signal]]
    if signal == "selector":
        keys.append("factory_selector")
    inputs = {key: report["lighting_inputs"].get(key) for key in keys}
    result = {"operator_observed": observed, "inputs": inputs}
    if signal in report.get("history_write_failures", ()):
        result["history_write_failed"] = True
    for section, status in (("current", "unattributed_current"), ("last_confirmed", "history_only"),
                            ("earlier_android_reports", "earlier_android_report")):
        row = report[section].get(signal)
        if row is None:
            continue
        source = row.get("source")
        time_field = "reported_at_ms" if section == "earlier_android_reports" else "confirmed_at_ms"
        result.update({"value": row["value"], "source": source, time_field: row[time_field]})
        if section == "current" and "history_write_failed" in row:
            result["history_write_failed"] = row["history_write_failed"]
        if section == "current" and source is not None:
            permission = SELECTOR_PERMISSION if signal == "selector" else ACTIVITY_PERMISSION
            automotive = report["android_automotive"]
            android_ready = (report["android_api"] >= 29 and automotive.get("advertised") is True and
                             automotive["permissions"].get(permission) is True)
            key = "factory_selector" if source == "qinggan" else ANDROID_INPUTS[signal]
            if inputs[key] != "subscribed" or (source == "android_automotive" and not android_ready):
                status = "current_input_unverified"
            else:
                status = "live_match" if row["value"] == observed else "live_mismatch"
        result["status"] = status
        return result
    result["status"] = "permission_denied" if "permission_denied" in inputs.values() else "unavailable"
    return result


def transition(before, after, before_capture):
    if before["status"] != "live_match" or after["status"] != "live_match":
        return "unconfirmed"
    if before["source"] != after["source"]:
        return "source_changed"
    if after["confirmed_at_ms"] <= before_capture:
        same_confirmation = before["confirmed_at_ms"] == after["confirmed_at_ms"] and before["value"] == after["value"]
        return "same_confirmation" if same_confirmation else "no_new_confirmation"
    return "new_confirmation_same_value" if before["value"] == after["value"] else "fresh_matching_transition"


def cockpit_window(cockpit, identity, snapshots):
    if cockpit is None:
        return "not_provided", None, False
    require(cockpit.get("scope") == "cockpit", "Expected a Cockpit evidence report")
    device = object_value(cockpit.get("device"), "Cockpit device")
    require((device.get("fingerprint"), device.get("product"), device.get("sdk")) ==
            (identity["android_build"], identity["android_product"], str(identity["android_api"])),
            "Cockpit build, product or API differs from Controls reports")
    rows = cockpit.get("packages")
    require(isinstance(rows, list), "Cockpit packages must be an array")
    packages = {}
    for row in rows:
        row = object_value(row, "Cockpit package")
        name = row.get("package")
        require(name in (*FACTORY_PACKAGES, CONTROLS) and name not in packages, "Unexpected or repeated Cockpit package")
        if valid_sha(row.get("signing_cert_sha256")) and valid_sha(row.get("apk_sha256")):
            version = row.get("version_code")
            require(isinstance(version, str) and re.fullmatch(r"[0-9]{1,19}", version), "Invalid Cockpit package version")
            packages[name] = {"version_code": int(version), "signers": (row["signing_cert_sha256"].lower(),),
                              "apk_sha256": row["apk_sha256"].lower(), "version_name": row.get("version_name")}
        else:
            packages[name] = {}
    controls = packages.get(CONTROLS, {})
    check_matching_fields({"version_name": identity["app_version"]}, controls, "Controls version")
    for snapshot in snapshots:
        for name, fields in snapshot.items():
            check_matching_fields(fields, packages.get(name, {}), name)
    can = packages.get(CAN, {})
    can_verified = bool(can) and all(
        all(fields.get(CAN, {}).get(key) == can[key] for key in ("apk_sha256", "signers", "version_code"))
        and fields[CAN].get("split_apk_count") == 0
        for fields in snapshots
    )
    window = object_value(cockpit.get("cockpit_events", {}), "Cockpit observation window")
    if window.get("status") != "captured":
        return "incomplete", None, False
    if not controls.get("version_name"):
        return "controls_version_unverified", None, False
    start, end = window.get("start_epoch_ms"), window.get("end_epoch_ms_exclusive")
    records = window.get("records")
    require(valid_time(start) and valid_time(end) and 0 < end - start <= 120_000, "Invalid Cockpit observation interval")
    require(isinstance(records, list) and window.get("log_entry_limit") == 256 and len(records) <= 256,
            "Invalid Cockpit event limit")
    for row in records:
        at = object_value(row, "Cockpit event").get("logged_at_ms")
        require(valid_time(at) and start <= at < end, "Cockpit event lies outside the observation interval")
    return "captured", (start, end, sorted(records, key=lambda row: row["logged_at_ms"])), can_verified


def corroboration(signal, result, capture, window, can_verified):
    if result["status"] not in ("live_match", "live_mismatch"):
        return "no_attributed_live_reading"
    start, end, records = window
    if not start <= result["confirmed_at_ms"] <= capture < end:
        return "outside_window"
    if result["source"] == "qinggan" and not can_verified:
        return "can_package_unverified"
    last = None
    interactive = True  # A complete window may start while the reader is already active.
    for row in records:
        if row["logged_at_ms"] > capture:
            continue
        if row.get("event") == "headlight_power":
            last = None
            interactive = row.get("interactive") is True
        if not interactive:
            continue
        if signal == "selector" and row.get("event") == "headlight_feedback":
            last = (row, row.get("mode"))
        if signal != "selector" and row.get("event") == "headlight_activity" and row.get("lamp") == signal:
            last = (row, row.get("activity"))
    if last is not None:
        row, value = last
        if (value == result["value"] and row.get("source") == result["source"] and
                row.get("confirmed_at_ms") == result["confirmed_at_ms"] <= row["logged_at_ms"]):
            return "matched_event"
    return "no_matching_event"


def compare(before, after, observations, cockpit=None):
    packages = [validate_snapshot(before), validate_snapshot(after)]
    identity = {field: before[field] for field in IDENTITY_FIELDS}
    check_matching_fields(identity, after, "Controls identity")
    require(before["captured_at_ms"] < after["captured_at_ms"], "Controls reports must be captured in chronological order")
    for package in packages[0].keys() & packages[1].keys():
        check_matching_fields(packages[0][package], packages[1][package], package)
    notes = [object_value(observations.get(label), f"Observations {label}") for label in ("before", "after")]
    require(notes[0] and notes[0].keys() == notes[1].keys(), "Record the same nonempty set of signals at both checkpoints")
    for point in notes:
        for signal, value in point.items():
            require(signal in SIGNALS and valid_reading(signal, value, None), "Invalid operator-observed signal or value")
    window_status, window, can_verified = cockpit_window(cockpit, identity, packages)
    signals = {}
    for signal in SIGNALS:
        if signal not in notes[0]:
            continue
        first, second = [checkpoint(report, signal, point[signal]) for report, point in zip((before, after), notes)]
        if window is not None:
            first["cockpit_event"] = corroboration(signal, first, before["captured_at_ms"], window, can_verified)
            second["cockpit_event"] = corroboration(signal, second, after["captured_at_ms"], window, can_verified)
        signals[signal] = {"before": first, "after": second, "transition": transition(first, second, before["captured_at_ms"])}
    automatic = "selector" in signals and all(
        signals["selector"][point]["status"] == "live_match" and notes[index]["selector"] == "automatic"
        for index, point in enumerate(("before", "after"))
    )
    dipped_transition = ("headlights" in signals and signals["headlights"]["transition"] == "fresh_matching_transition"
                         and {point["headlights"] for point in notes} == {"on", "off"})
    return {
        "identity": identity,
        "captured_at_ms": {"before": before["captured_at_ms"], "after": after["captured_at_ms"]},
        "cockpit_window": window_status,
        "signals": signals,
        "automatic_at_both_with_fresh_headlight_transition": automatic and dipped_transition,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("observations", type=Path)
    parser.add_argument("--cockpit", type=Path, help="Optional output of the Cockpit evidence collector")
    args = parser.parse_args()
    try:
        report = compare(read_json(args.before), read_json(args.after), read_json(args.observations),
                         read_json(args.cockpit) if args.cockpit else None)
    except (OSError, ValueError, RecursionError) as failure:
        parser.exit(2, f"{parser.prog}: {failure}\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
