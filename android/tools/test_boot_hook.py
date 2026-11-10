"""Exercise the real boot installer with a fake system directory and no ADB."""
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
STOCK = b"#!/system/bin/sh\nprintf 'stock logcat\\n'\n"


class BootHookTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        system, data = (root / name for name in ("system", "data"))
        for folder in (system, data):
            folder.mkdir()
        self.boot = system / "init.logcat.sh"
        self.boot.write_bytes(STOCK)
        self.backup = data / "open-voyah-controls-boot"
        staged = data / "open-voyah-controls" / "boot-start.sh"
        staged.parent.mkdir()
        staged.write_text("#!/bin/sh\nexit 0\n", newline="\n")
        staged.chmod(0o755)
        self.script = root / "configure.sh"
        source = SOURCE.read_text(encoding="utf-8")
        source = source.replace("/system/etc", system.as_posix()).replace("/data/local/tmp", data.as_posix())
        source = source.replace('/system/bin/sh -n "$stage"', 'sh -n "$stage"')
        mocks = (
            "id() { echo 0; }\n"
            f'getprop() {{ case "$1" in ro.build.fingerprint) echo {FINGERPRINT} ;; *) echo 30 ;; esac; }}\n'
            "ls() { echo '?'; }\nstat() { echo 0; }\ngetenforce() { echo Disabled; }\nsync() { :; }\n"
        )
        source = source.replace("#!/system/bin/sh\n", "#!/system/bin/sh\n" + mocks, 1)
        self.script.write_text(source, encoding="utf-8", newline="\n")

    def configure(self, mode, fingerprint=FINGERPRINT):
        return subprocess.run([BASH, str(self.script), mode, fingerprint], capture_output=True,
                              text=True, timeout=15)

    def test_install_twice_and_restore_keep_the_original(self):
        installed = self.configure("install")
        self.assertEqual(0, installed.returncode, installed.stderr)
        self.assertIn(b"OPEN_VOYAH_CONTROLS_BOOT_V1", self.boot.read_bytes())
        self.assertEqual(STOCK, (self.backup / "original").read_bytes())
        self.assertEqual(hashlib.sha256(STOCK).hexdigest(), (self.backup / "original.sha256").read_text().strip())
        self.assertIn("already installed", self.configure("install").stdout)
        self.assertEqual(0, self.configure("restore").returncode)
        self.assertEqual(STOCK, self.boot.read_bytes())

    def test_unexpected_firmware_or_file_change_does_not_write(self):
        self.assertNotEqual(0, self.configure("install", "unexpected-firmware").returncode)
        self.assertEqual(STOCK, self.boot.read_bytes())
        self.assertEqual(0, self.configure("install").returncode)
        modified = self.boot.read_bytes() + b"# Local change\n"
        self.boot.write_bytes(modified)
        self.assertNotEqual(0, self.configure("restore").returncode)
        self.assertEqual(modified, self.boot.read_bytes())


if __name__ == "__main__":
    unittest.main()
