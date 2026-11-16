"""Run the bridge's actual Java-generated shutdown script against independent process fixtures."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

from test_boot_hook import BASH, BOOT_ID, posix_path


WORKSPACE = Path(__file__).resolve().parents[1]
SOURCE = WORKSPACE / "app/src/main/java/org/openvoyah/controls/data/frida/FridaBridge.java"
PRIVATE = "/data/adb/open-voyah-controls"
LEGACY = "/data/local/tmp/open-voyah-controls"
INJECTOR = "open-voyah-controls-frida-17.26.2"


class FridaShutdownTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        parent = WORKSPACE / "build/frida-shutdown-fixtures"
        parent.mkdir(parents=True, exist_ok=True)
        cls.generated = tempfile.TemporaryDirectory(dir=parent)
        cls.addClassCleanup(cls.generated.cleanup)
        directory = Path(cls.generated.name)
        source = SOURCE.read_text(encoding="utf-8")
        channels = next(line for line in source.splitlines() if "private static final String[] CHANNELS =" in line)
        stop = source[source.index("    private String stopCommand("):source.index("    private static String workerIdentityScript(")]
        identity = source[source.index("    private static String workerIdentityScript("):source.index("    private void requireRoot()")]
        fixture = directory / "FridaShutdownFixture.java"
        fixture.write_text("public final class FridaShutdownFixture {\n" + channels + "\n"
            + "private final String base;\n"
            + "private FridaShutdownFixture(String base) { this.base = base; }\n"
            + stop + identity
            + "public static void main(String[] args) {\n"
            + "String[] selected = args.length == 1 ? CHANNELS : new String[]{args[1]};\n"
            + "System.out.print(new FridaShutdownFixture(args[0]).stopCommand(selected));\n}\n}\n",
            encoding="utf-8", newline="\n")
        jdk = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Android\Android Studio\jbr"))
        javac = jdk / "bin/javac.exe" if os.name == "nt" else shutil.which("javac")
        java = jdk / "bin/java.exe" if os.name == "nt" else shutil.which("java")
        subprocess.run([str(javac), "-d", str(directory), str(fixture)], check=True, capture_output=True, text=True, timeout=30)
        def generate(*args):
            return subprocess.run([str(java), "-cp", str(directory), "FridaShutdownFixture", PRIVATE, *args],
                                  check=True, capture_output=True, text=True, timeout=10).stdout
        cls.full_script = generate()
        cls.partial_script = generate("/window")

    def setUp(self):
        parent = WORKSPACE / "build/frida-shutdown-fixtures"
        directory = tempfile.TemporaryDirectory(dir=parent)
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.private = self.root / "adb/open-voyah-controls"
        self.legacy = self.root / "local/open-voyah-controls"
        self.proc = self.root / "proc"
        self.private.mkdir(parents=True)
        self.legacy.mkdir(parents=True)
        boot = self.proc / "sys/kernel/random"
        boot.mkdir(parents=True)
        (boot / "boot_id").write_text(BOOT_ID + "\n")
        self.process(100, "/system/bin/init", ["/system/bin/init"])
        self.signals = self.root / "signals"

    def process(self, pid, executable, arguments, *, start=456, state="S", flags=0, readable_stat=True):
        path = self.proc / str(pid)
        path.mkdir()
        if readable_stat:
            fields = [state] + ["0"] * 18 + [str(start)] + ["0"] * 5
            fields[6] = str(flags)
            (path / "stat").write_text(f"{pid} (fixture process) " + " ".join(fields) + "\n")
        if executable is not None:
            (path / "exe.fixture").write_text(executable)
        if arguments is not None:
            (path / "cmdline").write_bytes(b"\0".join(arg.encode() for arg in arguments) + b"\0")

    def receipt(self, *, pid=800, start=123):
        channel = self.private / "can"
        channel.mkdir(exist_ok=True)
        (channel / "worker.boot").write_text(BOOT_ID + "\n")
        (channel / "child.pid").write_text(str(pid) + "\n")
        (channel / "child.start").write_text(str(start) + "\n")

    def run_shutdown(self, *, partial=False):
        source = self.partial_script if partial else self.full_script
        for old, new in (("/data/adb", self.root / "adb"), ("/data/local/tmp", self.root / "local"), ("/proc", self.proc)):
            source = source.replace(old, posix_path(new))
        mocks = f"""
