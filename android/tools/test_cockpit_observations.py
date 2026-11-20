"""Exercise the offline comparison with synthetic reports and operator notes."""

from copy import deepcopy
import json
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest

from compare_cockpit_observations import (
    ACTIVITY_PERMISSION, CAN, CONTROLS, FACTORY_PACKAGES, SELECTOR_PERMISSION, compare, read_json,
)


EPOCH = 1_700_000_000_000
SIGNER = "a" * 64
DIGEST = "b" * 64
BUILD = "fixture/fixture/fixture:11/version/test:user/release-keys"
APP_VERSION = "0.20.1"


def reading(value, offset, source="android_automotive"):
    return {"value": value, "confirmed_at_ms": EPOCH + offset, "source": source, "history_write_failed": False}


def snapshot(capture, headlights, lamp_time):
    return {
        "report_version": 2,
        "captured_at_ms": EPOCH + capture,
        "android_build": BUILD,
        "android_product": "fixture",
        "android_api": 30,
        "app_version": APP_VERSION,
        "android_automotive": {
            "advertised": True, "permissions": {ACTIVITY_PERMISSION: True, SELECTOR_PERMISSION: True},
        },
        "factory_packages": {
            CAN: {"status": "visible", "version_code": 7, "version_name": "1.2.0", "enabled": True,
                  "system_app": True, "split_apk_count": 0, "current_signer_sha256": [SIGNER],
                  "base_apk_fingerprint_status": "read", "base_apk_sha256": DIGEST},
            **{package: {"status": "not_visible_or_absent"} for package in FACTORY_PACKAGES[1:]},
        },
        "lighting_inputs": {
            "factory_selector": "subscribed", "android_selector": "subscribed",
            "android_headlamps": "subscribed", "android_high_beam": None,
        },
        "current": {"selector": reading("automatic", 20, "qinggan"), "headlights": reading(headlights, lamp_time)},
        "last_confirmed": {},
        "earlier_android_reports": {},
    }


def log_event(signal, value, at, source="android_automotive", logged_offset=5):
    fields = {"event": "headlight_feedback", "mode": value} if signal == "selector" else {
        "event": "headlight_activity", "lamp": signal, "activity": value,
    }
    return {**fields, "source": source, "confirmed_at_ms": EPOCH + at, "logged_at_ms": EPOCH + at + logged_offset}


def cockpit_report():
    return {
        "scope": "cockpit",
        "device": {"fingerprint": BUILD, "product": "fixture", "sdk": "30"},
        "packages": [
            {"package": CONTROLS, "version_code": "24", "version_name": APP_VERSION,
             "signing_cert_sha256": "c" * 64, "apk_sha256": "d" * 64},
            {"package": CAN, "version_code": "7", "version_name": "1.2.0",
             "signing_cert_sha256": SIGNER, "apk_sha256": DIGEST},
        ],
        "cockpit_events": {
            "status": "captured", "start_epoch_ms": EPOCH, "end_epoch_ms_exclusive": EPOCH + 3_000,
            "log_entry_limit": 256,
            "records": [log_event("selector", "automatic", 20, "qinggan"), log_event("headlights", "off", 30),
                        log_event("headlights", "on", 1_200)],
        },
    }


