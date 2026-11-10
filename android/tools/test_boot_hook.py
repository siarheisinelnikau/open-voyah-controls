"""Exercise the real recovery operations against isolated filesystem and process fixtures."""
import base64
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SOURCE = Path(__file__).with_name("configure-boot.sh")
BASH = r"C:\Program Files\Git\bin\bash.exe" if os.name == "nt" else shutil.which("bash")
FINGERPRINT = "qg/test/device:11/TEST/1:user/test-keys"
SERIAL = "test-device"
BOOT_ID = "73bd791c-ae8c-401a-b321-8ca0d9fe3fc4"
CONTEXT = "u:object_r:system_file:s0"
STOCK = b"#!/system/bin/sh\nprintf 'stock logcat\\n'\n"


def posix_path(path):
    if os.name != "nt":
        return path.as_posix()
    return "/" + path.drive[0].lower() + path.as_posix()[len(path.drive):]


class BootHookTest(unittest.TestCase):
    def setUp(self):
        workspace = Path(__file__).resolve().parents[1]
        temp = (workspace / "build" / "boot-recovery-fixtures").resolve()
        self.assertTrue(temp.is_relative_to(workspace) and temp != workspace)
        temp.mkdir(parents=True, exist_ok=True)
        directory = tempfile.TemporaryDirectory(dir=temp)
        self.addCleanup(directory.cleanup)
        self.root = root = Path(directory.name)
        system, data, adb, proc = (root / name for name in ("system", "local", "adb", "proc"))
        for folder in (system, data, adb, proc):
            folder.mkdir(mode=0o700)
        self.boot = system / "init.logcat.sh"
        self.boot.write_bytes(STOCK)
        self.boot.chmod(0o755)
        self.backup = adb / "open-voyah-controls-boot"
        self.runtime = adb / "open-voyah-controls"
        self.legacy = data / "open-voyah-controls"
        self.runtime.mkdir(mode=0o700)
        (self.runtime / "boot-start.sh").write_text("#!/bin/sh\nexit 0\n", newline="\n")
        (self.runtime / "boot-start.sh").chmod(0o700)
        worker_hash = self.hash(self.runtime / "boot-start.sh")
        receipt = self.runtime / "trust.tsv"
        receipt.write_text(f"artifact\tboot-start.sh\t{worker_hash}\n", newline="\n")
        receipt.chmod(0o600)
        self.proc = proc
        sys_random = proc / "sys" / "kernel" / "random"
        sys_random.mkdir(parents=True)
        self.boot_id = sys_random / "boot_id"
        self.boot_id.write_text(BOOT_ID + "\n", newline="\n")
        (sys_random / "uuid").write_text(BOOT_ID + "\n", newline="\n")
        self.settings = root / "settings"
        self.settings.write_text("unrelated=keep\n", newline="\n")
        self.signals = root / "signals"
        self.script = root / "configure.sh"
        self.source = SOURCE.read_text(encoding="utf-8")
        for original, replacement in (("/system/etc", system), ("/data/adb", adb), ("/data/local/tmp", data), ("/proc", proc)):
            self.source = self.source.replace(original, posix_path(replacement))
        self.source = self.source.replace("export PATH=/system/bin:/system/xbin", ":")
        self.source = self.source.replace('/system/bin/sh -n "$stage"', 'bash -n "$stage"')
        self.source = self.source.replace('/system/bin/sh "$private/boot-start.sh" --verify', 'bash "$private/boot-start.sh" --verify')
        self.source = self.source.replace('start_of() {\n', 'start_of() {\n    if [ "$1" = "$$" ]; then echo 100; return; fi\n')
        # Git Bash on Windows cannot represent POSIX chmod on files. Emulate modes using
        # the fixture name. On Linux all checks still exercise the shell control flow.
        modes = (
            'case "$3" in */trust.tsv|*/recovery.*) echo 600;; '
            '*/init.logcat.sh|*/init.logcat.ovc.*|*/original|*/original.*) echo 755;; '
            '*/boot-start.sh|*/adb|*/local|*/open-voyah-controls|*/open-voyah-controls-boot|*/exported.*) echo 700;; '
            '*) echo 700;; esac'
        ) if os.name == "nt" else 'command stat -c %a "$3"'
        mocks = f'''
id() {{ echo 0; }}
fixture_mode() {{ {modes}; }}
stat() {{
    case "$2" in
        %u) echo 0 ;;
        %a) fixture_mode "$@" ;;
        %u:%a) printf '0:%s\\n' "$(fixture_mode "$@")" ;;
        %u:%g:%a) printf '0:0:%s\\n' "$(fixture_mode "$@")" ;;
        *) command stat "$@" ;;
    esac
}}
getprop() {{
    case "$1" in
        ro.build.fingerprint) echo '{FINGERPRINT}';;
        ro.build.version.sdk) echo 30;;
        ro.serialno) echo '{SERIAL}';;
        ro.product.cpu.abi) echo arm64-v8a;;
    esac
}}
ls() {{ echo '{CONTEXT}'; }}
getenforce() {{ echo Enforcing; }}
chown() {{ :; }}
chcon() {{ :; }}
sync() {{ :; }}
sleep() {{ :; }}
am() {{ if [ "$1" = get-current-user ]; then echo 0; fi; }}
readlink() {{ command cat "$1"; }}
settings() {{
    case "$1" in
        list) command cat '{posix_path(self.settings)}' ;;
        put|delete)
            awk -F= -v key="$3" '$1 != key' '{posix_path(self.settings)}' > '{posix_path(root)}/settings.tmp'
            mv -f '{posix_path(root)}/settings.tmp' '{posix_path(self.settings)}'
            if [ "$1" = put ]; then printf '%s=%s\\n' "$3" "$4" >> '{posix_path(self.settings)}'; fi ;;
        *) return 1 ;;
    esac
}}
kill() {{
    [ "$1" = -TERM ] || return 1
    case "$2" in 401|402|403) ;; *) return 1;; esac
    echo "$2" >> '{posix_path(self.signals)}'
    : > '{posix_path(proc)}'/"$2"/cmdline
}}
'''
        self.source = self.source.replace("#!/system/bin/sh\n", "#!/system/bin/sh\n" + mocks, 1)

    @staticmethod
    def hash(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def configure(self, mode, *args, fingerprint=FINGERPRINT, serial=SERIAL, exported=None):
        prefix = ""
        if exported is not None:
            encoded = base64.b64encode(exported).decode("ascii")
            prefix = "ovc_external_original() { base64 -d <<'OVC_ORIGINAL_BYTES'\n" + encoded + "\nOVC_ORIGINAL_BYTES\n}\n"
        self.script.write_text(prefix + self.source, encoding="utf-8", newline="\n")
        return subprocess.run([BASH, str(self.script), mode, fingerprint, serial, *args], capture_output=True,
                              text=True, timeout=35)

    def assert_success(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def create_process(self, pid, args, exe, start):
        directory = self.proc / str(pid)
        directory.mkdir()
        (directory / "cmdline").write_bytes(("\0".join(args) + "\0").encode())
        (directory / "exe").write_text(exe + "\n", newline="\n")
        (directory / "stat").write_text(f"{pid} (fixture) S " + "0 " * 18 + f"{start}\n", newline="\n")

    def ready_launcher(self):
        runtime = posix_path(self.runtime)
        self.create_process(401, ["/system/bin/sh", runtime + "/supervise.sh"], "/system/bin/mksh", 21)
        injector = runtime + "/open-voyah-controls-frida-17.18.0"
        self.create_process(402, [injector, "-p", "70", "-s", runtime + "/launcher-buttons.js"], injector, 22)
        (self.runtime / "ready").write_text("OVC_READY NavigationBar click+hold pid=70\n", newline="\n")

    def install(self):
        backup = self.configure("backup")
        self.assert_success(backup)
        self.assertIn("OVC_BACKUP_SHA256=" + self.hash(self.boot), backup.stdout)
        self.assertEqual(STOCK, (self.backup / "original").read_bytes())
        self.ready_launcher()
        result = self.configure("install", self.hash(self.backup / "original"))
        self.assert_success(result)

    def test_backup_install_restore_and_idempotency(self):
        self.install()
        self.assertIn(b"OPEN_VOYAH_CONTROLS_BOOT_V2", self.boot.read_bytes())
        again = self.configure("install", self.hash(self.backup / "original"))
        self.assert_success(again)
        self.assertIn("already-installed", again.stdout)
        self.assert_success(self.configure("restore"))
        self.assertEqual(STOCK, self.boot.read_bytes())
        self.assertIn("entry-absent", self.configure("restore").stdout)

    def test_install_requires_verified_backup_and_live_launcher(self):
        bad = self.configure("install", self.hash(self.boot))
        self.assertNotEqual(0, bad.returncode)
        self.assert_success(self.configure("backup"))
        self.assertNotEqual(0, self.configure("install", self.hash(self.boot)).returncode)
        self.ready_launcher()
        self.assertNotEqual(0, self.configure("install", "0" * 64).returncode)
        self.assertEqual(STOCK, self.boot.read_bytes())

    def test_wrong_identity_and_changed_boot_are_refused(self):
        self.assertNotEqual(0, self.configure("backup", fingerprint="unknown-device").returncode)
        self.assertNotEqual(0, self.configure("backup", serial="other-device").returncode)
        self.assertEqual(STOCK, self.boot.read_bytes())
        self.install()
        modified = self.boot.read_bytes() + b"# Local change\n"
        self.boot.write_bytes(modified)
        result = self.configure("rollback")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("OVC_BOOT_RESTORE=unconfirmed", result.stdout)
        self.assertEqual(modified, self.boot.read_bytes())

    def test_rollback_keeps_native_state_unconfirmed_until_reboot(self):
        self.install()
        (self.runtime / "enabled").write_text("", newline="\n")
        self.settings.write_text("unrelated=keep\norg.openvoyah.controls.windowRequest=fixture\n", newline="\n")
        result = self.configure("rollback")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("OVC_ROLLBACK_VISUALS=reboot-required", result.stdout)
        self.assertIn("OVC_ROLLBACK_INERT=no", result.stdout)
        self.assertEqual({"401", "402"}, set(self.signals.read_text().splitlines()))
        self.assertTrue((self.runtime / "recovery.blocked").is_file())
        self.assertEqual(STOCK, self.boot.read_bytes())
        self.assertEqual("unrelated=keep\n", self.settings.read_text())
        self.boot_id.write_text("6277ee31-9931-442a-9dc8-3bbbf3ff562e\n", newline="\n")
        self.assertIn("OVC_ROLLBACK_INERT=yes", self.configure("status").stdout)
        self.assert_success(self.configure("rearm"))
        self.assertFalse((self.runtime / "recovery.blocked").exists())

    def test_unknown_process_is_never_signalled(self):
        runtime = posix_path(self.runtime)
        self.create_process(403, ["/system/bin/sh", runtime + "/supervise.sh"], "/system/bin/unrelated", 25)
        status = self.configure("status")
        self.assertIn("OVC_RUNTIME=unknown", status.stdout)
        result = self.configure("rollback")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.signals.exists())
        self.assertIn("OVC_ROLLBACK_INERT=no", result.stdout)

    def test_stale_owner_does_not_remove_a_new_claim(self):
        self.backup.mkdir(mode=0o700)
        (self.backup / "recovery.owner").write_text(BOOT_ID + ":99999:77\n", newline="\n")
        self.assert_success(self.configure("backup"))
        self.assertFalse((self.backup / "recovery.owner").exists())
        self.create_process(403, ["fixture"], "/system/bin/unrelated", 23)
        (self.backup / "recovery.owner").write_text(BOOT_ID + ":403:23\n", newline="\n")
        result = self.configure("backup")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Another recovery operation is active", result.stderr)

    def test_pc_backup_restores_exact_v2_after_device_copy_is_lost(self):
        self.install()
        for item in ("original", "original.sha256"):
            (self.backup / item).unlink()
        stock_hash = hashlib.sha256(STOCK).hexdigest()
        result = self.configure("restore-exported", stock_hash, CONTEXT, "0:0", "755", exported=STOCK)
        self.assertIn("OVC_BOOT_RESTORE=restored", result.stdout, result.stdout + result.stderr)
        self.assertEqual(STOCK, self.boot.read_bytes())
        self.assertFalse((self.backup / "original").exists())

    def test_export_does_not_overwrite_unrecognized_v2(self):
        self.install()
        (self.backup / "original").unlink()
        modified = self.boot.read_bytes() + b"# Changed generation\n"
        self.boot.write_bytes(modified)
        stock_hash = hashlib.sha256(STOCK).hexdigest()
        result = self.configure("restore-exported", stock_hash, CONTEXT, "0:0", "755", exported=STOCK)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.signals.exists())
        self.assertEqual(modified, self.boot.read_bytes())

    def test_stopped_legacy_staging_blocks_until_retired(self):
        self.legacy.mkdir(mode=0o700)
        for name in ("boot-start.sh", "supervise.sh", "launcher-buttons.js", "localization.js", "operator-notes"):
            (self.legacy / name).write_text("fixture\n", newline="\n")
        window = self.legacy / "window"
        window.mkdir(mode=0o700)
        (window / "launcher-buttons.js").write_text("fixture\n", newline="\n")
        self.assertIn("OVC_LEGACY_STATE=active", self.configure("status").stdout)
        result = self.configure("rollback")
        self.assert_success(result)
        self.assertIn("OVC_LEGACY_STATE=inert", result.stdout)
        self.assertFalse((window / "launcher-buttons.js").exists())
        self.assertEqual(["operator-notes", "window"], sorted(file.name for file in self.legacy.iterdir()))


if __name__ == "__main__":
    unittest.main()
