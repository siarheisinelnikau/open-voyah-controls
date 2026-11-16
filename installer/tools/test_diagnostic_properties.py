"""Run the installer's property-report script against fake getprop; never contact ADB."""
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import unittest


INSTALLER = Path(__file__).resolve().parents[1]
SOURCE = INSTALLER / "src/device.rs"
SHELL = shutil.which("sh")
ALLOWED = {
    "ro.product.name", "ro.product.device", "ro.product.model", "ro.product.brand", "ro.product.manufacturer",
    "ro.product.cpu.abi", "ro.build.fingerprint", "ro.build.version.release", "ro.build.version.sdk",
    "ro.build.version.incremental", "ro.build.type", "ro.build.tags", "ro.boot.verifiedbootstate",
    "ro.boot.veritymode", "ro.boot.vbmeta.device_state", "ro.boot.flash.locked", "ro.boot.selinux",
    "ro.adb.secure", "ro.debuggable", "service.adb.root", "init.svc.adbd", "sys.boot_completed",
}
SECRET = "PRIVATE_ACCOUNT_PHONE_TOKEN_MUST_NOT_APPEAR"


@unittest.skipUnless(SHELL, "A POSIX shell is required for the isolated script fixture")
class DiagnosticPropertiesTest(unittest.TestCase):
    def setUp(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.script = re.search(r'const DIAGNOSTIC_PROPERTIES: &str = r#"(.*?)"#;', source, re.S).group(1)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.log = Path(self.temp.name) / "properties-requested.txt"

    def report(self, model="Fixture head unit", model_error=False):
        fixture = """
getprop() {
    printf '%%s\\n' "$*" >> %s
    if [ "$#" -ne 1 ]; then
        printf '[qinggan.account.phone]: [%s]\\n[ro.serialno]: [%s]\\n'
        return 0
    fi
    case "$1" in
        ro.product.model) %s printf '%%s' %s;;
        ro.build.fingerprint) printf 'fixture/device:11/TEST/1:user/test-keys';;
        ro.boot.verifiedbootstate) printf green;;
        ro.serialno|*account*|*token*|*phone*|*user*) printf %s;;
        *) printf fixture;;
    esac
}
""" % (shlex.quote(self.log.as_posix()), SECRET, SECRET,
       "return 1;" if model_error else "", shlex.quote(model), SECRET)
        return subprocess.run([SHELL, "-s"], input=fixture + self.script, capture_output=True,
                              text=True, encoding="utf-8", timeout=5)

    def test_only_explicit_nonpersonal_properties_are_requested(self):
        result = self.report()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        requested = self.log.read_text(encoding="utf-8").splitlines()
        self.assertEqual(ALLOWED, set(requested))
        self.assertEqual(len(ALLOWED), len(requested))
        self.assertNotIn(SECRET, result.stdout + result.stderr)
        self.assertNotIn("ro.serialno", result.stdout)
        self.assertIn("[ro.product.model]: [Fixture head unit]", result.stdout)
        self.assertIn("[ro.boot.verifiedbootstate]: [green]", result.stdout)
        lines = result.stdout.splitlines()
        self.assertEqual(len(ALLOWED), len(lines))
        self.assertTrue(all(re.fullmatch(r"\[[a-z0-9_.]+\]: \[[ -~]{0,512}\]", line) for line in lines))

    def test_multiline_and_oversized_values_cannot_inject_diagnostic_records(self):
        for model in ("Fixture\n[qinggan.account.phone]: [" + SECRET + "]", SECRET * 40, "Fixture\x01" + SECRET):
            with self.subTest(model=model[:20]):
                result = self.report(model)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("[ro.product.model]: [<unavailable>]", result.stdout)
                self.assertNotIn(SECRET, result.stdout + result.stderr)
                self.assertEqual(len(ALLOWED), len(result.stdout.splitlines()))

    def test_failed_property_is_reported_without_stopping_collection(self):
        result = self.report(model_error=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("[ro.product.model]: [<unavailable>]", result.stdout)
        self.assertEqual(len(ALLOWED), len(result.stdout.splitlines()))


if __name__ == "__main__":
    unittest.main()