class CockpitObservationsTest(unittest.TestCase):
    def setUp(self):
        self.before = snapshot(600, "off", 30)
        self.after = snapshot(1_600, "on", 1_200)
        self.notes = {"before": {"selector": "automatic", "headlights": "off"},
                      "after": {"selector": "automatic", "headlights": "on"}}

    def compare(self, cockpit=None):
        return compare(self.before, self.after, self.notes, cockpit)

    def test_separate_auto_selection_and_new_lamp_transition(self):
        result = self.compare()
        self.assertTrue(result["automatic_at_both_with_fresh_headlight_transition"])
        self.assertEqual("same_confirmation", result["signals"]["selector"]["transition"])
        self.assertEqual("fresh_matching_transition", result["signals"]["headlights"]["transition"])
        self.assertEqual({"before": EPOCH + 600, "after": EPOCH + 1_600}, result["captured_at_ms"])
        self.assertEqual("not_provided", result["cockpit_window"])
        self.assertNotIn("cockpit_event", result["signals"]["headlights"]["after"])

    def test_old_timestamp_cannot_establish_new_state_even_when_both_live_readings_match(self):
        self.after["current"]["headlights"]["confirmed_at_ms"] = EPOCH + 599
        result = self.compare()
        self.assertEqual("live_match", result["signals"]["headlights"]["after"]["status"])
        self.assertEqual("no_new_confirmation", result["signals"]["headlights"]["transition"])
        self.assertFalse(result["automatic_at_both_with_fresh_headlight_transition"])

    def test_history_write_error_stays_separate_from_live_reading(self):
        self.after["current"]["headlights"]["history_write_failed"] = True
        headlights = self.compare()["signals"]["headlights"]
        self.assertFalse(headlights["before"]["history_write_failed"])
        self.assertTrue(headlights["after"]["history_write_failed"])
        self.assertEqual("live_match", headlights["after"]["status"])

    def test_history_write_errors_remain_when_signals_are_unavailable(self):
        self.after["current"].pop("headlights")
        self.after["last_confirmed"]["headlights"] = {"value": "off", "source": "android_automotive",
                                                     "confirmed_at_ms": EPOCH + 30}
        self.after["history_write_failures"] = ["headlights", "high_beam"]
        self.notes["before"]["high_beam"] = "off"
        self.notes["after"]["high_beam"] = "on"
        result = self.compare()
        headlights = result["signals"]["headlights"]["after"]
        self.assertEqual("history_only", headlights["status"])
        self.assertEqual("off", headlights["value"])
        self.assertTrue(headlights["history_write_failed"])
        high_beam = result["signals"]["high_beam"]["after"]
        self.assertEqual("unavailable", high_beam["status"])
        self.assertTrue(high_beam["history_write_failed"])

    def test_rejects_malformed_or_conflicting_history_write_failures(self):
        for failures in ({"headlights": True}, ["headlights", "headlights"], [None], ["fog"], ["headlights"]):
            with self.subTest(failures=failures):
                self.after["history_write_failures"] = failures
                with self.assertRaisesRegex(ValueError, "history write"):
                    self.compare()

    def test_rejects_malformed_input_state_and_history_write_result(self):
        self.before["lighting_inputs"]["android_headlamps"] = {"subscribed": True}
        with self.assertRaisesRegex(ValueError, "Invalid lighting input state"):
            self.compare()
        self.before["lighting_inputs"]["android_headlamps"] = "subscribed"
        self.after["current"]["headlights"]["history_write_failed"] = "false"
        with self.assertRaisesRegex(ValueError, "Invalid headlights history write result"):
            self.compare()

    def test_new_confirmation_of_same_state_and_daytime_running_are_distinct(self):
        self.notes["after"]["headlights"] = "off"
        self.after["current"]["headlights"]["value"] = "off"
        result = self.compare()
        self.assertEqual("new_confirmation_same_value", result["signals"]["headlights"]["transition"])
        self.assertFalse(result["automatic_at_both_with_fresh_headlight_transition"])
        self.notes["after"]["headlights"] = "daytime_running"
        self.after["current"]["headlights"]["value"] = "daytime_running"
        result = self.compare()
        self.assertEqual("fresh_matching_transition", result["signals"]["headlights"]["transition"])
        self.assertFalse(result["automatic_at_both_with_fresh_headlight_transition"])

    def test_history_and_earlier_android_states_are_never_live(self):
        self.after["last_confirmed"]["headlights"] = self.after["current"].pop("headlights")
        self.before["earlier_android_reports"]["headlights"] = {
            "value": "off", "source": "android_automotive", "reported_at_ms": EPOCH + 30,
        }
        self.before["current"].pop("headlights")
        result = self.compare(cockpit_report())
        self.assertEqual("earlier_android_report", result["signals"]["headlights"]["before"]["status"])
        self.assertEqual("history_only", result["signals"]["headlights"]["after"]["status"])
        self.assertEqual("no_attributed_live_reading", result["signals"]["headlights"]["after"]["cockpit_event"])
        self.assertFalse(result["automatic_at_both_with_fresh_headlight_transition"])

    def test_unavailable_and_permission_denied_are_individual_results(self):
        self.notes = {"before": {"high_beam": "off"}, "after": {"high_beam": "on"}}
        self.after["lighting_inputs"]["android_high_beam"] = "permission_denied"
        self.after["android_automotive"]["permissions"][ACTIVITY_PERMISSION] = False
        result = self.compare()["signals"]
        self.assertEqual({"high_beam"}, set(result))
        self.assertEqual("unavailable", result["high_beam"]["before"]["status"])
        self.assertEqual("permission_denied", result["high_beam"]["after"]["status"])
        self.assertEqual("unconfirmed", result["high_beam"]["transition"])

    def test_current_with_missing_source_or_denying_permission_cannot_establish_transition(self):
        self.before["current"]["headlights"]["source"] = None
        self.after["android_automotive"]["permissions"][ACTIVITY_PERMISSION] = False
        result = self.compare()
        self.assertEqual("unattributed_current", result["signals"]["headlights"]["before"]["status"])
        self.assertEqual("current_input_unverified", result["signals"]["headlights"]["after"]["status"])
        self.assertFalse(result["automatic_at_both_with_fresh_headlight_transition"])

    def test_automotive_subscription_requires_the_readers_minimum_api(self):
        self.before["android_api"] = self.after["android_api"] = 28
        result = self.compare()
        self.assertEqual("current_input_unverified", result["signals"]["headlights"]["after"]["status"])
        self.assertFalse(result["automatic_at_both_with_fresh_headlight_transition"])

    def test_unmatched_operator_observation_blocks_special_result(self):
        self.notes["after"]["headlights"] = "off"
        result = self.compare(cockpit_report())
        self.assertEqual("live_mismatch", result["signals"]["headlights"]["after"]["status"])
        self.assertEqual("matched_event", result["signals"]["headlights"]["after"]["cockpit_event"])
        self.assertFalse(result["automatic_at_both_with_fresh_headlight_transition"])

    def test_rejects_capture_order_bad_observation_time_and_identity_mismatch(self):
        for field, value, error in (
            ("captured_at_ms", self.before["captured_at_ms"], "Impossible|chronological"),
            ("android_product", "other", "identity differs"),
            ("android_api", True, "Android API"),
            ("report_version", 1, "version 2"),
        ):
            with self.subTest(field=field):
                changed = {**self.after, field: value}
                with self.assertRaisesRegex(ValueError, error):
                    compare(self.before, changed, self.notes)
        with self.assertRaisesRegex(ValueError, "Impossible headlights time"):
            self.after["current"]["headlights"]["confirmed_at_ms"] = EPOCH + 1_601
            self.compare()

    def test_rejects_mismatched_factory_hash_and_bad_or_asymmetric_notes(self):
        changed = deepcopy(self.after)
        changed["factory_packages"][CAN]["base_apk_sha256"] = "1" * 64
        with self.assertRaisesRegex(ValueError, "canbus.*apk_sha256"):
            compare(self.before, changed, self.notes)
        with self.assertRaisesRegex(ValueError, "same nonempty set"):
            compare(self.before, self.after, {"before": {"high_beam": "off"}, "after": {}})
        with self.assertRaisesRegex(ValueError, "Invalid operator-observed"):
            compare(self.before, self.after, {"before": {"high_beam": "off"}, "after": {"high_beam": "automatic"}})

    def test_corroborates_exact_events_inside_complete_window(self):
        result = self.compare(cockpit_report())
        self.assertEqual("captured", result["cockpit_window"])
        for signal in ("selector", "headlights"):
            for point in ("before", "after"):
                self.assertEqual("matched_event", result["signals"][signal][point]["cockpit_event"])

    def test_window_status_and_package_mismatch_never_corroborate(self):
        cockpit = cockpit_report()
        cockpit["cockpit_events"]["status"] = "record_limit_reached"
        result = self.compare(cockpit)
        self.assertEqual("incomplete", result["cockpit_window"])
        self.assertNotIn("cockpit_event", result["signals"]["selector"]["before"])
        cockpit["packages"][1]["apk_sha256"] = "1" * 64
        with self.assertRaisesRegex(ValueError, "canbus.*apk_sha256"):
            self.compare(cockpit)

    def test_factory_corroboration_requires_exact_can_package_in_both_snapshots(self):
        self.after["factory_packages"][CAN]["base_apk_fingerprint_status"] = "unreadable"
        self.after["factory_packages"][CAN]["base_apk_sha256"] = None
        result = self.compare(cockpit_report())
        self.assertEqual("can_package_unverified", result["signals"]["selector"]["before"]["cockpit_event"])
        self.assertEqual("matched_event", result["signals"]["headlights"]["after"]["cockpit_event"])

    def test_matching_event_must_be_latest_and_after_power_boundary(self):
        cockpit = cockpit_report()
        records = cockpit["cockpit_events"]["records"]
        records.append(log_event("headlights", "off", 1_300))
        records.append({"event": "headlight_power", "trigger": "screen_on", "interactive": True,
                        "logged_at_ms": EPOCH + 1_250})
        records.append(log_event("headlights", "on", 1_750))
        result = self.compare(cockpit)
        self.assertEqual("no_matching_event", result["signals"]["headlights"]["after"]["cockpit_event"])
        self.assertEqual("no_matching_event", result["signals"]["selector"]["after"]["cockpit_event"])
        self.assertEqual("matched_event", result["signals"]["headlights"]["before"]["cockpit_event"])

    def test_events_after_noninteractive_boundary_wait_for_interactive_boundary(self):
        cockpit = cockpit_report()
        records = cockpit["cockpit_events"]["records"]
        records.append({"event": "headlight_power", "trigger": "screen_off", "interactive": False,
                        "logged_at_ms": EPOCH + 1_100})
        result = self.compare(cockpit)
        self.assertEqual("no_matching_event", result["signals"]["headlights"]["after"]["cockpit_event"])
        records.append({"event": "headlight_power", "trigger": "screen_on", "interactive": True,
                        "logged_at_ms": EPOCH + 1_150})
        result = self.compare(cockpit)
        self.assertEqual("matched_event", result["signals"]["headlights"]["after"]["cockpit_event"])

    def test_rejects_events_beyond_verified_log_window(self):
        cockpit = cockpit_report()
        cockpit["cockpit_events"]["records"].append(log_event("headlights", "on", 3_000))
        with self.assertRaisesRegex(ValueError, "outside the observation interval"):
            self.compare(cockpit)

    def test_cli_accepts_utf8_bom_and_reports_invalid_json(self):
        script = Path(__file__).with_name("compare_cockpit_observations.py")
        with TemporaryDirectory() as folder:
            paths = [Path(folder) / name for name in ("before.json", "after.json", "notes.json")]
            for path, data in zip(paths, (self.before, self.after, self.notes)):
                path.write_text(json.dumps(data), encoding="utf-8-sig")
            run = subprocess.run([sys.executable, str(script), *map(str, paths)], capture_output=True, text=True)
            self.assertEqual(0, run.returncode, run.stderr)
            self.assertTrue(json.loads(run.stdout)["automatic_at_both_with_fresh_headlight_transition"])
            paths[0].write_text('{"report_version":2,"report_version":1}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate keys"):
                read_json(paths[0])


if __name__ == "__main__":
    unittest.main()
