"""Verify portable evidence collection with fake native tools; never contact ADB or HTTP."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest


ANDROID = Path(__file__).resolve().parents[1]
SOURCE = ANDROID / "tools/read-firmware-evidence.ps1"
POWERSHELL = shutil.which("pwsh")
RUSTC = shutil.which("rustc")
APK = b"portable evidence fixture APK"
ACTIVITY_PERMISSION = "android.car.permission.CAR_EXTERIOR_LIGHTS"
SELECTOR_PERMISSION = "android.car.permission.CONTROL_CAR_EXTERIOR_LIGHTS"
SEATS_PERMISSION = "android.car.permission.CONTROL_CAR_SEATS"


def expected_headlight_configs(
    selector=("compatible", "read_write", "on_change"),
    headlights=("compatible", "read", "on_change"),
    high_beam=("compatible", "read", "on_change"),
    source="car_service_shell",
):
    result = {"source": source}
    for signal, property_id, (status, access, mode) in (
        ("selector", "0x11400e10", selector),
        ("headlights", "0x11400e00", headlights),
        ("high_beam", "0x11400e01", high_beam),
    ):
        result[signal] = {"property_id": property_id, "status": status, "access": access, "change_mode": mode}
    return result


@unittest.skipUnless(os.name == "nt" and POWERSHELL and RUSTC, "Windows, PowerShell and Rust are required")
class FirmwareEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.parent = (ANDROID / "build/firmware-evidence-fixtures").resolve()
        cls.parent.mkdir(parents=True, exist_ok=True)
        cls.compiled = tempfile.TemporaryDirectory(dir=cls.parent)
        compiled_root = Path(cls.compiled.name).resolve()
        assert compiled_root.is_relative_to(cls.parent) and compiled_root != cls.parent
        cls.executable = compiled_root / "tools.exe"
        subprocess.run(
            [RUSTC, "--edition=2024", str(ANDROID / "tools/fixtures/firmware_evidence_tools.rs"),
             "-o", str(cls.executable)], check=True, capture_output=True, text=True,
        )

    @classmethod
    def tearDownClass(cls):
        compiled_root = Path(cls.compiled.name).resolve()
        assert compiled_root.is_relative_to(cls.parent) and compiled_root != cls.parent
        cls.compiled.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=self.parent)
        self.root = Path(self.temp.name).resolve()
        self.assertTrue(self.root.is_relative_to(self.parent) and self.root != self.parent)
        self.addCleanup(self.cleanup)
        self.kit = self.root / "portable kit"
        self.sdk = self.kit / "android-sdk"
        self.support = self.kit / "support"
        self.support.mkdir(parents=True)
        self.script = self.support / SOURCE.name
        shutil.copyfile(SOURCE, self.script)
        self.tool("platform-tools/adb.exe")
        self.build_tools("35.0.0")
        self.java = self.tool("jre/bin/java.exe")
        # These files match the portable SDK layout; no batch wrapper is included.
        for name in ("AdbWinApi.dll", "AdbWinUsbApi.dll", "source.properties", "NOTICE.txt"):
            (self.sdk / "platform-tools" / name).write_bytes(b"offline fixture")
        self.log = self.root / "commands.tsv"
        self.environment = os.environ.copy()
        self.environment["OVC_FIRMWARE_FIXTURE_LOG"] = str(self.log)
        self.environment["OVC_FIRMWARE_FIXTURE_APK_SHA256"] = hashlib.sha256(APK).hexdigest()
        self.environment.pop("OVC_FIRMWARE_FIXTURE_JAVA_FAIL", None)
        self.environment.pop("OVC_FIRMWARE_FIXTURE_ACCOUNT", None)
        self.environment.pop("OVC_FIRMWARE_FIXTURE_PROVIDER", None)
        self.environment.pop("OVC_FIRMWARE_FIXTURE_MANIFEST_FAIL", None)
        self.environment.pop("OVC_FIRMWARE_FIXTURE_MANIFEST_EMPTY", None)
        self.environment.pop("OVC_FIRMWARE_FIXTURE_ADB_DELAY", None)
        self.environment.pop("OVC_FIRMWARE_FIXTURE_EVENTS", None)
        self.environment.pop("OVC_FIRMWARE_FIXTURE_AUTOMOTIVE", None)
        self.environment.pop("OVC_FIRMWARE_FIXTURE_SEAT_METADATA", None)
        self.environment.pop("OVC_FIRMWARE_FIXTURE_HEADLIGHT_CONFIG", None)
        self.environment.pop("JAVA_HOME", None)

    def cleanup(self):
        self.assertTrue(self.root.is_relative_to(self.parent) and self.root != self.parent)
        self.temp.cleanup()

    def tool(self, relative):
        target = self.sdk / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(self.executable, target)
        return target

    def build_tools(self, version):
        self.tool(f"build-tools/{version}/aapt2.exe")
        directory = self.sdk / f"build-tools/{version}/lib"
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "apksigner.jar").write_bytes(b"offline verifier fixture")

    def collect(self, *arguments):
        return subprocess.run(
            [POWERSHELL, "-NoProfile", "-NonInteractive", "-File", str(self.script),
             "-Serial", "unit-a", "-SdkRoot", str(self.sdk), *arguments],
            capture_output=True, text=True, encoding="utf-8", timeout=20, env=self.environment,
        )

    def commands(self):
        return [line.split("\t") for line in self.log.read_text(encoding="utf-8").splitlines()] if self.log.exists() else []

    def assert_collected(self, result, java, version="35.0.0"):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        report = json.loads(result.stdout)
        self.assertFalse(report["reviewed"])
        self.assertNotIn("android_automotive", report)
        self.assertNotIn("factory_product", report["device"])
        self.assertEqual("Enforcing", report["selinux"])
        self.assertEqual("Fixture head unit", report["model"])
        self.assertEqual(8, len(report["packages"]))
        account = report["account_evidence"]
        self.assertEqual(1, len(account["diagnostic_packages"]))
        diagnostic = account["diagnostic_packages"][0]
        self.assertTrue(diagnostic["diagnostic_only"])
        self.assertNotIn("candidate_catalog_row", diagnostic)
        for package in report["packages"] + account["diagnostic_packages"]:
            self.assertNotIn("unavailable", package)
            self.assertEqual(hashlib.sha256(APK).hexdigest(), package["apk_sha256"])
            self.assertEqual("1" * 64, package["signing_cert_sha256"])
        commands = self.commands()
        self.assertFalse(any("check-permission" in row or "get-current-user" in row for row in commands))
        self.assertFalse(any("car_service" in row for row in commands))
        self.assertFalse(any("ro.build.product_3rdparty" in row for row in commands))
        java_commands = [row for row in commands if Path(row[0]).name == "java.exe"]
        self.assertEqual(10, len(java_commands))
        jar = self.sdk / f"build-tools/{version}/lib/apksigner.jar"
        for row in java_commands:
            self.assertEqual(java, Path(row[0]))
            self.assertEqual("-jar", row[1])
            self.assertEqual(jar, Path(row[2]))
        aapt_commands = [row for row in commands if Path(row[0]).name == "aapt2.exe"]
        self.assertTrue(all(Path(row[0]).parent.name == version for row in aapt_commands))
        self.assertTrue(all(row[1:3] == ["-t", "7"] for row in commands
                            if Path(row[0]).name == "adb.exe" and row[1] != "devices"))
        self.assertNotIn("PRIVATE_RECORD_MUST_NOT_APPEAR", result.stdout + result.stderr)
        self.assertFalse(account["complete_backup"])
        self.assertEqual("unavailable", account["restore_contract"])
        self.assertEqual("com.qinggan.tracking.fixture", account["provider"]["package"])
        self.assertEqual("metadata-observed", account["account_file"]["status"])
        self.assertFalse(account["account_file"]["content_collected"])
        self.assertEqual("not-observed", account["account_file"]["record_classification"])
        self.assertEqual("u:object_r:fixture_account_file:s0", account["account_file"]["selinux_context"])
        self.assertNotIn("fixture-hardware", result.stdout)
        inspected = [package for package in report["packages"] + account["diagnostic_packages"]
                     if "account_components" in package]
        self.assertEqual({"com.qinggan.app.login", "com.qinggan.app.vehicle", "com.qinggan.tracking.fixture"},
                         {package["package"] for package in inspected})
        for package in inspected:
            inventory = package["account_components"]
            self.assertEqual("declared", inventory["status"])
            self.assertEqual([
                {"kind": "service", "name": package["package"] + ".ObservedService"},
                {"kind": "provider", "name": package["package"] + ".ObservedProvider",
                 "authorities": [package["package"] + ".provider"]},
            ], inventory["components"])

    def test_portable_layout_uses_bundled_java_without_a_batch_wrapper(self):
        wrong_java = self.root / "wrong-jdk/bin/java.exe"
        wrong_java.parent.mkdir(parents=True)
        shutil.copyfile(self.executable, wrong_java)
        self.environment["JAVA_HOME"] = str(wrong_java.parent.parent)
        self.environment["PATH"] = str(wrong_java.parent) + os.pathsep + self.environment["PATH"]
        self.assertFalse((self.sdk / "build-tools/35.0.0/apksigner.bat").exists())
        self.assert_collected(self.collect(), self.java)

    def test_requested_build_tools_version_is_preserved(self):
        self.build_tools("36.0.0")
        self.assert_collected(self.collect("-BuildToolsVersion", "36.0.0"), self.java, "36.0.0")

    def test_cockpit_scope_verifies_only_four_packages_without_account_or_log_reads(self):
        result = self.collect("-Scope", "Cockpit")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual("cockpit", report["scope"])
        self.assertFalse(report["reviewed"])
        self.assertNotIn("account_evidence", report)
        self.assertNotIn("cockpit_events", report)
        self.assertEqual("f6808", report["device"]["factory_product"])
        self.assertEqual("f0506h", report["device"]["product"])
        self.assertEqual({
            "api_eligible": True, "automotive_feature": "present", "car_library": "present",
            "car_service": "present",
            "controls_permissions": {ACTIVITY_PERMISSION: "granted", SELECTOR_PERMISSION: "denied",
                                     SEATS_PERMISSION: "denied"},
            "seat_memory": {"source": "car_service_shell", "driver_area": 1,
                            "save_ranges": [{"area_id": 1, "min_index": 0, "max_index": 3}],
                            "restore_ranges": [{"area_id": 1, "min_index": 0, "max_index": 3}]},
            "headlight_properties": expected_headlight_configs(),
        }, report["android_automotive"])
        packages = {item["package"]: item for item in report["packages"]}
        self.assertEqual({"org.openvoyah.controls", "com.qinggan.canbus.service",
                          "com.qinggan.app.vehicle", "com.qinggan.app.vehiclesetting"}, set(packages))
        for name, item in packages.items():
            self.assertEqual("fixture", item["version_name"])
            self.assertEqual("1", item["version_code"])
            self.assertEqual("1" * 64, item["signing_cert_sha256"])
            self.assertEqual(hashlib.sha256(APK).hexdigest(), item["apk_sha256"])
            self.assertNotIn("account_components", item)
            if name in {"com.qinggan.canbus.service", "com.qinggan.app.vehicle"}:
                self.assertIn("candidate_catalog_row", item)
            else:
                self.assertTrue(item["diagnostic_only"])
                self.assertNotIn("candidate_catalog_row", item)
        commands = self.commands()
        self.assertEqual(set(packages), {cmd[-1] for cmd in commands if cmd[4:6] == ["pm", "path"]})
        self.assertEqual([
            ["shell", "service", "check", "car_service"],
            ["shell", "cmd", "car_service", "get-property-value", "0x1540010a", "0"],
            ["shell", "cmd", "car_service", "get-carpropertyconfig", "0x15400b81"],
            ["shell", "cmd", "car_service", "get-carpropertyconfig", "0x15400b80"],
            ["shell", "cmd", "car_service", "get-carpropertyconfig", "0x11400e10"],
            ["shell", "cmd", "car_service", "get-carpropertyconfig", "0x11400e00"],
            ["shell", "cmd", "car_service", "get-carpropertyconfig", "0x11400e01"],
        ],
                         [cmd[3:] for cmd in commands if "car_service" in cmd])
        grant_queries = [cmd[4:] for cmd in commands if "check-permission" in cmd]
        self.assertEqual([
            ["dumpsys", "package", "check-permission", permission, "org.openvoyah.controls", "10"]
            for permission in (ACTIVITY_PERMISSION, SELECTOR_PERMISSION, SEATS_PERMISSION)
        ], grant_queries)
        self.assertEqual(3, sum("get-current-user" in cmd for cmd in commands))
        self.assertEqual(4, sum(cmd[4:7] == ["pm", "path", "--user"] for cmd in commands))
        controls_path = "/data/app/~~Z9_eFI-00a==/org.openvoyah.controls-a11_Bc==/base.apk"
        self.assertTrue(any(cmd[3:5] == ["pull", controls_path] for cmd in commands))
        self.assertGreaterEqual(sum(cmd[4:] == ["sha256sum", controls_path] for cmd in commands), 3)
        for field in ("providers", "xmltree", "-T", "logcat", "pidof", "ro.serialno"):
            self.assertFalse(any(field in cmd for cmd in commands), field)
        self.assertNotIn("fixture-hardware", result.stdout)

    def test_cockpit_readiness_distinguishes_absent_and_unreadable_capabilities(self):
        cases = (("legacy", False, "absent", "absent", "not_visible"),
                 ("malformed", True, "unknown", "unknown", "unknown"),
                 ("feature-only", True, "present", "absent", "not_visible"),
                 ("library-only", True, "absent", "present", "not_visible"),
                 ("service-only", True, "present", "absent", "present"),
                 ("overflow-version", True, "unknown", "present", "present"))
        for case, eligible, feature, library, service in cases:
            with self.subTest(case=case):
                self.log.unlink(missing_ok=True)
                self.environment["OVC_FIRMWARE_FIXTURE_AUTOMOTIVE"] = case
                result = self.collect("-Scope", "Cockpit")
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                report = json.loads(result.stdout)
                self.assertEqual(None if case in {"legacy", "malformed"} else "f6808", report["device"]["factory_product"])
                readiness = report["android_automotive"]
                self.assertIs(eligible, readiness["api_eligible"])
                self.assertEqual((feature, library, service),
                                 (readiness["automotive_feature"], readiness["car_library"], readiness["car_service"]))
                other_grant = "unknown" if case == "malformed" else "denied"
                self.assertEqual({ACTIVITY_PERMISSION: "granted", SELECTOR_PERMISSION: other_grant,
                                  SEATS_PERMISSION: other_grant}, readiness["controls_permissions"])
                if service != "present":
                    self.assertEqual({"source": "not_probed", "driver_area": None, "save_ranges": None,
                                      "restore_ranges": None}, readiness["seat_memory"])
                    signal = ("not_probed", None, None)
                    self.assertEqual(expected_headlight_configs(signal, signal, signal, "not_probed"),
                                     readiness["headlight_properties"])
                    self.assertFalse(any(cmd[4:6] == ["cmd", "car_service"] for cmd in self.commands()))
                else:
                    self.assertEqual("car_service_shell", readiness["seat_memory"]["source"])
                    self.assertEqual(expected_headlight_configs(), readiness["headlight_properties"])
                self.assertNotIn("PRIVATE_RECORD_MUST_NOT_APPEAR", result.stdout + result.stderr)

    def test_cockpit_headlight_configs_require_the_exact_property_and_reader_policy(self):
        unavailable = ("unavailable", None, None)
        expected = {
            "modern": expected_headlight_configs(),
            "policy": expected_headlight_configs(
                ("compatible", "read", "static"),
                ("incompatible", "read_write", "on_change"),
                ("incompatible", "read", "continuous"),
            ),
            "invalid": expected_headlight_configs(
                ("incompatible", "read_write", "on_change"),
                ("incompatible", "read", "on_change"),
                unavailable,
            ),
            "unavailable": expected_headlight_configs(unavailable, unavailable),
        }
        for case, configs in expected.items():
            with self.subTest(case=case):
                self.environment["OVC_FIRMWARE_FIXTURE_HEADLIGHT_CONFIG"] = case
                result = self.collect("-Scope", "Cockpit")
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                readiness = json.loads(result.stdout)["android_automotive"]
                self.assertEqual(configs, readiness["headlight_properties"])
                self.assertEqual("denied", readiness["controls_permissions"][SELECTOR_PERMISSION])
                self.assertEqual("granted", readiness["controls_permissions"][ACTIVITY_PERMISSION])
                self.assertNotIn("PRIVATE_RECORD_MUST_NOT_APPEAR", result.stdout + result.stderr)

    def test_cockpit_reads_current_seat_configuration_without_assuming_the_driver_side(self):
        self.environment["OVC_FIRMWARE_FIXTURE_SEAT_METADATA"] = "modern"
        result = self.collect("-Scope", "Cockpit")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        readiness = json.loads(result.stdout)["android_automotive"]
        ranges = [{"area_id": 4, "min_index": 0, "max_index": 2}]
        self.assertEqual({"source": "car_service_shell", "driver_area": 4,
                          "save_ranges": ranges, "restore_ranges": ranges}, readiness["seat_memory"])
        self.assertEqual("denied", readiness["controls_permissions"][SEATS_PERMISSION])

    def test_cockpit_omits_unproven_driver_area_and_ranges_independently(self):
        for case, expected_restore in (("invalid-ranges", None), ("missing", [{"area_id": 1, "min_index": 0, "max_index": 3}])):
            with self.subTest(case=case):
                self.environment["OVC_FIRMWARE_FIXTURE_SEAT_METADATA"] = case
                result = self.collect("-Scope", "Cockpit")
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                metadata = json.loads(result.stdout)["android_automotive"]["seat_memory"]
                self.assertEqual({"source": "car_service_shell", "driver_area": None,
                                  "save_ranges": None, "restore_ranges": expected_restore}, metadata)
                self.assertNotIn("PRIVATE_RECORD_MUST_NOT_APPEAR", result.stdout + result.stderr)

    def test_cockpit_rejects_a_changed_factory_product(self):
        self.environment["OVC_FIRMWARE_FIXTURE_AUTOMOTIVE"] = "product-changed"
        result = self.collect("-Scope", "Cockpit")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout.strip())
        self.assertIn("changed during collection", result.stderr)

    def test_cockpit_resolves_controls_installed_for_the_driver_only(self):
        self.environment["OVC_FIRMWARE_FIXTURE_AUTOMOTIVE"] = "driver-user-only"
        result = self.collect("-Scope", "Cockpit", "-EventWindowSeconds", "1")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        report = json.loads(result.stdout)
        controls = next(item for item in report["packages"] if item["package"] == "org.openvoyah.controls")
        self.assertEqual(hashlib.sha256(APK).hexdigest(), controls["apk_sha256"])
        self.assertEqual("granted", report["android_automotive"]["controls_permissions"][ACTIVITY_PERMISSION])
        self.assertEqual("captured", report["cockpit_events"]["status"])
        commands = self.commands()
        self.assertFalse(any(cmd[4:] == ["pm", "path", "org.openvoyah.controls"] for cmd in commands))
        self.assertTrue(any(cmd[4:] == ["pm", "list", "packages", "-U", "--user", "10", "org.openvoyah.controls"]
                            for cmd in commands))

    def test_cockpit_rejects_apk_paths_outside_the_expected_package_directories(self):
        for case in ("outside-path", "dot-path", "wrong-tilde"):
            with self.subTest(case=case):
                self.log.unlink(missing_ok=True)
                self.environment["OVC_FIRMWARE_FIXTURE_AUTOMOTIVE"] = case
                result = self.collect("-Scope", "Cockpit")
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                report = json.loads(result.stdout)
                controls = next(item for item in report["packages"] if item["package"] == "org.openvoyah.controls")
                self.assertIn("unavailable", controls)
                self.assertEqual({"unknown"}, set(report["android_automotive"]["controls_permissions"].values()))
                self.assertFalse(any("check-permission" in cmd for cmd in self.commands()))
                self.assertFalse(any(cmd[-1].endswith("/base.apk") for cmd in self.commands() if "sha256sum" in cmd))

    def test_cockpit_grants_require_the_same_foreground_user_and_signed_controls_apk(self):
        cases = {"bad-user": False, "path-mismatch": False, "user-changed": True,
                 "path-changed": True, "hash-changed": True, "app-missing": False}
        for case, permission_checked in cases.items():
            with self.subTest(case=case):
                self.log.unlink(missing_ok=True)
                self.environment["OVC_FIRMWARE_FIXTURE_AUTOMOTIVE"] = case
                self.environment["OVC_FIRMWARE_FIXTURE_EVENTS"] = case
                result = self.collect("-Scope", "Cockpit")
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                readiness = json.loads(result.stdout)["android_automotive"]
                self.assertEqual(("present", "present"), (readiness["automotive_feature"], readiness["car_library"]))
                self.assertEqual(dict.fromkeys((ACTIVITY_PERMISSION, SELECTOR_PERMISSION, SEATS_PERMISSION), "unknown"),
                                 readiness["controls_permissions"])
                self.assertEqual(expected_headlight_configs(), readiness["headlight_properties"])
                self.assertEqual(permission_checked, any("check-permission" in row for row in self.commands()))

    def test_cockpit_handoff_exports_only_typed_factory_navigation(self):
        self.environment["OVC_FIRMWARE_FIXTURE_EVENTS"] = "handoff"
        result = self.collect("-Scope", "Cockpit", "-EventWindowSeconds", "1")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        window = json.loads(result.stdout)["cockpit_events"]
        self.assertEqual("captured", window["status"])
        record = {"event": "driver_memory_handoff", "logged_at_ms": 1700000001145}
        expected = [
            {**record, "slot": slot, "action": action, "target": target, "outcome": outcome, "elapsed_ms": 0}
            for slot in (1, 2, 3) for action in ("save", "restore") for target in ("seats", "vehicle")
            for outcome in ("requested", "unavailable", "failed")
        ]
        expected.append({**record, "slot": 2, "action": "save", "target": "seats", "outcome": "requested",
                         "elapsed_ms": 2**63 - 1})
        self.assertEqual(expected, window["records"])
        self.assertNotIn("PRIVATE_RECORD_MUST_NOT_APPEAR", result.stdout + result.stderr)

    def test_cockpit_vehicle_workflows_preserve_typed_outcomes_without_private_payloads(self):
        self.environment["OVC_FIRMWARE_FIXTURE_EVENTS"] = "vehicle_workflows"
        result = self.collect("-Scope", "Cockpit", "-EventWindowSeconds", "1")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        window = json.loads(result.stdout)["cockpit_events"]
        self.assertEqual("captured", window["status"])
        self.assertEqual([
            {"event": "vehicle_observer", "state": "model_unreviewed", "product": "f6808"},
            {"event": "vehicle_observer", "outcome": "read_failed", "failure": "SecurityException"},
            {"event": "vehicle_automation_monitor", "outcome": "started"},
            {"event": "vehicle_automation_monitor", "outcome": "stopped"},
            {"event": "vehicle_workflow_runtime", "outcome": "started", "background": True},
            {"event": "vehicle_workflow_runtime", "outcome": "stopped"},
            {"event": "conditional_rule_attempt", "rule": "12345678-1234-4234-9234-123456789abc", "outcome": "unconfirmed"},
            {"event": "conditional_rule_handoff", "rule": "12345678-1234-4234-9234-123456789abc", "outcome": "completed"},
            {"event": "vehicle_automation_dispatch", "kind": "headlight", "command": "LowBeam", "outcome": "requested"},
            {"event": "vehicle_automation_dispatch", "kind": "driving_mode", "command": "EcoMode", "outcome": "unconfirmed"},
            {"event": "seasonal_climate_automatic", "season": "winter", "outcome": "partial", "completed": 2},
            {"event": "seasonal_climate_automatic", "season": "summer", "outcome": "recent", "completed": 0},
            {"event": "vehicle_signal", "signal": "gear", "value": "drive", "outcome": "observed", "elapsed_ns": 42,
             "session": "12345678-1234-4234-9234-123456789abc"},
            {"event": "vehicle_signal", "signal": "outside_temperature", "value": "-12.5", "outcome": "observed", "elapsed_ns": 43,
             "session": "12345678-1234-4234-9234-123456789abc"},
            {"event": "vehicle_signal", "signal": "door_driver", "value": "unknown", "outcome": "unavailable", "elapsed_ns": 44,
             "session": "12345678-1234-4234-9234-123456789abc"},
        ], [{k: v for k, v in record.items() if k != "logged_at_ms"} for record in window["records"]])
        self.assertNotIn("PRIVATE_RECORD_MUST_NOT_APPEAR", result.stdout + result.stderr)

    def test_cockpit_event_window_exports_only_typed_events_for_the_verified_process(self):
        result = self.collect("-Scope", "Cockpit", "-EventWindowSeconds", "1")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        window = json.loads(result.stdout)["cockpit_events"]
        self.assertEqual("captured", window["status"])
        self.assertEqual((1700000001000, 1700000002000), (window["start_epoch_ms"], window["end_epoch_ms_exclusive"]))
        self.assertEqual(256, window["log_entry_limit"])
        self.assertEqual([
            {"event": "headlight_power", "trigger": "screen_off", "interactive": False, "elapsed_ms": 8100},
            {"event": "headlight_power", "trigger": "screen_on", "interactive": True, "elapsed_ms": 8110},
            {"event": "headlight_source", "source": "qinggan", "outcome": "waiting_for_change"},
            {"event": "headlight_source", "source": "qinggan", "outcome": "unsupported_product"},
            {"event": "headlight_source", "source": "qinggan", "outcome": "service_not_found"},
            {"event": "headlight_source", "source": "qinggan", "outcome": "reader_failed",
             "failure": "BadParcelableException", "elapsed_ms": 8111},
            {"event": "headlight_source", "source": "android_automotive", "outcome": "reader_failed",
             "failure": "LinkageError", "elapsed_ms": 8112},
            {"event": "headlight_feedback", "source": "qinggan", "mode": "automatic", "elapsed_ms": 8120,
             "confirmed_at_ms": 1700000001100},
            {"event": "headlight_feedback", "source": "qinggan", "mode": "low_beam", "elapsed_ms": 8130,
             "confirmed_at_ms": 1700000001120},
            {"event": "headlight_feedback", "source": "qinggan", "mode": "position", "elapsed_ms": 8200,
             "confirmed_at_ms": 1700000001145},
            {"event": "headlight_feedback", "source": "qinggan", "mode": "off", "elapsed_ms": 8240,
             "confirmed_at_ms": 1700000001160},
            {"event": "headlight_feedback", "source": "android_automotive", "mode": "on", "elapsed_ms": 8300,
             "confirmed_at_ms": 1700000001200},
            {"event": "driver_memory_guidance", "slot": 2, "outcome": "requested"},
            {"event": "driver_memory_guidance", "slot": 3, "outcome": "failed"},
        ], [{k: v for k, v in record.items() if k != "logged_at_ms"} for record in window["records"]])
        self.assertTrue(all(record["logged_at_ms"] == 1700000001145 for record in window["records"]))
        self.assertNotIn("PRIVATE_RECORD_MUST_NOT_APPEAR", result.stdout + result.stderr)

    def test_cockpit_activity_records_require_an_exact_automotive_lamp_sample(self):
        self.environment["OVC_FIRMWARE_FIXTURE_EVENTS"] = "activity"
        result = self.collect("-Scope", "Cockpit", "-EventWindowSeconds", "1")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        window = json.loads(result.stdout)["cockpit_events"]
        self.assertEqual("captured", window["status"])
        self.assertEqual([
            {"event": "headlight_activity", "source": "android_automotive", "lamp": lamp, "activity": activity,
             "elapsed_ms": 0, "confirmed_at_ms": 1, "logged_at_ms": 1700000001145}
            for lamp, activities in (("headlights", ("off", "on", "daytime_running")), ("high_beam", ("off", "on")))
            for activity in activities
        ], window["records"])
        self.assertNotIn("PRIVATE_RECORD_MUST_NOT_APPEAR", result.stdout + result.stderr)

    def test_cockpit_reader_reports_subscription_diagnostics_without_lamp_confirmations(self):
        self.environment["OVC_FIRMWARE_FIXTURE_EVENTS"] = "reader"
        result = self.collect("-Scope", "Cockpit", "-EventWindowSeconds", "1")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        window = json.loads(result.stdout)["cockpit_events"]
        self.assertEqual("captured", window["status"])
        record = {"event": "headlight_reader", "source": "android_automotive", "logged_at_ms": 1700000001145}
        expected = [
            {**record, "signal": signal, "outcome": outcome, "elapsed_ms": 0}
            for signal in ("selector", "headlights", "high_beam")
            for outcome in ("unsupported_system", "permission_not_granted", "not_connected",
                            "property_not_listed", "incompatible_config", "registration_rejected",
                            "access_denied", "read_failed", "subscribed")
        ]
        expected.append({**record, "signal": "headlights", "outcome": "subscribed", "elapsed_ms": 2**63 - 1})
        self.assertEqual(expected, window["records"])
        self.assertNotIn("PRIVATE_RECORD_MUST_NOT_APPEAR", result.stdout + result.stderr)

    def test_cockpit_event_limit_is_explicit(self):
        self.environment["OVC_FIRMWARE_FIXTURE_EVENTS"] = "full"
        result = self.collect("-Scope", "Cockpit", "-EventWindowSeconds", "1")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        window = json.loads(result.stdout)["cockpit_events"]
        self.assertEqual("record_limit_reached", window["status"])
        self.assertEqual(256, len(window["records"]))

    def test_cockpit_event_window_rejects_missing_mismatched_or_restarted_processes(self):
        cases = {"app-missing": "app_unverified", "ambiguous": "process_unavailable",
                 "other-owner": "process_unavailable", "restarted": "identity_changed",
                 "denied": "log_unavailable", "clock-jump": "window_unavailable", "driver-changed": "identity_changed"}
        for case, status in cases.items():
            with self.subTest(case=case):
                self.log.unlink(missing_ok=True)
                self.environment["OVC_FIRMWARE_FIXTURE_EVENTS"] = case
                result = self.collect("-Scope", "Cockpit", "-EventWindowSeconds", "1")
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual({"status": status, "records": []}, json.loads(result.stdout)["cockpit_events"])
                self.assertEqual(case in {"restarted", "denied", "driver-changed"}, any("logcat" in cmd for cmd in self.commands()))
                self.assertNotIn("PRIVATE_RECORD_MUST_NOT_APPEAR", result.stdout + result.stderr)

    def test_event_window_requires_cockpit_scope_before_any_device_read(self):
        result = self.collect("-EventWindowSeconds", "1")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.commands())

    def test_explicit_java_home_supports_an_unbundled_sdk(self):
        self.java.unlink()
        external = self.root / "explicit-jdk/bin/java.exe"
        external.parent.mkdir(parents=True)
        shutil.copyfile(self.executable, external)
        self.assert_collected(self.collect("-JavaHome", str(external.parent.parent)), external)

    def test_missing_signer_jar_stops_before_any_device_read(self):
        (self.sdk / "build-tools/35.0.0/lib/apksigner.jar").unlink()
        result = self.collect()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("apksigner.jar", result.stderr)
        self.assertEqual([], self.commands())

    def test_missing_java_stops_before_any_device_read(self):
        self.java.unlink()
        result = self.collect()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Provide -JavaHome", result.stderr)
        self.assertEqual([], self.commands())

    def test_incomplete_java_runtime_stops_before_adb(self):
        self.environment["OVC_FIRMWARE_FIXTURE_JAVA_FAIL"] = "1"
        result = self.collect()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("APK signature verifier", result.stderr)
        self.assertEqual(["java.exe"], [Path(row[0]).name for row in self.commands()])

    def test_build_tools_path_traversal_is_rejected_before_tool_execution(self):
        result = self.collect("-BuildToolsVersion", "../35.0.0")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.commands())

    def test_unavailable_provider_never_guesses_its_package(self):
        for state in ("missing", "ambiguous"):
            with self.subTest(state=state):
                self.environment["OVC_FIRMWARE_FIXTURE_PROVIDER"] = state
                result = self.collect()
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                report = json.loads(result.stdout)
                provider = report["account_evidence"]["provider"]
                self.assertEqual("unavailable" if state == "missing" else state, provider["status"])
                self.assertNotIn("package", provider)
                self.assertEqual(8, len(report["packages"]))
                self.assertEqual([], report["account_evidence"]["diagnostic_packages"])
                self.assertNotIn("unrelated.package", result.stdout)

    def test_account_metadata_classification_never_contains_record_values(self):
        for state in ("absent", "linked", "non-regular", "unavailable", "unstable", "empty", "oversized",
                      "malformed", "duplicate", "error"):
            with self.subTest(state=state):
                self.environment["OVC_FIRMWARE_FIXTURE_ACCOUNT"] = state
                result = self.collect()
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                record = json.loads(result.stdout)["account_evidence"]["account_file"]
                self.assertEqual("unavailable" if state in ("malformed", "duplicate", "error") else state,
                                 record["status"])
                self.assertFalse(record["content_collected"])
                self.assertEqual("not-observed", record["record_classification"])
                self.assertNotIn("PRIVATE_RECORD_MUST_NOT_APPEAR", result.stdout + result.stderr)
                self.assertNotIn("sha256", record)

    def test_manifest_failure_does_not_claim_an_empty_component_inventory(self):
        for failure in ("FAIL", "EMPTY"):
            with self.subTest(failure=failure):
                key = f"OVC_FIRMWARE_FIXTURE_MANIFEST_{failure}"
                self.environment[key] = "1"
                result = self.collect()
                self.environment.pop(key)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                report = json.loads(result.stdout)
                self.assertTrue(all(package["account_components"] == {"status": "unavailable"}
                                    for package in report["packages"] + report["account_evidence"]["diagnostic_packages"]
                                    if "account_components" in package))

    def test_stalled_adb_read_has_a_deadline_and_hides_native_error_output(self):
        helper = self.script.read_text(encoding="utf-8").split("function Invoke-AdbRead", 1)[1].split("$devices =", 1)[0]
        harness = self.root / "bounded-read.ps1"
        harness.write_text("$ErrorActionPreference = 'Stop'\n$adb = $args[0]\nfunction Invoke-AdbRead" + helper
                           + "Invoke-AdbRead @('devices', '-l') -TimeoutSeconds 1\n", encoding="utf-8")
        self.environment["OVC_FIRMWARE_FIXTURE_ADB_DELAY"] = "1"
        before = time.monotonic()
        result = subprocess.run([POWERSHELL, "-NoProfile", "-NonInteractive", "-File", str(harness),
                                 str(self.sdk / "platform-tools/adb.exe")], capture_output=True, text=True,
                                encoding="utf-8", timeout=8, env=self.environment)
        self.assertNotEqual(0, result.returncode)
        self.assertLess(time.monotonic() - before, 7)
        self.assertIn("timed out", result.stderr)
        self.assertNotIn("PRIVATE_RECORD_MUST_NOT_APPEAR", result.stdout + result.stderr)

    def test_account_observation_script_has_no_content_read_or_device_mutation(self):
        source = self.script.read_text(encoding="utf-8")
        script = source.split("$script = @'", 1)[1].split("'@", 1)[0]
        self.assertIn("stat -c", script)
        self.assertIn("readlink -f", script)
        for command in ("cat ", "dd ", "sha256sum", "content ", "setprop", "chmod", "chown", "mv ", "cp ", "su "):
            self.assertNotIn(command, script)


if __name__ == "__main__":
    unittest.main()
