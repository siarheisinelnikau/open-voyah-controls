"""Run the real login gate against local process fixtures; never contact a device."""
from pathlib import Path
import hashlib
import subprocess
import tempfile
import unittest
from test_boot_hook import BASH, posix_path

ROOT = Path(__file__).resolve().parents[1]
KEY = "91" * 32
CLIENT = "cd" * 16
SERVER = "ab" * 16


class AccessLoginTest(unittest.TestCase):
    def setUp(self):
        parent = ROOT / "build/access-fixtures"
        parent.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=parent)
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        for name, text in (("key", KEY), ("uid", "10123"), ("fingerprint", "fixture/build"), ("nonce", SERVER)):
            (self.base / name).write_text(text + "\n", newline="\n")
        source = (ROOT / "tools/access-login.sh").read_text(encoding="utf-8")
        source = source.replace("export PATH=/system/bin:/system/xbin", ":")
        source = source.replace("/data/adb/open-voyah-controls-access", posix_path(self.base))
        source = source.replace("/proc/sys/kernel/random/uuid", posix_path(self.base / "nonce"))
        source = source.replace("exec /system/bin/sh -c 'cat | /system/bin/sh'", "exec bash -c 'cat | bash'")
        mocks = '''
id() { echo 0; }
getenforce() { echo Enforcing; }
getprop() { echo fixture/build; }
pm() { echo 'package:org.openvoyah.controls uid:10123'; }
stat() { echo '0:600:1'; }
stty() { :; }
'''
        self.script = self.base / "login.sh"
        self.script.write_text(mocks + source, encoding="utf-8", newline="\n")

    def launch(self):
        process = subprocess.Popen([BASH, str(self.script)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8")
        process.stdin.reconfigure(newline="\n")
        def close():
            if process.poll() is None: process.kill()
            process.wait(timeout=5)
            for stream in (process.stdin, process.stdout, process.stderr): stream.close()
        self.addCleanup(close)
        return process

    def challenge(self, process):
        self.assertEqual("OVC_ACCESS_1\n", process.stdout.readline())
        process.stdin.write(CLIENT + "\n"); process.stdin.flush()
        server = process.stdout.readline().strip().split(" ")
        self.assertEqual(["OVC_SERVER", SERVER], server[:2])
        self.assertEqual(hashlib.sha256(f"OVC_ROOT_SERVER_V1:{KEY}:{CLIENT}".encode()).hexdigest(), server[2])
        return hashlib.sha256(f"OVC_ROOT_CLIENT_V1:{KEY}:{CLIENT}:{SERVER}".encode()).hexdigest()

    def test_authenticated_session_reaches_shell_without_disclosing_key(self):
        process = self.launch()
        proof = self.challenge(process)
        output, error = process.communicate(proof + "\nprintf 'APP_EXECUTED\\n'\nexit\n", timeout=8)
        self.assertEqual(0, process.returncode, error)
        self.assertEqual("OVC_AUTHENTICATED\nAPP_EXECUTED\n", output)
        self.assertNotIn(KEY, output + error)

    def test_wrong_response_cannot_execute_following_input(self):
        process = self.launch(); self.challenge(process)
        output, error = process.communicate("0" * 64 + "\nprintf 'BAD_COMMAND\\n'\n", timeout=8)
        self.assertNotEqual(0, process.returncode)
        self.assertNotIn("BAD_COMMAND", output)
        self.assertNotIn(KEY, output + error)

    def test_package_prefix_collision_does_not_hide_the_exact_app_uid(self):
        source = self.script.read_text(encoding="utf-8").replace(
            "echo 'package:org.openvoyah.controls uid:10123'",
            "printf 'package:org.openvoyah.controls uid:10123\\npackage:org.openvoyah.controls.test uid:10124\\n'")
        self.script.write_text(source, encoding="utf-8", newline="\n")
        process = self.launch(); proof = self.challenge(process)
        output, error = process.communicate(proof + "\nprintf APP_EXECUTED\nexit\n", timeout=8)
        self.assertEqual(0, process.returncode, error)
        self.assertIn("APP_EXECUTED", output)

    def test_overlong_nonce_is_rejected_before_proof_or_command(self):
        process = self.launch()
        self.assertEqual("OVC_ACCESS_1\n", process.stdout.readline())
        output, _ = process.communicate("a" * 1000 + "\nprintf BAD_COMMAND\n", timeout=8)
        self.assertNotEqual(0, process.returncode)
        self.assertNotIn("OVC_SERVER", output)
        self.assertNotIn("BAD_COMMAND", output)


if __name__ == "__main__": unittest.main()
