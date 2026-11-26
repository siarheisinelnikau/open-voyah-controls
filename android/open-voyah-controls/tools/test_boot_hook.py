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
        self.source = self.source.replace('/system/bin/sh "$access/access-start.sh"', 'bash "$access/access-start.sh"')
        self.source = self.source.replace('start_of() {\n', 'start_of() {\n    if [ "$1" = "$$" ]; then echo 100; return; fi\n')
        # Git Bash on Windows cannot represent POSIX chmod on files. Emulate modes using
        # the fixture name. On Linux all checks still exercise the shell control flow.
        modes = (
            'case "$3" in */trust.tsv|*/checksums|*/recovery.*|*/visual.session) echo 600;; '
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
        %u:%a:%h) printf '0:%s:%s\\n' "$(fixture_mode "$@")" "$(command stat -c %h "$3")" ;;
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
am() {{
    if [ "$1" = get-current-user ]; then
        if [ -f '{posix_path(root)}/current-user' ]; then cat '{posix_path(root)}/current-user'; else echo 0; fi
    fi
}}
readlink() {{ command cat "$1"; }}
fixture_visual_ack() {{
    case "$1" in
        org.openvoyah.controls.panel)
            fixture_channel=main
            fixture_dir='{posix_path(self.runtime)}'
            fixture_request=$(printf '%s\\n' "$2" | sed -n 's/.*"restoreIcons":"\\([a-f0-9]*\\)".*/\\1/p') ;;
        org.openvoyah.controls.windowRequest)
            fixture_channel=window
            fixture_dir='{posix_path(self.runtime)}/window'
            fixture_request=$(printf '%s\\n' "$2" | sed -n 's/.*"id":"\\([a-f0-9]*\\)".*/\\1/p') ;;
        *) return 0 ;;
    esac
    [ -e '{posix_path(root)}/ack-'"$fixture_channel" ] || return 0
    if [ "$fixture_channel" = main ]; then
        printf 'OVC_PANEL_ICONS_RESTORED %s\\n' "$fixture_request" >> "$fixture_dir/hook.log"
    else
        printf 'OVC_WINDOW_RESULT %s {{"status":"applied"}}\\n' "$fixture_request" >> "$fixture_dir/hook.log"
    fi
    if [ -f '{posix_path(root)}/race-'"$fixture_channel" ]; then
        cp '{posix_path(root)}/race-'"$fixture_channel" "$fixture_dir/visual.session.next"
        mv -f "$fixture_dir/visual.session.next" "$fixture_dir/visual.session"
    fi
}}
settings() {{
    case "$1" in
        list) command cat '{posix_path(self.settings)}' ;;
        get) sed -n "s/^$3=//p" '{posix_path(self.settings)}' ;;
        put|delete)
            awk -F= -v key="$3" '$1 != key' '{posix_path(self.settings)}' > '{posix_path(root)}/settings.tmp'
            mv -f '{posix_path(root)}/settings.tmp' '{posix_path(self.settings)}'
            if [ "$1" = put ]; then
                printf '%s=%s\\n' "$3" "$4" >> '{posix_path(self.settings)}'
                fixture_visual_ack "$3" "$4"
            fi ;;
        *) return 1 ;;
    esac
}}
kill() {{
    [ "$1" = -TERM ] || return 1
    case "$2" in 401|402|403|404) ;; *) return 1;; esac
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

    def ready_launcher(self, version="17.26.2"):
        runtime = posix_path(self.runtime)
        self.create_process(401, ["/system/bin/sh", runtime + "/supervise.sh"], "/system/bin/mksh", 21)
        injector = runtime + "/open-voyah-controls-frida-" + version
        self.create_process(402, [injector, "-p", "70", "-s", runtime + "/launcher-buttons.js"], injector, 22)
        (self.runtime / "ready").write_text("OVC_READY NavigationBar click+hold pid=70\n", newline="\n")

    def ready_window(self):
        runtime = posix_path(self.runtime)
        directory = self.runtime / "window"
        directory.mkdir(mode=0o700, exist_ok=True)
        self.create_process(403, ["/system/bin/sh", runtime + "/supervise.sh", "window"], "/system/bin/mksh", 23)
        injector = runtime + "/open-voyah-controls-frida-17.26.2"
        self.create_process(404, [injector, "-p", "80", "-s", runtime + "/window/launcher-buttons.js"], injector, 24)

    def visual_session(self, channel="main", boot=BOOT_ID, start=900, nonce="ab" * 16):
        directory = self.runtime if channel == "main" else self.runtime / "window"
        directory.mkdir(mode=0o700, exist_ok=True)
        pid = 70 if channel == "main" else 80
        if not (self.proc / str(pid)).exists():
            target = "com.qinggan.app.launcher" if channel == "main" else "system_server"
            self.create_process(pid, [target], "/system/bin/app_process64", start)
        record = f"{boot}:{pid}:{start}:{nonce}\n".encode()
        path = directory / "visual.session"
        path.write_bytes(record)
        path.chmod(0o600)
        return path

    def acknowledge_visual_restore(self, channel="main"):
        directory = self.runtime if channel == "main" else self.runtime / "window"
        (directory / "hook.log").write_text("", newline="\n")
        (self.root / ("ack-" + channel)).touch()

    def test_preattach_visual_sessions_block_status_and_rearm_without_workers(self):
        for channel in ("main", "window"):
            with self.subTest(channel=channel):
                session = self.visual_session(channel)
                original = session.read_bytes()
                before = sorted(path.relative_to(self.runtime) for path in self.runtime.rglob("*"))
                status = self.configure("status")
                self.assert_success(status)
                self.assertIn("OVC_ROLLBACK_INERT=no", status.stdout)
                self.assertEqual(original, session.read_bytes())
                self.assertEqual(before, sorted(path.relative_to(self.runtime) for path in self.runtime.rglob("*")))
                self.assertNotEqual(0, self.configure("rearm").returncode)
                self.assertEqual(original, session.read_bytes())
                session.unlink()

    def test_original_main_and_window_restore_acknowledgments_clear_bound_sessions(self):
        self.ready_launcher()
        self.ready_window()
        main = self.visual_session()
        window = self.visual_session("window")
        self.acknowledge_visual_restore()
        self.acknowledge_visual_restore("window")
        result = self.configure("rollback")
        self.assert_success(result)
        self.assertIn("OVC_ROLLBACK_VISUALS=acknowledged", result.stdout)
        self.assertIn("OVC_ROLLBACK_INERT=yes", result.stdout)
        self.assertFalse(main.exists())
        self.assertFalse(window.exists())
        self.assertEqual({"401", "402", "403", "404"}, set(self.signals.read_text().splitlines()))
        self.assertTrue((self.proc / "70/stat").is_file())
        self.assertTrue((self.proc / "80/stat").is_file())
        self.assert_success(self.configure("rearm"))

    def test_restore_ack_cannot_clear_a_replacement_visual_session(self):
        self.ready_launcher()
        session = self.visual_session()
        self.acknowledge_visual_restore()
        replacement = f"{BOOT_ID}:70:900:{'cd' * 16}\n".encode()
        (self.root / "race-main").write_bytes(replacement)
        result = self.configure("rollback")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("OVC_ROLLBACK_VISUALS=reboot-required", result.stdout)
        self.assertEqual(replacement, session.read_bytes())
        self.assertNotEqual(0, self.configure("rearm").returncode)

    def test_old_boot_session_is_classified_read_only_and_retired_by_rearm(self):
        session = self.visual_session()
        original = session.read_bytes()
        self.boot_id.write_text("6277ee31-9931-442a-9dc8-3bbbf3ff562e\n", newline="\n")
        status = self.configure("status")
        self.assert_success(status)
        self.assertIn("OVC_ROLLBACK_INERT=yes", status.stdout)
        self.assertEqual(original, session.read_bytes())
        self.assert_success(self.configure("rearm"))
        self.assertFalse(session.exists())

    def test_proven_target_reuse_retires_only_its_visual_session(self):
        session = self.visual_session("window")
        original = session.read_bytes()
        target = self.proc / "80/stat"
        target.write_text("80 (system_server) S " + "0 " * 18 + "901\n", newline="\n")
        status = self.configure("status")
        self.assert_success(status)
        self.assertIn("OVC_ROLLBACK_INERT=yes", status.stdout)
        self.assertEqual(original, session.read_bytes())
        self.assert_success(self.configure("rollback"))
        self.assertFalse(session.exists())
        self.assertTrue(target.is_file())
        self.assertFalse(self.signals.exists())

    def test_malformed_visual_session_remains_blocked_after_reboot(self):
        session = self.visual_session()
        malformed = b"malformed visual journal\n"
        session.write_bytes(malformed)
        self.boot_id.write_text("6277ee31-9931-442a-9dc8-3bbbf3ff562e\n", newline="\n")
        self.assertIn("OVC_ROLLBACK_INERT=no", self.configure("status").stdout)
        result = self.configure("rollback")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("OVC_ROLLBACK_VISUALS=reboot-required", result.stdout)
        self.assertNotEqual(0, self.configure("rearm").returncode)
        self.assertEqual(malformed, session.read_bytes())

    def test_unreadable_target_identity_does_not_retire_a_visual_session(self):
        session = self.visual_session()
        original = session.read_bytes()
        (self.proc / "70/stat").write_text("unreadable or truncated proc state\n", newline="\n")
        self.assertIn("OVC_ROLLBACK_INERT=no", self.configure("status").stdout)
        self.assertNotEqual(0, self.configure("rearm").returncode)
        self.assertEqual(original, session.read_bytes())

    def test_positive_restore_does_not_clear_an_independent_visual_marker(self):
        self.ready_launcher()
        session = self.visual_session()
        self.acknowledge_visual_restore()
        marker = self.runtime / "recovery.visual-pending"
        marker.write_text(BOOT_ID + "\n", newline="\n")
        result = self.configure("rollback")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(session.exists())
        self.assertEqual(BOOT_ID + "\n", marker.read_text())
        self.assertIn("OVC_ROLLBACK_INERT=no", result.stdout)
        self.assertNotEqual(0, self.configure("rearm").returncode)

    def test_non_owner_window_ack_cannot_clear_the_owner_visual_session(self):
        self.ready_window()
        session = self.visual_session("window")
        original = session.read_bytes()
        self.acknowledge_visual_restore("window")
        (self.root / "current-user").write_text("10\n", newline="\n")
        result = self.configure("rollback")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("OVC_ROLLBACK_VISUALS=reboot-required", result.stdout)
        self.assertEqual(original, session.read_bytes())
        self.assertEqual("", (self.runtime / "window/hook.log").read_text())
        self.assertNotEqual(0, self.configure("rearm").returncode)

    def test_visual_pid_boundaries_match_the_canonical_journal_schema(self):
        block = self.source.split("# Visual session recovery helpers start.", 1)[1].split("\n", 1)[1]
        block = block.split("# Visual session recovery helpers end.", 1)[0]
        script = self.root / "visual-pid-boundaries.sh"
        script.write_text("set -eu\n" + block + "\nfor value do\n"
            " if visual_pid_valid \"$value\"; then echo valid; else echo invalid; fi\ndone\n",
            encoding="utf-8", newline="\n")
        valid = ["2", "70", "2147483646", "2147483647"]
        invalid = ["", "0", "1", "01", "02", "-2", "+2", "2147483648", "9999999999", "10000000000", "2x"]
        result = subprocess.run([BASH, str(script), *valid, *invalid], capture_output=True,
                                text=True, timeout=10)
        self.assert_success(result)
        self.assertEqual(["valid"] * len(valid) + ["invalid"] * len(invalid), result.stdout.splitlines())

    def assert_android_wrapped_pid_is_preserved(self, pid):
        # Model the two observed Android 32-bit comparison results in the Bash fixture.
        android_compare = '''[() {
    case "$#" in
        4) case "$1:$2:$3:$4" in
            '2147483648:-le:2147483647:]'|'9999999999:-le:2147483647:]') return 0;;
            '2147483648:-gt:2147483647:]'|'9999999999:-gt:2147483647:]') return 1;;
        esac;;
    esac
    builtin [ "$@"
}
'''
        self.source = self.source.replace("#!/system/bin/sh\n", "#!/system/bin/sh\n" + android_compare, 1)
        session = self.visual_session()
        record = f"{BOOT_ID}:{pid}:900:{'ab' * 16}\n".encode()
        session.write_bytes(record)
        status = self.configure("status")
        self.assert_success(status)
        self.assertIn("OVC_ROLLBACK_INERT=no", status.stdout)
        self.assertEqual(record, session.read_bytes())
        rollback = self.configure("rollback")
        self.assertNotEqual(0, rollback.returncode)
        self.assertIn("OVC_ROLLBACK_VISUALS=reboot-required", rollback.stdout)
        self.assertEqual(record, session.read_bytes())
        self.assertNotEqual(0, self.configure("rearm").returncode)
        self.assertEqual(record, session.read_bytes())

    def test_android_overflow_pid_journal_is_not_retired(self):
        self.assert_android_wrapped_pid_is_preserved("2147483648")

    def test_android_wrapped_large_pid_journal_is_not_retired(self):
        self.assert_android_wrapped_pid_is_preserved("9999999999")

    def install(self):
        backup = self.configure("backup")
        self.assert_success(backup)
        self.assertIn("OVC_BACKUP_SHA256=" + self.hash(self.boot), backup.stdout)
        self.assertEqual(STOCK, (self.backup / "original").read_bytes())
        self.ready_launcher()
        result = self.configure("install", self.hash(self.backup / "original"))
        self.assert_success(result)

    def access_fixture(self):
        self.access = self.root / "adb/open-voyah-controls-access"
        self.access.mkdir(mode=0o700)
        script = '''#!/bin/sh
cd "$(dirname "$0")"
case "$1" in
 --status) if [ -e active ]; then echo active; else echo inactive; fi;;
 --stop) rm -f active;;
 --verify) test -e active;;
 *) exit 1;;
esac
'''
        for name, content in (("access-start.sh", script), ("login.sh", "exit 0\n"), ("busybox-fixture", "fixture\n")):
            (self.access / name).write_text(content, newline="\n")
            (self.access / name).chmod(0o700)
        (self.access / "checksums").write_text("".join(self.hash(self.access / n) + "  " + n + "\n"
            for n in ("access-start.sh", "login.sh", "busybox-fixture")), newline="\n")
        (self.access / "checksums").chmod(0o600)
        (self.access / "active").touch()

    def install_access(self):
        self.assert_success(self.configure("backup"))
        self.access_fixture()
        self.assert_success(self.configure("install-access", self.hash(self.backup / "original")))

    def legacy_boot(self):
        entry = ('( w=/data/local/tmp/open-voyah-controls/boot-start.sh; i=0; while [ ! -x "$w" ] && '
                 '[ "$i" -lt 40 ]; do sleep 2; i=$((i+1)); done; if [ -x "$w" ]; then /system/bin/sh "$w"; '
                 'fi ) </dev/null >/dev/null 2>&1 &\n')
        entry = entry.replace("/data/local/tmp", posix_path(self.legacy.parent))
        return STOCK.splitlines(keepends=True)[0] + b"# OPEN_VOYAH_CONTROLS_BOOT_V1\n" + entry.encode() + b"".join(STOCK.splitlines(keepends=True)[1:])

    def prepare_access_upgrade(self):
        self.install()
        # The app has already stopped its workers before updating the boot entry.
        for pid in ("401", "402"):
            (self.proc / pid / "cmdline").write_bytes(b"")
        self.access_fixture()

    def interrupted_access_upgrade(self, boundary):
        self.assertIn(boundary, self.source)
        source = self.source
        try:
            self.source = source.replace(boundary, boundary + "\n    fail 'Fixture interruption'", 1)
            result = self.configure("install-access", self.hash(self.backup / "original"))
        finally:
            self.source = source
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Fixture interruption", result.stderr)

    def test_access_entry_installs_before_oem_handlers_and_is_idempotent(self):
        self.install_access()
        self.assertIn(b"OPEN_VOYAH_CONTROLS_BOOT_V3", self.boot.read_bytes())
        self.assertIn("already-installed", self.configure("install-access", self.hash(self.backup / "original")).stdout)
        self.assertIn("OVC_ACCESS=active", self.configure("status").stdout)
        self.assertNotIn("OVC_ROLLBACK_INERT=yes", self.configure("status").stdout)

    def test_access_rollback_preserves_other_accessibility_services(self):
        self.install_access()
        (self.access / "accessibility-added").touch()
        self.settings.write_text("unrelated=keep\nenabled_accessibility_services=other/.Service:org.openvoyah.controls/.ButtonAccessibilityService\n", newline="\n")
        self.assert_success(self.configure("rollback"))
        self.assertEqual(STOCK, self.boot.read_bytes())
        self.assertTrue((self.access / "disabled").exists())
        self.assertFalse((self.access / "active").exists())
        self.assertIn("enabled_accessibility_services=other/.Service", self.settings.read_text())

    def test_pc_backup_recovers_v3_without_app_or_on_device_original(self):
        self.install_access()
        for item in ("original", "original.sha256"): (self.backup / item).unlink()
        result = self.configure("restore-exported", hashlib.sha256(STOCK).hexdigest(), CONTEXT, "0:0", "755", exported=STOCK)
        self.assert_success(result)
        self.assertEqual(STOCK, self.boot.read_bytes())

    def test_pc_backup_recovers_exact_v1_without_on_device_original(self):
        self.boot.write_bytes(self.legacy_boot())
        result = self.configure("restore-exported", hashlib.sha256(STOCK).hexdigest(), CONTEXT, "0:0", "755", exported=STOCK)
        self.assert_success(result)
        self.assertEqual(STOCK, self.boot.read_bytes())

    def test_pc_backup_preserves_foreign_v1_edits(self):
        modified = self.legacy_boot() + b"# Local change\n"
        self.boot.write_bytes(modified)
        result = self.configure("restore-exported", hashlib.sha256(STOCK).hexdigest(), CONTEXT, "0:0", "755", exported=STOCK)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(modified, self.boot.read_bytes())
        self.assertFalse(self.signals.exists())

    def test_interruption_before_boot_replace_keeps_previous_generation_recoverable(self):
        self.prepare_access_upgrade()
        previous = self.boot.read_bytes()
        previous_hash = self.hash(self.boot)
        self.interrupted_access_upgrade('if [ "$direction" = install ]; then save_text "$state/pending-installed.sha256" "$expected" || return 1')
        self.assertEqual(previous, self.boot.read_bytes())
        self.assertEqual(previous_hash, (self.backup / "installed.sha256").read_text().strip())
        self.assertTrue((self.backup / "pending-installed.sha256").is_file())
        self.assert_success(self.configure("backup"))
        self.assert_success(self.configure("rollback"))
        self.assertEqual(STOCK, self.boot.read_bytes())
        self.assertFalse((self.backup / "pending-installed.sha256").exists())

    def test_interruption_after_boot_replace_can_finish_and_roll_back(self):
        self.prepare_access_upgrade()
        previous_hash = self.hash(self.boot)
        self.interrupted_access_upgrade('    stage=\n    sync\n    [ "$(digest "$boot")" = "$expected" ] && [ "$(attrs_of "$boot")" = "$attrs" ] &&\n        [ "$(context_of "$boot")" = "$context" ] || return 1')
        self.assertIn(b"OPEN_VOYAH_CONTROLS_BOOT_V3", self.boot.read_bytes())
        self.assertEqual(previous_hash, (self.backup / "installed.sha256").read_text().strip())
        self.assertEqual(self.hash(self.boot), (self.backup / "pending-installed.sha256").read_text().strip())
        self.assert_success(self.configure("backup"))
        self.assert_success(self.configure("install-access", self.hash(self.backup / "original")))
        self.assertEqual(self.hash(self.boot), (self.backup / "installed.sha256").read_text().strip())
        self.assertFalse((self.backup / "pending-installed.sha256").exists())
        self.assert_success(self.configure("rollback"))
        self.assertEqual(STOCK, self.boot.read_bytes())

    def test_interrupted_generation_does_not_authorize_foreign_boot_edits(self):
        self.prepare_access_upgrade()
        self.interrupted_access_upgrade('    stage=\n    sync\n    [ "$(digest "$boot")" = "$expected" ] && [ "$(attrs_of "$boot")" = "$attrs" ] &&\n        [ "$(context_of "$boot")" = "$context" ] || return 1')
        modified = self.boot.read_bytes() + b"# Foreign change after interrupted install\n"
        self.boot.write_bytes(modified)
        self.assertNotEqual(0, self.configure("backup").returncode)
        rollback = self.configure("rollback")
        self.assertNotEqual(0, rollback.returncode)
        self.assertIn("OVC_BOOT_RESTORE=unconfirmed", rollback.stdout)
        self.assertEqual(modified, self.boot.read_bytes())

    def test_tampered_access_scripts_are_not_executed_by_recovery(self):
        self.install_access()
        (self.access / "access-start.sh").write_text("echo WRONG\n", newline="\n")
        result = self.configure("rollback")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("WRONG", result.stdout)
        self.assertEqual(STOCK, self.boot.read_bytes())

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
        unchanged = "unrelated=keep\norg.openvoyah.controls.apolloFactoryClaim=historical\n"
        self.settings.write_text(
            unchanged + "org.openvoyah.controls.windowRequest=fixture\n"
            "org.openvoyah.controls.apolloFactory=retired\n"
            "org.openvoyah.controls.apolloFactoryState=retired\n",
            newline="\n",
        )
        result = self.configure("rollback")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("OVC_ROLLBACK_VISUALS=reboot-required", result.stdout)
        self.assertIn("OVC_ROLLBACK_INERT=no", result.stdout)
        self.assertEqual({"401", "402"}, set(self.signals.read_text().splitlines()))
        self.assertTrue((self.runtime / "recovery.blocked").is_file())
        self.assertEqual(STOCK, self.boot.read_bytes())
        self.assertEqual(unchanged, self.settings.read_text())
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

    def test_previous_injector_is_still_owned_during_upgrade_rollback(self):
        self.ready_launcher("17.18.0")
        result = self.configure("rollback")
        self.assertIn("OVC_ROLLBACK_VISUALS=reboot-required", result.stdout)
        self.assertEqual({"401", "402"}, set(self.signals.read_text().splitlines()))

    def test_unknown_injector_version_blocks_recovery_without_signalling_it(self):
        runtime = posix_path(self.runtime)
        injector = runtime + "/open-voyah-controls-frida-99.0.0"
        self.create_process(402, [injector, "-p", "70", "-s", runtime + "/launcher-buttons.js"], injector, 22)
        result = self.configure("rollback")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.signals.exists())

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
