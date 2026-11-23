"""Offline checks for durable main/window originals across injector lifetimes."""
from pathlib import Path
import hashlib
import os
import subprocess
import unittest

import test_supervisor
from test_boot_hook import BASH, BOOT_ID


class VisualSessionTest(unittest.TestCase):
    def setUp(self):
        self.f = test_supervisor.SupervisorTest("test_reviewed_owner_deployment_and_changed_package")
        self.addCleanup(self.f.doCleanups)
        self.f.setUp()

    def process(self, pid=811, since="100", state="S"):
        directory = self.f.proc / str(pid)
        directory.mkdir(exist_ok=True)
        fields = [state] + ["0"] * 18 + [since]
        (directory / "stat").write_text(f"{pid} (fixture ) target) " + " ".join(fields) + "\n", newline="\n")

    def session(self, scope="main", pid=811, since="100", nonce="1" * 32, boot=BOOT_ID):
        directory = self.f.base if scope == "main" else self.f.base / "window"
        directory.mkdir(mode=0o700, exist_ok=True)
        path = directory / "visual.session"
        path.write_text(f"{boot}:{pid}:{since}:{nonce}\n", newline="\n")
        path.chmod(0o600)
        self.process(pid, since)
        return path

    def descriptor(self, scope="main"):
        result = self.f.run_script("supervise.sh", "--visual-session-state", scope)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return result.stdout.strip()

    def clear(self, scope, proof, restored=False):
        mode = "--visual-session-clear-restored" if restored else "--visual-session-clear-changed"
        return self.f.run_script("supervise.sh", mode, scope, proof)

    @staticmethod
    def hash(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def test_record_is_durable_before_child_bookkeeping_for_both_targets(self):
        for scope, channel, target, pid in (("main", "", "com.qinggan.app.launcher", 811), ("window", "window", "system_server", 810)):
            directory = self.f.base if scope == "main" else self.f.base / "window"
            directory.mkdir(mode=0o700, exist_ok=True)
            self.process(pid)
            result = self.f.run_functions(f'''
channel='{channel}'; dir='{test_supervisor.posix_path(directory)}'; target='{target}'
pid_for() {{ echo {pid}; }}
record_visual_session {pid} 100 || exit 1
[ -s "$dir/visual.session" ] && [ ! -e "$dir/child.pid" ] || exit 2
visual_session_descriptor
''')
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            path = directory / "visual.session"
            self.assertRegex(path.read_text(), rf"^{BOOT_ID}:{pid}:100:[0-9a-f]{{32}}\n$")
            self.assertEqual("current:" + self.hash(path), result.stdout.strip())

    def test_current_session_cannot_be_retired_because_its_injector_is_gone(self):
        for scope in ("main", "window"):
            path = self.session(scope)
            proof = self.hash(path)
            self.assertEqual("current:" + proof, self.descriptor(scope))
            self.assertNotEqual(0, self.clear(scope, proof).returncode)
            self.assertTrue(path.exists())
            self.assertFalse((path.parent / "child.pid").exists())

    def test_original_acknowledgment_can_clear_only_the_captured_journal(self):
        for scope in ("main", "window"):
            path = self.session(scope)
            proof = self.hash(path)
            self.assertEqual(0, self.clear(scope, proof, restored=True).returncode)
            self.assertFalse(path.exists())
            self.assertEqual("absent", self.descriptor(scope))

    def test_replaced_or_missing_journal_refuses_an_old_acknowledgment(self):
        for scope in ("main", "window"):
            path = self.session(scope)
            old = self.hash(path)
            self.session(scope, nonce="2" * 32)
            replacement = path.read_bytes()
            self.assertNotEqual(0, self.clear(scope, old, restored=True).returncode)
            self.assertEqual(replacement, path.read_bytes())
            path.unlink()
            self.assertNotEqual(0, self.clear(scope, old, restored=True).returncode)

    def test_positive_target_death_reuse_or_boot_change_may_retire_each_surface(self):
        boot_path = self.f.proc / "sys/kernel/random/boot_id"
        for scope in ("main", "window"):
            for cause in ("gone", "reused", "zombie", "new-boot"):
                with self.subTest(scope=scope, cause=cause):
                    boot_path.write_text(BOOT_ID + "\n", newline="\n")
                    path = self.session(scope)
                    proof = self.hash(path)
                    if cause == "gone":
                        (self.f.proc / "811/stat").unlink()
                        (self.f.proc / "811").rmdir()
                    elif cause == "reused":
                        self.process(since="101")
                    elif cause == "zombie":
                        self.process(state="Z")
                    else:
                        boot_path.write_text("6277ee31-9931-442a-9dc8-3bbbf3ff562e\n", newline="\n")
                    self.assertEqual("changed:" + proof, self.descriptor(scope))
                    self.assertEqual(0, self.clear(scope, proof).returncode)
                    self.assertFalse(path.exists())

    def test_unknown_process_or_current_boot_preserves_the_journal(self):
        path = self.session()
        proof = self.hash(path)
        stat = self.f.proc / "811/stat"
        for value in (None, "811 (target) S 0\n"):
            if value is None:
                stat.unlink()
            else:
                stat.write_text(value, newline="\n")
            self.assertEqual("uncertain", self.descriptor())
            self.assertNotEqual(0, self.clear("main", proof).returncode)
        self.process()
        (self.f.proc / "sys/kernel/random/boot_id").unlink()
        self.assertEqual("uncertain", self.descriptor())
        self.assertNotEqual(0, self.clear("main", proof, restored=True).returncode)
        self.assertTrue(path.exists())

    def test_pid_boundary_uses_only_bounded_digit_comparisons(self):
        for pid, valid in (("2", True), ("999999999", True), ("1999999999", True), ("2147483646", True),
                           ("2147483647", True), ("2147483648", False), ("9999999999", False),
                           ("10000000000", False), ("0", False), ("1", False), ("02", False)):
            with self.subTest(pid=pid):
                result = self.f.run_functions(f'''
visual_pid_valid '{pid}'
''')
                self.assertEqual(0 if valid else 1, result.returncode, result.stdout + result.stderr)

    def test_android_integer_overflow_cannot_retire_a_malformed_session(self):
        path = self.session()
        for pid in ("2147483648", "9999999999"):
            path.write_text(f"{BOOT_ID}:{pid}:100:{'1' * 32}\n", newline="\n")
            result = self.f.run_functions('''
# Android 9/11 numeric test operands above signed-int max wrapped in native probes.
function [ {
    case "$1:$2" in
        2147483648:-gt|9999999999:-gt) return 1;;
    esac
    builtin [ "$@"
}
visual_session_descriptor
''')
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual("uncertain", result.stdout.strip())
            self.assertTrue(path.exists())

    def test_malformed_records_never_retire_even_after_a_new_boot(self):
        path = self.session()
        valid = path.read_bytes()
        variants = [valid.rstrip(b"\n"), valid + b"\n", valid + b"extra\n", valid.replace(b":811:", b":0811:"),
                    valid.replace(b":811:", b":1:"), valid.replace(b":811:", b":2147483648:"),
                    valid.replace(b":100:", b":0100:"), valid.replace(b":100:", b":1:2:"),
                    valid.replace(b"100:", b"9" * 21 + b":"), valid.replace(b"-", b"g", 1),
                    valid.replace(b"1111", b"ABCD", 1), valid.replace(b"1111", b"111", 1),
                    valid.replace(b":811:", b":811:\0"), b"x" * 129]
        (self.f.proc / "sys/kernel/random/boot_id").write_text("6277ee31-9931-442a-9dc8-3bbbf3ff562e\n", newline="\n")
        for value in variants:
            with self.subTest(value=value):
                path.write_bytes(value)
                self.assertEqual("uncertain", self.descriptor())
                self.assertNotEqual(0, self.clear("main", self.hash(path), restored=True).returncode)
                self.assertEqual(value, path.read_bytes())

    def test_unsafe_mode_or_link_count_does_not_resolve_a_session(self):
        self.session()
        for attribute, value in (("%u:%a", "0:644"), ("%h", "2")):
            result = self.f.run_functions(f'''
stat() {{
    if [ "$3" = "$root/visual.session" ] && [ "$2" = '{attribute}' ]; then echo '{value}'; return; fi
    case "$2" in %u:%a) echo 0:600;; *) command stat "$@";; esac
}}
visual_session_descriptor
''')
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("uncertain", result.stdout.strip())

    def test_read_and_clear_modes_reject_unknown_or_missing_scope_and_receipts(self):
        invalid = [("--visual-session-state",), ("--visual-session-state", "can"),
                   ("--visual-session-state", "main", "extra"), ("--visual-session-state", "../window"),
                   ("--visual-session-clear-restored", "main"),
                   ("--visual-session-clear-restored", "main", "x" * 64),
                   ("--visual-session-clear-changed", "main", "a" * 64, "extra")]
        for arguments in invalid:
            self.assertNotEqual(0, self.f.run_script("supervise.sh", *arguments).returncode)
        self.assertFalse((self.f.root / "started").exists())

    def test_uncertain_session_quarantine_survives_supervisor_and_child_evidence_loss(self):
        for scope, channel in (("main", ""), ("window", "window")):
            path = self.session(scope)
            (self.f.base / "enabled").touch()
            (path.parent / "enabled").touch()
            result = self.f.run_functions(f'''
channel='{channel}'; dir='{test_supervisor.posix_path(path.parent)}'
guard_visual_session
[ "$?" = 1 ] && [ "$recovery_required" = 1 ] && [ "$recovery_recorded" = 1 ] || exit 1
[ -f "$root/recovery.visual-pending" ] && [ -f "$root/recovery.blocked" ] || exit 2
may_start && exit 3
exit 0
''')
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertTrue(path.exists())
            self.assertFalse((self.f.base / "enabled").exists())
            self.assertFalse((self.f.root / "signals").exists())
        self.assertNotEqual(0, self.f.run_script("boot-start.sh").returncode)

    def test_first_main_injector_loss_quarantines_before_a_second_attachment(self):
        self.process(810)
        self.process(811)
        source = self.f.sources["supervise.sh"]
        prefix, main = source.split('if [ "$channel" = --verify-boot ]; then', 1)
        overrides = '''
pid_for() { if [ "$1" = system_server ]; then echo 810; else echo 811; fi; }
unknown_injector() { return 1; }
sleep() { :; }
'''
        command = '"$injector" -p "$target_pid" -s "$script" >> "$dir/hook.log" 2>&1 &'
        self.assertIn(command, main)
        main = main.replace(command, '''[ -s "$dir/visual.session" ] || exit 85
printf 'attach\\n' >> "$root/attachments"
''' + command)
        (self.f.base / "enabled").touch()
        result = subprocess.run([BASH, "-s"], input=prefix + overrides + 'if [ "$channel" = --verify-boot ]; then' + main,
                                capture_output=True, text=True, timeout=20, env=os.environ)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["attach"], (self.f.base / "attachments").read_text().splitlines())
        self.assertTrue((self.f.base / "visual.session").exists())
        self.assertEqual(BOOT_ID, (self.f.base / "recovery.visual-pending").read_text().strip())
        self.assertFalse((self.f.base / "enabled").exists())


if __name__ == "__main__":
    unittest.main()
