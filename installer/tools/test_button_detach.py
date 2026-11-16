"""Exercise the actual removal script with isolated settings fixtures."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASH = r"C:\Program Files\Git\bin\bash.exe" if os.name == "nt" else shutil.which("bash")


class DetachButtonsTest(unittest.TestCase):
    def run_fixture(self, services, fingerprint="fixture/build", reject=False):
        parent = ROOT / "target/button-detach-tests"
        parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=parent) as directory:
            folder = Path(directory)
            (folder / "settings").write_text(services, encoding="utf-8")
            source = (ROOT / "src/recovery.rs").read_text(encoding="utf-8")
            script = re.search(r'const DETACH_BUTTONS: &str = r#"(.*?)"#;', source, re.S).group(1)
            mocks = '''getprop() { case "$1" in ro.build.fingerprint) echo fixture/build;; ro.serialno) echo fixture-unit;; esac; }
am() { echo 0; }
sleep() { :; }
settings() {
    case "$3" in
        get) cat settings;;
        put) printf '%s' "$6" > settings; echo changed >> writes;;
        *) return 1;;
    esac
}
'''
            if reject:
                mocks = mocks.replace('printf \'%s\' "$6" > settings;', ':;')
            path = folder / "detach.sh"
            path.write_text(mocks + script, encoding="utf-8", newline="\n")
            result = subprocess.run([BASH, str(path), fingerprint, "fixture-unit"], cwd=folder,
                                    capture_output=True, text=True, timeout=10)
            return result, (folder / "settings").read_text(), (folder / "writes").exists()

    def test_detaches_both_component_spellings_and_preserves_other_services(self):
        services = "other/.One:org.openvoyah.controls/.ButtonAccessibilityService:third/.Two:org.openvoyah.controls/org.openvoyah.controls.ButtonAccessibilityService"
        result, value, wrote = self.run_fixture(services)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("OVC_BUTTONS_DETACHED\n", result.stdout)
        self.assertEqual("other/.One:third/.Two", value)
        self.assertTrue(wrote)

    def test_absent_service_does_not_rewrite_settings(self):
        result, value, wrote = self.run_fixture("other/.One")
        self.assertEqual(0, result.returncode)
        self.assertEqual("other/.One", value)
        self.assertFalse(wrote)

    def test_wrong_identity_does_not_write(self):
        result, value, wrote = self.run_fixture("org.openvoyah.controls/.ButtonAccessibilityService", "other/build")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(wrote)

    def test_failed_setting_write_never_acknowledges_detachment(self):
        result, value, wrote = self.run_fixture("org.openvoyah.controls/.ButtonAccessibilityService", reject=True)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("OVC_BUTTONS_DETACHED", result.stdout)


if __name__ == "__main__":
    unittest.main()
