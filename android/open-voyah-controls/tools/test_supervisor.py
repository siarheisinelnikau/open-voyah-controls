"""Offline fixtures for the Android boot worker's private trust and retry gates."""
from pathlib import Path
import hashlib
import os
import subprocess
import tempfile
import unittest

from test_boot_hook import BASH, BOOT_ID, FINGERPRINT, posix_path


ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets/hooks"
RUNTIME = "open-voyah-controls-frida-17.26.2"
RUNTIME_SHA = "b58f44918fc27843484fc62502b3d2c90ea686a85ed484bed062b23482cd1733"


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
        self.can = self.root / "can.apk"
        self.app.write_bytes(b"approved app")
        self.launcher.write_bytes(b"approved OEM launcher")
        self.can.write_bytes(b"approved CAN service")
        self.injector = self.base / RUNTIME
        self.injector.write_text(
            "#!/usr/bin/env bash\nprintf 'started\\n' > '" + posix_path(self.root / "started") + "'\n",
            newline="\n",
        )
        self.injector.chmod(0o500)
        (self.base / "launcher-buttons.js").write_text("// approved agent\n", newline="\n")
        (self.base / "launcher-buttons.js").chmod(0o600)
        self.record = self.base / "trust.tsv"
        self.user = self.root / "user"
        self.app_key = self.user / "org.openvoyah.controls/files/root-channel.key"
        self.app_key.parent.mkdir(parents=True)
        self.app_key.write_text("a" * 64 + "\n", newline="\n")
        self.app_key.chmod(0o600)
        self.access = adb / "open-voyah-controls-access"
        self.access.mkdir(mode=0o700)
        (self.access / "key").write_bytes(self.app_key.read_bytes())
        (self.access / "uid").write_text("10086\n", newline="\n")
        for path in self.access.iterdir():
            path.chmod(0o600)
        boot = self.proc / "sys/kernel/random"
        boot.mkdir(parents=True)
        (boot / "boot_id").write_text(BOOT_ID + "\n", newline="\n")
        self.modes = (
            'case "$3" in */trust.tsv|*/launcher-buttons.js|*/worker.owner|*/root-channel.key|*/key|*/uid|*/recovery.*|*/visual.session) echo 600;; '
            '*/' + RUNTIME + ') echo 500;; *) echo 700;; esac'
        ) if os.name == "nt" else 'command stat -c %a "$3"'
        mocks = f'''
id() {{ echo 0; }}
fixture_mode() {{ {self.modes}; }}
stat() {{
    case "$3" in
        */root-channel.key) case "$2" in
            %u) echo "${{OVC_TEST_KEY_UID:-10086}}"; return;;
            %u:%a) printf '%s:%s\\n' "${{OVC_TEST_KEY_UID:-10086}}" "${{OVC_TEST_KEY_MODE:-600}}"; return;;
            %u:%a:%h) printf '%s:%s:%s\\n' "${{OVC_TEST_KEY_UID:-10086}}" "${{OVC_TEST_KEY_MODE:-600}}" "${{OVC_TEST_KEY_LINKS:-1}}"; return;;
        esac;;
    esac
    case "$2" in
        %u) echo 0;;
        %u:%a) printf '0:%s\\n' "$(fixture_mode "$@")";;
        %u:%a:%h) printf '0:%s:1\\n' "$(fixture_mode "$@")";;
        *) command stat "$@";;
    esac
}}
getprop() {{
    case "$1" in
        ro.build.fingerprint) echo '{FINGERPRINT}';;
        ro.build.version.sdk) echo 30;;
        ro.product.cpu.abi) echo arm64-v8a;;
        ro.product.name) echo qg-fixture;;
        sys.user.0.ce_available) echo "${{OVC_TEST_CE_AVAILABLE:-true}}";;
        sys.boot_completed) echo 1;;
    esac
}}
getenforce() {{ echo Enforcing; }}
dumpsys() {{
    [ "$1" = user ] || return 1
    printf 'Users:\\n  UserInfo{{0:null:13}} serialNo=0\\n    State: %s\\n' "${{OVC_TEST_USER_STATE:-RUNNING_UNLOCKED}}"
}}
am() {{ if [ "$1" = get-current-user ]; then echo "${{OVC_TEST_OWNER:-0}}"; fi; }}
pm() {{
    if [ "$1 $2" = 'list packages' ]; then echo "package:org.openvoyah.controls uid:${{OVC_TEST_UID:-10086}}"; return; fi
    case "$4" in
        org.openvoyah.controls) echo 'package:{posix_path(self.app)}';;
        com.qinggan.app.launcher) echo 'package:{posix_path(self.launcher)}';;
        com.qinggan.canbus.service) echo 'package:{posix_path(self.can)}';;
        *) return 1;;
    esac
}}
kill() {{ printf 'signal\\n' >> '{posix_path(self.root / "signals")}'; return 1; }}
sleep() {{
    if [ "${{OVC_TEST_END_WAIT:-0}}" = 1 ]; then rm -f '{posix_path(self.base / "enabled")}'; return; fi
    command sleep "$@"
}}
'''
        self.sources = {}
        for name in ("supervise.sh", "boot-start.sh"):
            source = (ASSETS / name).read_text(encoding="utf-8")
            for old, new in (("/data/adb", adb), ("/data/local/tmp", local), ("/data/user/0", self.user), ("/proc", self.proc),
                             ("/data/system/packages.xml", self.root / "packages.xml")):
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

    def write_receipt(self, signer=None):
        artifacts = [RUNTIME, "boot-start.sh", "supervise.sh", "launcher-buttons.js"]
        rows = [f"device\t{FINGERPRINT}\t30\tarm64-v8a\tqg-fixture"]
        for name, path in (("org.openvoyah.controls", self.app), ("com.qinggan.app.launcher", self.launcher)):
            rows.append(f"package\t{name}\t{posix_path(path)}\t{digest(path)}")
        if (self.base / "can/launcher-buttons.js").exists():
            artifacts.append("can/launcher-buttons.js")
            rows.append(f"package\tcom.qinggan.canbus.service\t{posix_path(self.can)}\t{digest(self.can)}")
        if signer:
            rows.append(f"signer\torg.openvoyah.controls\t{signer}\t24")
        rows.extend(f"artifact\t{name}\t{digest(self.base / name)}" for name in artifacts)
        stamp = subprocess.run([BASH, "-c", 'stat -c "%d:%i:%s:%Y:%Z" "$1"', "--", posix_path(self.app_key)],
                               capture_output=True, text=True, timeout=10, check=True).stdout.strip()
        rows.append(f"lifetime\torg.openvoyah.controls\t10086\t{stamp}\t{digest(self.app_key)}")
        self.record.write_text("\n".join(rows) + "\n", newline="\n")
        self.record.chmod(0o600)

    def run_script(self, name, *args, owner="0", **environment):
        return subprocess.run([BASH, str(self.base / name), *args], capture_output=True, text=True,
                              timeout=30, env={**os.environ, "OVC_TEST_OWNER": owner, **environment})

    def run_functions(self, commands, **environment):
        prefix = self.sources["supervise.sh"].split('if [ "$channel" = --verify-boot ]; then', 1)[0]
        return subprocess.run([BASH, "-s"], input=prefix + commands, capture_output=True, text=True,
                              timeout=30, env={**os.environ, **environment})

    def test_reviewed_owner_deployment_and_changed_package(self):
        result = self.run_script("boot-start.sh", "--verify")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("OVC_BOOT_VERIFIED", result.stdout)
        self.assertNotEqual(0, self.run_script("boot-start.sh", "--verify", owner="10").returncode)
        self.launcher.write_bytes(b"changed OEM launcher")
        self.assertNotEqual(0, self.run_script("boot-start.sh", "--verify").returncode)
        self.assertFalse((self.root / "started").exists())

    def write_packages(self, cert, version=25):
        code = posix_path(self.app.parent)
        (self.root / "packages.xml").write_text(
            "<packages>\n"
            f'    <package name="org.openvoyah.controls" codePath="{code}" version="{version}" userId="10086">\n'
            f'        <sigs count="1" schemeVersion="2">\n            <cert index="3" key="{cert.hex()}" />\n        </sigs>\n'
            "    </package>\n</packages>\n", newline="\n")

    def test_signer_pin_accepts_a_same_signer_update_and_refuses_a_foreign_or_older_one(self):
        cert = b"owner signing certificate"
        self.write_receipt(signer=hashlib.sha256(cert).hexdigest())
        self.write_packages(cert)
        self.app.write_bytes(b"updated app, same signer")
        result = self.run_script("boot-start.sh", "--verify")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("OVC_BOOT_VERIFIED", result.stdout)
        self.write_packages(b"foreign certificate")
        self.assertEqual(1, self.run_script("supervise.sh", "--verify-boot").returncode)
        self.write_packages(cert, version=23)
        self.assertEqual(1, self.run_script("supervise.sh", "--verify-boot").returncode)
        (self.root / "packages.xml").unlink()
        result = self.run_script("supervise.sh", "--verify-boot")
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn("OVC_BOOT_WAITING", result.stderr)
        self.assertFalse((self.base / "recovery.blocked").exists())
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

    def test_previous_legacy_injector_prevents_production_attachment(self):
        (self.root / "local/open-voyah-controls-frida-17.18.0").write_text("previous runtime", newline="\n")
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

    def test_can_worker_checks_its_own_package_and_script(self):
        channel = self.base / "can"
        channel.mkdir(mode=0o700)
        agent = channel / "launcher-buttons.js"
        agent.write_text("// independent CAN agent\n", newline="\n")
        agent.chmod(0o600)
        self.write_receipt()
        prefix = self.sources["supervise.sh"].split('if [ "$channel" = --verify-boot ]; then', 1)[0]
        script = prefix + 'check_runtime && printf "%s\\n" "$target $relative"\n'
        def verify():
            return subprocess.run([BASH, "-s", "--", "can"], input=script, capture_output=True, text=True, timeout=15)
        result = verify()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("com.qinggan.canbus.service can/launcher-buttons.js", result.stdout)
        self.can.write_bytes(b"changed CAN service")
        self.assertNotEqual(0, verify().returncode)
        self.assertFalse((self.root / "started").exists())


    def test_unlocked_clear_data_revokes_the_deployment_before_cleanup(self):
        for suffix in ("", "window", "navigation"):
            directory = self.base / suffix
            directory.mkdir(mode=0o700, exist_ok=True)
            (directory / "enabled").touch()
        self.app_key.unlink()
        result = self.run_functions('''
lifetime_status
[ "$?" = 1 ] || exit 1
revoke_activation || exit 2
[ "$recovery_recorded" = 1 ] || exit 3
for marker in recovery.visual-pending recovery.blocked; do
    [ -f "$root/$marker" ] || exit 4
done
[ ! -e "$root/enabled" ] && [ ! -e "$root/window/enabled" ] && [ ! -e "$root/navigation/enabled" ] || exit 5
printf 'revoked before owned cleanup\\n'
''')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("revoked before owned cleanup", result.stdout)
        for marker in ("recovery.visual-pending", "recovery.blocked"):
            self.assertEqual(BOOT_ID, (self.base / marker).read_text().strip())
        self.assertFalse((self.root / "signals").exists())

    def test_changed_key_identity_hash_uid_or_permissions_refuses_activation(self):
        for environment in ({"OVC_TEST_UID": "10087"}, {"OVC_TEST_KEY_UID": "10087"},
                            {"OVC_TEST_KEY_MODE": "644"}, {"OVC_TEST_KEY_LINKS": "2"}):
            self.assertNotEqual(0, self.run_script("supervise.sh", "--verify-boot", **environment).returncode)
        self.app_key.write_text("b" * 64 + "\n", newline="\n")
        self.assertNotEqual(0, self.run_script("supervise.sh", "--verify-boot").returncode)
        self.app_key.rename(self.root / "retired-key")
        self.app_key.write_bytes((self.access / "key").read_bytes())
        self.app_key.chmod(0o600)
        self.assertNotEqual(0, self.run_script("supervise.sh", "--verify-boot").returncode)

    def test_missing_or_unknown_unlock_observation_waits_without_revoking(self):
        self.app_key.unlink()
        for environment in ({"OVC_TEST_CE_AVAILABLE": "false"}, {"OVC_TEST_USER_STATE": "RUNNING_LOCKED"},
                            {"OVC_TEST_USER_STATE": "UNKNOWN"}):
            result = self.run_script("supervise.sh", "--verify-boot", **environment)
            self.assertEqual(2, result.returncode, result.stdout + result.stderr)
            self.assertIn("OVC_BOOT_WAITING", result.stderr)
            self.assertFalse((self.base / "recovery.blocked").exists())
            self.assertFalse((self.root / "started").exists())

    def test_a_session_of_the_same_running_target_is_adopted_not_revoked(self):
        session = self.base / "visual.session"
        session.write_text(f"{BOOT_ID}:4242:777:{'ab' * 16}\n", newline="\n")
        session.chmod(0o600)
        result = self.run_functions('''
channel=; dir=$root
start_of() { [ "$1" = 4242 ] && echo 777; }
child_gone() { [ "$(start_of "$1")" != "$2" ]; }
revoke_activation() { printf 'revoked %s\\n' "$1"; return 0; }
guard_visual_session 4242 777; echo "same=$?"
guard_visual_session defer; echo "defer=$?"
guard_visual_session 4242 999; echo "other=$?"
guard_visual_session; echo "unknown=$?"
''')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["same=0", "defer=0", "revoked visual", "other=1", "revoked visual", "unknown=1"],
                         result.stdout.splitlines())
        self.assertTrue(session.exists(), "the adopted session is kept")
        self.assertIn("attaching again to the same process", (self.base / "worker.log").read_text())

    def test_legacy_receipt_requires_explicit_reseal(self):
        rows = self.record.read_text().splitlines()
        self.record.write_text("\n".join(row for row in rows if not row.startswith("lifetime\t")) + "\n", newline="\n")
        result = self.run_script("supervise.sh", "--verify-boot")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("explicit connection", result.stderr)
        self.assertFalse((self.root / "started").exists())

    def test_package_invalidation_records_recovery_before_owned_cleanup(self):
        self.app.write_bytes(b"replacement signed app")
        result = self.run_functions('''
check_runtime
[ "$?" = 1 ] || exit 1
revoke_activation || exit 2
stop_child() {
    [ "$recovery_recorded" = 1 ] && [ -f "$root/recovery.visual-pending" ] && [ -f "$root/recovery.blocked" ] || exit 3
    printf 'owned cleanup after durable recovery\\n'
}
stop_child 800 123
''')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("owned cleanup after durable recovery", result.stdout)

    def test_update_pending_blocks_new_starts_without_being_a_stop_condition(self):
        (self.base / "enabled").touch()
        (self.base / "update.pending").write_text("pending\n", newline="\n")
        result = self.run_functions('enabled || exit 1\nmay_start && exit 2\nexit 0\n')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotEqual(0, self.run_script("boot-start.sh").returncode)
        self.assertFalse((self.root / "started").exists())

    def test_recovery_marker_failure_preserves_enablement_and_no_signal(self):
        (self.base / "enabled").touch()
        target = self.base / "foreign"
        target.write_text("untouched\n", newline="\n")
        try:
            (self.base / "recovery.visual-pending").symlink_to(target)
        except OSError:
            self.skipTest("The host does not allow symlink fixtures")
        result = self.run_functions('revoke_activation\n[ "$?" != 0 ] && [ "$recovery_required" = 1 ] && [ "$recovery_recorded" = 0 ]\n')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue((self.base / "enabled").exists())
        self.assertEqual("untouched\n", target.read_text())
        self.assertFalse((self.root / "signals").exists())


    def test_locked_start_holds_one_owner_without_attaching_or_consuming_retries(self):
        (self.base / "enabled").touch()
        self.app_key.unlink()
        result = self.run_script("supervise.sh", OVC_TEST_CE_AVAILABLE="false", OVC_TEST_END_WAIT="1")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(1, (self.base / "worker.log").read_text().count("OVC_WORKER_WAITING"))
        self.assertFalse((self.base / "failure.count").exists())
        self.assertFalse((self.base / "recovery.blocked").exists())
        self.assertFalse((self.root / "started").exists())
        self.assertFalse((self.base / "worker.owner").exists())


    def run_live_invalidation(self, change, fail_recovery=False):
        source = self.sources["supervise.sh"]
        prefix, main = source.split('if [ "$channel" = --verify-boot ]; then', 1)
        overrides = '''
pid_for() { if [ "$1" = system_server ]; then echo 810; else echo 811; fi; }
start_of() { echo 100; }
unknown_injector() { return 1; }
child_gone() { return 0; }
stop_child() {
    [ -n "$1" ] || return 0
    [ "$recovery_recorded" = 1 ] && [ -f "$root/recovery.visual-pending" ] && [ -f "$root/recovery.blocked" ] || exit 81
    [ ! -e "$root/enabled" ] || exit 82
    printf 'owned cleanup\\n' >> "$root/cleanup-order"
}
sleep() {
    if [ -n "$child" ]; then CHANGE; fi
}
'''
        overrides = overrides.replace("CHANGE", change)
        if fail_recovery:
            overrides += '''
mktemp() {
    case "$1" in */.lifetime-recovery.*) return 1;; *) command mktemp "$@";; esac
}
'''
        (self.base / "enabled").touch()
        result = subprocess.run([BASH, "-s"], input=prefix + overrides + 'if [ "$channel" = --verify-boot ]; then' + main,
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return result

    def test_live_key_loss_persists_recovery_before_owned_injector_cleanup(self):
        self.run_live_invalidation('rm -f "$app_key"')
        self.assertTrue((self.base / "cleanup-order").exists())
        self.assertEqual(BOOT_ID, (self.base / "recovery.visual-pending").read_text().strip())
        self.assertFalse((self.base / "enabled").exists())
        self.assertFalse((self.root / "signals").exists())

    def test_live_package_change_persists_recovery_before_owned_injector_cleanup(self):
        self.run_live_invalidation('printf changed > "$app_path"')
        self.assertTrue((self.base / "cleanup-order").exists())
        self.assertEqual(BOOT_ID, (self.base / "recovery.blocked").read_text().strip())
        self.assertFalse((self.base / "enabled").exists())

    def test_failed_durable_live_recovery_preserves_ownership_instead_of_cleanup(self):
        self.run_live_invalidation('rm -f "$app_key"', fail_recovery=True)
        self.assertFalse((self.base / "cleanup-order").exists())
        self.assertTrue((self.base / "worker.owner").exists())
        self.assertTrue((self.base / "enabled").exists())
        self.assertFalse((self.root / "signals").exists())


    def test_receipt_schema_rejects_malformed_tabs_duplicates_and_missing_pins(self):
        original = self.record.read_text()
        rows = original.splitlines()
        lifetime = next(row for row in rows if row.startswith("lifetime\t"))
        device = next(row for row in rows if row.startswith("device\t"))
        package = next(row for row in rows if row.startswith("package\t"))
        artifact = next(row for row in rows if row.startswith("artifact\t"))
        malformed = [
            original + lifetime + "\n", original + device + "\n", original + package + "\n",
            original + artifact + "\n", original.replace(lifetime, lifetime + "\t"),
            original.replace(lifetime, lifetime + "\textra"), original.replace("\t10086\t", "\t\t"),
            original.replace("lifetime\t", "\tlifetime\t"), original.replace("\t10086\t", "\t010086\t"),
            original.replace("lifetime\torg.openvoyah.controls", "lifetime\twrong.package"),
            original.replace(device + "\n", ""), original.replace(package + "\n", ""),
            original.replace(artifact + "\n", ""), original + "unknown\trow\n",
            original.replace("artifact\tboot-start.sh", "artifact\tother.sh"),
            original.replace("/controls.apk", "/../controls.apk"),
            original.replace("package\torg.openvoyah.controls\t", "package\tbad-name\t"),
            original.replace(lifetime, lifetime + "\0"), original + ("x" * 16384),
        ]
        try:
            for index, value in enumerate(malformed):
                self.record.write_text(value, newline="\n")
                result = self.run_script("supervise.sh", "--verify-boot")
                self.assertEqual(1, result.returncode, f"Malformed receipt {index}: {result.stdout}{result.stderr}")
        finally:
            self.record.write_text(original, newline="\n")


    def test_injector_argv_requires_exact_five_nul_delimited_arguments(self):
        executable = posix_path(self.injector)
        agent = posix_path(self.base / "launcher-buttons.js")
        good = [executable, "-p", "123", "-s", agent]
        data = self.root / "argv"
        variants = [
            (good, True), (good + ["extra"], False), (good + [""], False),
            (good[:-1], False), ([executable, "-p", "0", "-s", agent], False),
            ([executable, "-p", "0123", "-s", agent], False),
            ([executable, "-p", "12x", "-s", agent], False),
            ([executable, "-s", "123", "-p", agent], False),
            (["/foreign/injector", "-p", "123", "-s", agent], False),
            ([executable, "-p", "123", "-s", "/foreign/agent.js"], False),
            ([" ".join(good)], False),
        ]
        for arguments, expected in variants:
            data.write_bytes(b"\0".join(value.encode() for value in arguments) + b"\0")
            result = self.run_functions("injector_argv '" + posix_path(data) + "'\n")
            self.assertEqual(0 if expected else 1, result.returncode, result.stderr)
        data.write_bytes(b"\0".join(value.encode() for value in good))
        self.assertEqual(1, self.run_functions("injector_argv '" + posix_path(data) + "'\n").returncode)

    def test_same_child_keeps_start_uid_and_executable_gates(self):
        process = self.proc / "800"
        process.mkdir()
        executable = posix_path(self.injector)
        agent = posix_path(self.base / "launcher-buttons.js")
        (process / "cmdline").write_bytes(b"\0".join(value.encode() for value in [executable, "-p", "123", "-s", agent]) + b"\0")
        command = '''
start_of() { echo "${OVC_TEST_ACTUAL_START:-123}"; }
stat() { echo "${OVC_TEST_ACTUAL_UID:-0}"; }
readlink() { printf '%s\n' "${OVC_TEST_ACTUAL_EXE:-$injector}"; }
same_child 800 123
'''
        for environment, expected in (({}, 0), ({"OVC_TEST_ACTUAL_START": "124"}, 1),
                                      ({"OVC_TEST_ACTUAL_UID": "10086"}, 1),
                                      ({"OVC_TEST_ACTUAL_EXE": "/foreign/injector"}, 1),
                                      ({"OVC_TEST_ACTUAL_EXE": executable + " (deleted)"}, 0)):
            result = self.run_functions(command, **environment)
            self.assertEqual(expected, result.returncode, result.stderr)

    def test_child_gone_parses_parenthesized_names_and_refuses_bad_evidence(self):
        process = self.proc / "800"
        process.mkdir()
        stat = process / "stat"
        for state, current, expected, status in (("S", "123", "123", 1), ("S", "456", "123", 0),
                                                  ("Z", "123", "123", 0), ("X", "123", "123", 0),
                                                  ("S", "123", "", 1), ("S", "123", "bad", 1),
                                                  ("S", "bad", "123", 1), ("bad", "123", "123", 1)):
            fields = [state] + ["0"] * 18 + [current]
            stat.write_text("800 (fixture ) embedded ) name) " + " ".join(fields) + "\n", newline="\n")
            result = self.run_functions("child_gone 800 '" + expected + "'\n")
            self.assertEqual(status, result.returncode, result.stderr)
        for value in ("800 (fixture) S 0 0\n", "S " + "0 " * 30):
            stat.write_text(value, newline="\n")
            self.assertEqual(1, self.run_functions("child_gone 800 123\n").returncode)
        stat.unlink()
        self.assertEqual(1, self.run_functions("child_gone 800 123\n").returncode)
        self.assertEqual(0, self.run_functions("child_gone 801 123\n").returncode)


if __name__ == "__main__":
    unittest.main()