stat() {{
    case "$2" in
        %u:%a) case "$3" in */worker.owner) echo 0:600;; *) echo 0:700;; esac;;
        *) command stat "$@";;
    esac
}}
readlink() {{
    if [ -f "$1.fixture" ]; then cat "$1.fixture"; else return 1; fi
}}
sleep() {{ :; }}
kill() {{ printf signal >> '{posix_path(self.signals)}'; return 99; }}
"""
        # The fixture's argv/exe values use the same replaced namespace as the generated script.
        for path in self.proc.glob("[0-9]*/*"):
            if path.name not in ("cmdline", "exe.fixture"):
                continue
            data = path.read_bytes().replace(b"/data/adb", posix_path(self.root / "adb").encode())
            data = data.replace(b"/data/local/tmp", posix_path(self.root / "local").encode())
            path.write_bytes(data)
        result = subprocess.run([BASH, "-s"], input=mocks + source, capture_output=True, text=True, timeout=15)
        self.assertFalse(self.signals.exists(), "Shutdown proof must never signal a scoped orphan")
        return result

    def assert_refused(self):
        result = self.run_shutdown()
        self.assertEqual(78, result.returncode, result.stdout + result.stderr)

    def assert_quiet(self):
        result = self.run_shutdown()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_known_live_child_remains_busy(self):
        self.process(800, f"{PRIVATE}/{INJECTOR}", [f"{PRIVATE}/{INJECTOR}"])
        self.receipt(start=456)
        self.assert_refused()

    def test_stale_numeric_child_start_cannot_hide_an_owned_injector(self):
        self.process(800, f"{PRIVATE}/{INJECTOR}", [f"{PRIVATE}/{INJECTOR}"])
        self.receipt(start=123)
        self.assert_refused()

    def test_missing_receipts_cannot_hide_an_owned_injector(self):
        self.process(800, f"{PRIVATE}/{INJECTOR}", [f"{PRIVATE}/{INJECTOR}"])
        self.assert_refused()

    def test_deleted_injector_and_previous_version_are_still_owned(self):
        self.process(800, f"{PRIVATE}/open-voyah-controls-frida-17.18.0 (deleted)", ["injector"])
        self.assert_refused()

    def test_old_pid_reused_by_foreign_process_is_not_blocked(self):
        self.process(800, "/system/bin/unrelated", ["/system/bin/unrelated"])
        self.receipt(start=123)
        self.assert_quiet()

    def test_other_frida_executable_is_not_blocked(self):
        self.process(800, "/data/local/tmp/frida-inject", ["frida-inject", "-p", "42", "-s", "/other/agent.js"])
        self.assert_quiet()

    def test_owned_shell_script_without_receipts_is_blocked(self):
        self.process(800, "/system/bin/mksh", ["/system/bin/sh", f"{PRIVATE}/boot-start.sh"])
        self.assert_refused()

    def test_shell_with_only_argv_zero_is_not_a_project_script(self):
        self.process(800, "/system/bin/sh", ["/system/bin/sh"])
        self.assert_quiet()

    def test_only_the_second_nul_delimited_argument_selects_a_shell_script(self):
        self.process(800, "/system/bin/sh", ["sh", "-c", "printf test", f"{PRIVATE}/supervise.sh"])
        self.assert_quiet()

    def test_owned_script_argument_still_matches_with_trailing_arguments(self):
        self.process(800, "/system/bin/sh", ["sh", f"{PRIVATE}/supervise.sh", "window", "extra"])
        self.assert_refused()

    def test_diagnostic_arguments_and_inline_shell_text_are_not_scripts(self):
        self.process(800, "/system/bin/cat", ["cat", f"{PRIVATE}/supervise.sh"])
        self.process(801, "/system/bin/sh", ["sh", "-c", f"printf test\\n\n{PRIVATE}/supervise.sh\n"])
        self.assert_quiet()

    def test_script_argument_requires_its_own_nul_boundaries(self):
        self.process(800, "/system/bin/sh", ["sh", f"prefix\n{PRIVATE}/supervise.sh\nsuffix"])
        self.assert_quiet()

    def test_similar_foreign_script_directory_is_not_blocked(self):
        self.process(800, "/system/bin/sh", ["sh", "/data/adb/open-voyah-controls-other/supervise.sh"])
        self.assert_quiet()

    def test_legacy_injector_and_script_are_in_the_full_shutdown_scope(self):
        self.process(800, f"/data/local/tmp/{INJECTOR}", ["injector"])
        self.process(801, "/system/bin/sh", ["sh", f"{LEGACY}/supervise.sh", "window"])
        self.assert_refused()

    def test_surviving_user_process_with_unreadable_executable_is_not_quiet(self):
        self.process(800, None, ["unobservable"])
        self.assert_refused()

    def test_unreadable_stat_without_any_receipt_is_not_quiet(self):
        self.process(800, f"{PRIVATE}/{INJECTOR}", ["injector"], readable_stat=False)
        self.assert_refused()

    def test_unreadable_shell_arguments_are_not_quiet(self):
        self.process(800, "/system/bin/sh", None)
        self.assert_refused()

    def test_zombies_and_verified_kernel_threads_are_not_live_workers(self):
        self.process(800, f"{PRIVATE}/{INJECTOR}", ["injector"], state="Z")
        self.process(801, None, None, flags=0x00200000)
        self.assert_quiet()

    def test_partial_hook_stop_does_not_reject_the_active_launcher(self):
        self.process(800, f"{PRIVATE}/{INJECTOR}", ["injector"])
        result = self.run_shutdown(partial=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
