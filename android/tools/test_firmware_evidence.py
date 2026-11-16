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
