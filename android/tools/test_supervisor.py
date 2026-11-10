"""Offline fixtures for the Android boot worker's private trust and retry gates."""
from pathlib import Path
import hashlib
import os
import subprocess
import tempfile
import unittest

from test_boot_hook import BASH, BOOT_ID, FINGERPRINT, posix_path


ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets/hooks"
RUNTIME = "open-voyah-controls-frida-17.18.0"
RUNTIME_SHA = "d8ce6fe18db97594d1c7a43be4f8fb405fc76f6a01470df34504bca6461328d0"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class SupervisorTest(unittest.TestCase):
    def setUp(self):
        workspace = Path(__file__).resolve().parents[1]
        fixture_parent = (workspace / "build/supervisor-fixtures").resolve()
        self.assertTrue(fixture_parent.is_relative_to(workspace) and fixture_parent != workspace)
        fixture_parent.mkdir(parents=True, exist_ok=True)
        directory = tempfile.TemporaryDirectory(dir=fixture_parent)
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        adb, local, self.proc = (self.root / name for name in ("adb", "local", "proc"))
        for path in (adb, local, self.proc):
            path.mkdir(mode=0o700)
        self.base = adb / "open-voyah-controls"
        self.base.mkdir(mode=0o700)
        self.app = self.root / "controls.apk"
        self.launcher = self.root / "launcher.apk"
        self.app.write_bytes(b"approved app")
        self.launcher.write_bytes(b"approved OEM launcher")
        self.injector = self.base / RUNTIME
        self.injector.write_text(
            "#!/usr/bin/env bash\nprintf 'started\\n' > '" + posix_path(self.root / "started") + "'\n",
            newline="\n",
        )
        self.injector.chmod(0o500)
        (self.base / "launcher-buttons.js").write_text("// approved agent\n", newline="\n")
        (self.base / "launcher-buttons.js").chmod(0o600)
        self.record = self.base / "trust.tsv"
        boot = self.proc / "sys/kernel/random"
        boot.mkdir(parents=True)
        (boot / "boot_id").write_text(BOOT_ID + "\n", newline="\n")
        self.modes = (
            'case "$3" in */trust.tsv|*/launcher-buttons.js|*/worker.owner) echo 600;; '
            '*/' + RUNTIME + ') echo 500;; *) echo 700;; esac'
        ) if os.name == "nt" else 'command stat -c %a "$3"'
        mocks = f'''
id() {{ echo 0; }}
fixture_mode() {{ {self.modes}; }}
stat() {{
    case "$2" in
        %u) echo 0;;
        %u:%a) printf '0:%s\\n' "$(fixture_mode "$@")";;
        *) command stat "$@";;
    esac
}}
getprop() {{
    case "$1" in
        ro.build.fingerprint) echo '{FINGERPRINT}';;
        ro.build.version.sdk) echo 30;;
        ro.product.cpu.abi) echo arm64-v8a;;
        ro.product.name) echo qg-fixture;;
    esac
}}
getenforce() {{ echo Enforcing; }}
am() {{ if [ "$1" = get-current-user ]; then echo "${{OVC_TEST_OWNER:-0}}"; fi; }}
pm() {{
    case "$4" in
        org.openvoyah.controls) echo 'package:{posix_path(self.app)}';;
        com.qinggan.app.launcher) echo 'package:{posix_path(self.launcher)}';;
        *) return 1;;
    esac
}}
kill() {{ printf 'signal\\n' >> '{posix_path(self.root / "signals")}'; return 1; }}
'''
        self.sources = {}
        for name in ("supervise.sh", "boot-start.sh"):
            source = (ASSETS / name).read_text(encoding="utf-8")
            for old, new in (("/data/adb", adb), ("/data/local/tmp", local), ("/proc", self.proc)):
                source = source.replace(old, posix_path(new))
            source = source.replace("export PATH=/system/bin:/system/xbin", ":")
            source = source.replace("exec /system/bin/sh ", 'exec "$BASH" ')
            source = source.replace('start_of() {\n', 'start_of() {\n    if [ "$1" = "$$" ]; then echo 100; return; fi\n')
            source = source.replace(RUNTIME_SHA, digest(self.injector))
            source = source.replace("#!/system/bin/sh\n", "#!/system/bin/sh\n" + mocks, 1)
            self.sources[name] = source
            (self.base / name).write_text(source, encoding="utf-8", newline="\n")
            (self.base / name).chmod(0o700)
        self.write_receipt()

    def write_receipt(self):
        artifacts = [RUNTIME, "boot-start.sh", "supervise.sh", "launcher-buttons.js"]
        rows = [f"device\t{FINGERPRINT}\t30\tarm64-v8a\tqg-fixture"]
        for name, path in (("org.openvoyah.controls", self.app), ("com.qinggan.app.launcher", self.launcher)):
            rows.append(f"package\t{name}\t{posix_path(path)}\t{digest(path)}")
        rows.extend(f"artifact\t{name}\t{digest(self.base / name)}" for name in artifacts)
        self.record.write_text("\n".join(rows) + "\n", newline="\n")
        self.record.chmod(0o600)

    def run_script(self, name, *args, owner="0"):
        return subprocess.run([BASH, str(self.base / name), *args], capture_output=True, text=True,
                              timeout=30, env={**os.environ, "OVC_TEST_OWNER": owner})

    def test_reviewed_owner_deployment_and_changed_package(self):
        result = self.run_script("boot-start.sh", "--verify")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("OVC_BOOT_VERIFIED", result.stdout)
        self.assertNotEqual(0, self.run_script("boot-start.sh", "--verify", owner="10").returncode)
        self.launcher.write_bytes(b"changed OEM launcher")
        self.assertNotEqual(0, self.run_script("boot-start.sh", "--verify").returncode)
        self.assertFalse((self.root / "started").exists())

    def test_runtime_pin_is_checked_independently_of_receipt(self):
        self.injector.chmod(0o700)
        self.injector.write_bytes(b"changed injector")
        self.injector.chmod(0o500)
        self.write_receipt()
        self.assertNotEqual(0, self.run_script("supervise.sh", "--verify-boot").returncode)

    def test_existing_legacy_entry_prevents_production_attachment(self):
        old = self.root / "local/open-voyah-controls"
        old.mkdir()
        (old / "enabled").write_text("", newline="\n")
        self.assertNotEqual(0, self.run_script("supervise.sh", "--verify-boot").returncode)

    def test_retry_quarantine_does_not_attach_again_this_boot(self):
        for name, contents in (("failure.boot", BOOT_ID + "\n"), ("failure.count", "5\n"), ("enabled", "")):
            (self.base / name).write_text(contents, newline="\n")
        result = self.run_script("supervise.sh")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Retry limit reached for this boot", (self.base / "worker.log").read_text())
        self.assertFalse((self.root / "started").exists())

    def test_unobservable_child_is_not_confirmed_stopped(self):
        (self.proc / "800").mkdir()
        source = self.sources["supervise.sh"].split('if [ "$channel" = --verify-boot ]; then', 1)[0]
        with_unknown = source + 'stop_child 800 123\nexit $?\n'
        result = subprocess.run([BASH, "-s"], input=with_unknown, capture_output=True, text=True, timeout=15)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / "signals").exists())


if __name__ == "__main__":
    unittest.main()
