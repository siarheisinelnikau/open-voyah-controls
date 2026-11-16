"""Execute the bridge's generated retry reset against independent local filesystem fixtures."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

from test_boot_hook import BASH, posix_path


WORKSPACE = Path(__file__).resolve().parents[1]
SOURCE = WORKSPACE / "app/src/main/java/org/openvoyah/controls/data/frida/FridaBridge.java"
CHANNELS = ("", "locale-setting", "locale-vehicle", "locale-bluetooth", "window", "navigation", "can")


class FridaRetryBudgetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        parent = WORKSPACE / "build/frida-retry-fixtures"
        parent.mkdir(parents=True, exist_ok=True)
        cls.generated = tempfile.TemporaryDirectory(dir=parent)
        cls.addClassCleanup(cls.generated.cleanup)
        directory = Path(cls.generated.name)
        source = SOURCE.read_text(encoding="utf-8")
        channels = next(line for line in source.splitlines() if "private static final String[] CHANNELS =" in line)
        reset = source[source.index("    private void resetRetryBudget("):source.index("    private String checkedInjectorVersion(")]
        quote = next(line for line in source.splitlines() if "private static String quote(" in line)
        fixture = directory / "FridaRetryFixture.java"
        fixture.write_text("import java.io.IOException;\npublic final class FridaRetryFixture {\n"
            + channels + "\nprivate final String base;\nprivate boolean quiet;\n"
            + "private FridaRetryFixture(String base) { this.base = base; }\n"
            + "private String activationGuard() { return \"\"; }\n"
            + "private void verifyUpdateQuiescence() { quiet = true; }\n"
            + "private String command(String script) { if (!quiet) throw new AssertionError(\"Missing fresh quiescence\"); System.out.print(script); return \"\"; }\n"
            + quote + "\n" + reset
            + "public static void main(String[] args) throws Exception { new FridaRetryFixture(args[0]).resetRetryBudget(); }\n}\n",
            encoding="utf-8", newline="\n")
        jdk = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Android\Android Studio\jbr"))
        javac = jdk / "bin/javac.exe" if os.name == "nt" else shutil.which("javac")
        cls.java = jdk / "bin/java.exe" if os.name == "nt" else shutil.which("java")
        subprocess.run([str(javac), "-d", str(directory), str(fixture)], check=True, capture_output=True, text=True, timeout=30)
        cls.classpath = str(directory)

    def setUp(self):
        parent = WORKSPACE / "build/frida-retry-fixtures"
        directory = tempfile.TemporaryDirectory(dir=parent)
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.base = self.root / "runtime"
        self.counters = []
        for suffix in CHANNELS:
            channel = self.base / suffix
            channel.mkdir(mode=0o700, parents=True, exist_ok=True)
            for name, value in (("failure.boot", "fixture-boot\n"), ("failure.count", "5\n")):
                file = channel / name
                file.write_text(value, newline="\n")
                file.chmod(0o600)
                self.counters.append(file)
        self.unrelated = self.base / "foreign/failure.count"
        self.unrelated.parent.mkdir()
        self.unrelated.write_text("preserved\n", newline="\n")
        self.extra = self.base / "failure.count.backup"
        self.extra.write_text("preserved\n", newline="\n")

    def reset(self, *, bad_counter=None, attributes="0:600:1"):
        script = subprocess.run([str(self.java), "-cp", self.classpath, "FridaRetryFixture", posix_path(self.base)],
                                check=True, capture_output=True, text=True, timeout=10).stdout
        bad = posix_path(bad_counter) if bad_counter else ""
        mocks = f"""
stat() {{
    case "$2" in
        %u:%a) echo 0:700;;
        %u:%a:%h)
            if [ "$3" = '{bad}' ]; then echo '{attributes}'; else echo 0:600:1; fi;;
        *) command stat "$@";;
    esac
}}
sync() {{ :; }}
"""
        return subprocess.run([BASH, "-s"], input=mocks + script, capture_output=True, text=True, timeout=15)

    def test_only_known_counter_pairs_are_removed(self):
        result = self.reset()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(all(not file.exists() for file in self.counters))
        self.assertEqual("preserved\n", self.unrelated.read_text())
        self.assertEqual("preserved\n", self.extra.read_text())

    def test_last_channel_invalid_owner_prevents_any_counter_removal(self):
        result = self.reset(bad_counter=self.counters[-1], attributes="10086:600:1")
        self.assertEqual(77, result.returncode, result.stdout + result.stderr)
        self.assertTrue(all(file.is_file() for file in self.counters))

    def test_last_channel_unsafe_mode_or_link_count_prevents_any_reset(self):
        for attributes in ("0:644:1", "0:600:2"):
            result = self.reset(bad_counter=self.counters[-1], attributes=attributes)
            self.assertEqual(77, result.returncode, result.stdout + result.stderr)
            self.assertTrue(all(file.is_file() for file in self.counters))

    def test_directory_instead_of_counter_prevents_any_reset(self):
        malformed = self.counters[-1]
        malformed.unlink()
        malformed.mkdir()
        result = self.reset()
        self.assertEqual(77, result.returncode, result.stdout + result.stderr)
        self.assertTrue(all(file.exists() for file in self.counters))

    def test_absent_optional_channels_are_allowed(self):
        for file in self.counters[2:]:
            file.unlink()
        for suffix in CHANNELS[1:]:
            (self.base / suffix).rmdir()
        result = self.reset()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(all(not file.exists() for file in self.counters))


if __name__ == "__main__":
    unittest.main()
