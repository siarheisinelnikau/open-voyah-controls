"""Exercise the actual access-start port parser with synthetic TCP tables only."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_boot_hook import BASH, posix_path

ROOT = Path(__file__).resolve().parents[1]
HEADER4 = "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"
HEADER6 = "sl local_address remote_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"


def row(local="0100007F:0913", remote="00000000:0000", state="0A", uid="0"):
    return f"0: {local} {remote} {state} 00000000:00000000 00:00000000 00000000 {uid} 0 12345\n"


class AccessStartParserTest(unittest.TestCase):
    def setUp(self):
        parent = ROOT / "build/access-start-fixtures"
        parent.mkdir(parents=True, exist_ok=True)
        directory = tempfile.TemporaryDirectory(dir=parent)
        self.addCleanup(directory.cleanup)
        self.base = Path(directory.name)
        self.tcp = self.base / "tcp"
        self.tcp6 = self.base / "tcp6"
        self.tcp.write_text(HEADER4, newline="\n")
        self.tcp6.write_text(HEADER6, newline="\n")
        source = (ROOT / "tools/access-start.sh").read_text(encoding="utf-8")
        source = source[source.index("port_available() {"):source.index('[ "$(id -u)" = 0 ]')]
        self.parser = source.replace("/proc/net/tcp6", posix_path(self.tcp6)).replace("/proc/net/tcp", posix_path(self.tcp))

    def check_available(self, expected):
        result = subprocess.run([BASH, "-s"], input=self.parser + "\nport_available\n",
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(0 if expected else 1, result.returncode, result.stdout + result.stderr)

    def test_empty_valid_tables_are_available(self):
        self.check_available(True)

    def test_root_foreign_uid_and_wildcard_listeners_all_refuse(self):
        for entry in (row(), row(uid="10086"), row(local="00000000:0913")):
            self.tcp.write_text(HEADER4 + entry, newline="\n")
            self.check_available(False)

    def test_ipv6_listener_refuses(self):
        self.tcp6.write_text(HEADER6 + row(local="0" * 32 + ":0913", remote="0" * 32 + ":0000", uid="10086"), newline="\n")
        self.check_available(False)

    def test_other_ports_and_nonlisteners_are_available(self):
        for entry in (row(local="0100007F:0914"), row(state="01")):
            self.tcp.write_text(HEADER4 + entry, newline="\n")
            self.check_available(True)

    def test_missing_or_malformed_tables_refuse(self):
        for text in ("", "not a header\n", HEADER4 + "short row\n",
                     HEADER4 + row(local="nothex:0913"), HEADER4 + row(state="XX"), HEADER4 + row(uid="bad")):
            self.tcp.write_text(text, newline="\n")
            self.check_available(False)
        self.tcp.write_text(HEADER4, newline="\n")
        self.tcp6.unlink()
        self.check_available(False)

    def test_oversized_table_refuses(self):
        self.tcp.write_text(HEADER4 + "x" * 262145, newline="\n")
        self.check_available(False)


if __name__ == "__main__":
    unittest.main()
