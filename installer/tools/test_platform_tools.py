"""Validate portable ADB pairing with offline native fixtures; never contact an ADB server."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


INSTALLER = Path(__file__).resolve().parents[1]
HELPER = INSTALLER / "tools/verify-platform-tools.ps1"
POWERSHELL = shutil.which("pwsh")
RUSTC = shutil.which("rustc")
FILES = ("adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll", "source.properties", "NOTICE.txt")


@unittest.skipUnless(os.name == "nt" and POWERSHELL and RUSTC, "Windows, PowerShell and Rust are required")
class PlatformToolsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.parent = (INSTALLER / "target/portable-tools-fixtures").resolve()
        cls.parent.mkdir(parents=True, exist_ok=True)
        cls.compiled = tempfile.TemporaryDirectory(dir=cls.parent)
        compiled_root = Path(cls.compiled.name).resolve()
        assert compiled_root.is_relative_to(cls.parent) and compiled_root != cls.parent
        cls.executable = compiled_root / "adb.exe"
        subprocess.run([RUSTC, "--edition=2024", str(INSTALLER / "tools/fixtures/platform_tools.rs"),
                        "-o", str(cls.executable)], check=True, capture_output=True, text=True, timeout=30)

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
        self.platform = self.root / "SDK with spaces/platform-tools"
        self.platform.mkdir(parents=True)
        shutil.copyfile(self.executable, self.platform / "adb.exe")
        for name in FILES[1:]:
            (self.platform / name).write_bytes(b"matching offline fixture")
        (self.platform / "source.properties").write_text("Pkg.Revision=37.0.1\n", newline="\n")
        (self.platform / "version.txt").write_text(
            "Android Debug Bridge version 1.0.41\nVersion 37.0.1-12345678\n", newline="\n")
        (self.platform / "help.txt").write_text(
            "global options:\n -t ID use device with given transport id\n"
            "general commands:\n devices [-l] list connected devices\n", newline="\n")
        self.log = self.root / "commands.tsv"
        self.environment = os.environ.copy()
        self.environment["OVC_PLATFORM_TOOLS_FIXTURE_LOG"] = str(self.log)

    def cleanup(self):
        self.assertTrue(self.root.is_relative_to(self.parent) and self.root != self.parent)
        self.temp.cleanup()

    def commands(self):
        return [line.split("\t") for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def verify(self, after_source="", timeout_ms=5000):
        harness = self.root / "verify.ps1"
        harness.write_text(
            "param([string]$Helper, [string]$Platform)\n$ErrorActionPreference='Stop'\n"
            ". $Helper\n"
            f"$verified = Get-VerifiedPlatformTools -Directory $Platform -TimeoutMilliseconds {timeout_ms}\n"
            + after_source + "\n$verified | ConvertTo-Json -Depth 5\n",
            encoding="utf-8", newline="\n")
        return subprocess.run(
            [POWERSHELL, "-NoProfile", "-NonInteractive", "-File", str(harness),
             "-Helper", str(HELPER), "-Platform", str(self.platform)],
            capture_output=True, text=True, encoding="utf-8", timeout=15, env=self.environment)

    def assert_passed(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("37.0.1", json.loads(result.stdout)["Version"])

    def test_matching_actual_version_and_help_use_only_the_selected_pair(self):
        wrong = self.root / "wrong-path"
        wrong.mkdir()
        shutil.copyfile(self.executable, wrong / "adb.exe")
        self.environment["PATH"] = str(wrong) + os.pathsep + self.environment["PATH"]
        self.assert_passed(self.verify())
        self.assertEqual(["version", "help"], [row[2] for row in self.commands()])
        self.assertTrue(all(Path(row[1]) == self.platform / "adb.exe" for row in self.commands()))

    def test_modern_metadata_cannot_hide_an_old_adb_executable(self):
        (self.platform / "version.txt").write_text("Android Debug Bridge version 1.0.36\n", newline="\n")
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Actual ADB must report supported Platform-Tools 37.0.1", result.stderr)
        self.assertEqual(["version"], [row[2] for row in self.commands()])

    def test_actual_platform_revision_must_match_the_metadata(self):
        (self.platform / "version.txt").write_text(
            "Android Debug Bridge version 1.0.41\nVersion 36.0.2-12345678\n", newline="\n")
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("matching source.properties", result.stderr)

    def test_platform_tools_30_is_the_supported_lower_boundary(self):
        (self.platform / "source.properties").write_text("Pkg.Revision=30.0.0\n", newline="\n")
        (self.platform / "version.txt").write_text(
            "Android Debug Bridge version 1.0.41\nVersion 30.0.0-6374843\n", newline="\n")
        result = self.verify()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("30.0.0", json.loads(result.stdout)["Version"])

    def test_unsupported_platform_tools_are_rejected_before_execution(self):
        (self.platform / "source.properties").write_text("Pkg.Revision=29.0.6\n", newline="\n")
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Platform-Tools 30 or newer", result.stderr)
        self.assertEqual([], self.commands())

    def test_shell_pty_help_is_not_transport_id_capability(self):
        (self.platform / "help.txt").write_text("shell:\n -t: allocate a pty if on a tty\n", newline="\n")
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("transport-ID selection", result.stderr)

    def test_missing_matching_library_stops_before_execution(self):
        (self.platform / "AdbWinApi.dll").unlink()
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("AdbWinApi.dll", result.stderr)
        self.assertEqual([], self.commands())

    def test_duplicate_revision_metadata_is_rejected_before_execution(self):
        (self.platform / "source.properties").write_text("Pkg.Revision=37.0.1\nPkg.Revision=30.0.0\n", newline="\n")
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("one valid Pkg.Revision", result.stderr)
        self.assertEqual([], self.commands())

    def test_changed_source_library_is_rejected_before_packaging(self):
        (self.platform / "change-dll").touch()
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("changed during validation or copying: AdbWinApi.dll", result.stderr)

    def test_copied_pair_is_reprobed_and_bound_to_source_hashes(self):
        result = self.verify("""
$copy = Join-Path (Split-Path $Platform) 'copied-platform-tools'
New-Item -ItemType Directory -Path $copy | Out-Null
Copy-Item -Path (Join-Path $Platform '*') -Destination $copy
$verified = Get-VerifiedPlatformTools -Directory $copy -ExpectedHashes $verified.Hashes
""")
        self.assert_passed(result)
        self.assertEqual(["version", "help", "version", "help"], [row[2] for row in self.commands()])
        self.assertTrue(all(Path(row[1]).parent.name == "copied-platform-tools" for row in self.commands()[2:]))

    def test_copied_library_tampering_is_rejected_before_execution(self):
        result = self.verify("""
$copy = Join-Path (Split-Path $Platform) 'copied-platform-tools'
New-Item -ItemType Directory -Path $copy | Out-Null
Copy-Item -Path (Join-Path $Platform '*') -Destination $copy
Set-Content -LiteralPath (Join-Path $copy 'AdbWinUsbApi.dll') -Value 'different library'
$verified = Get-VerifiedPlatformTools -Directory $copy -ExpectedHashes $verified.Hashes
""")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("changed during validation or copying: AdbWinUsbApi.dll", result.stderr)
        self.assertEqual(["version", "help"], [row[2] for row in self.commands()])

    def test_nonzero_help_exit_is_not_accepted(self):
        (self.platform / "fail-help").touch()
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("failed with exit code 7", result.stderr)

    def test_hung_version_is_bounded_and_only_the_owned_process_is_stopped(self):
        (self.platform / "hang-version").touch()
        result = self.verify(timeout_ms=250)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("did not finish within 250 ms", result.stderr)
        commands = self.commands()
        self.assertEqual(["version"], [row[2] for row in commands])
        process = subprocess.run(
            [POWERSHELL, "-NoProfile", "-NonInteractive", "-Command",
             f"if (Get-Process -Id {int(commands[0][0])} -ErrorAction SilentlyContinue) {{ exit 1 }}"],
            capture_output=True, timeout=10)
        self.assertEqual(0, process.returncode)


if __name__ == "__main__":
    unittest.main()
