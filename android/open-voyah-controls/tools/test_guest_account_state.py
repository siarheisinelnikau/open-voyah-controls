#!/usr/bin/env python3
"""Exercise the full guest-state shell wrapper on an explicitly selected isolated emulator.

All account paths, property reads/writes and broadcasts are synthetic. This does not
qualify a vehicle, the OEM seat provider, or complete account restoration.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import shlex
import subprocess
import textwrap
import time
import uuid


FIXTURE_BUSYBOX = "/data/local/tmp/ovc-channel-fixture/busybox"
SOURCE = Path(__file__).resolve().parents[1] / "app/src/main/kotlin/org/openvoyah/controls/data/frida/GuestAccountState.kt"
PROPERTIES = ("qinggan.account.aid", "qinggan.account.uid", "qinggan.account.phone", "com.qinggan.user_started")
ORIGINAL = "Original fixture%0%1%fixture-original%42\n"


def templates() -> dict[str, str]:
    source = SOURCE.read_text(encoding="utf-8")
    result = {}
    for name in ("LOCK", "SCRIPT"):
        match = re.search(r'private val ' + name + r' = """\n(.*?)\n    """\.trimIndent\(\)', source, re.S)
        if not match:
            raise AssertionError(f"Cannot locate the actual {name} shell template")
        result[name] = textwrap.dedent(match[1]).replace("${'$'}", "$")
    return result


class Emulator:
    def __init__(self, adb: str, port: int, serial: str):
        if not re.fullmatch(r"emulator-[0-9]+", serial):
            raise ValueError("Use an explicit isolated emulator serial")
        self.adb = [str(Path(adb).resolve()), "-P", str(port), "-s", serial, "shell", "/system/bin/sh", "-s"]
        self.root = "/data/local/tmp/ovc-guest-state-" + uuid.uuid4().hex
        self.base = self.root + "/base"
        self.token = self.root + "/token"
        self.parts = templates()
        self.active_writer: subprocess.Popen | None = None

    def run(self, script: str, *, check: bool = True, timeout: int = 25) -> subprocess.CompletedProcess:
        result = subprocess.run(self.adb, input=script + "\n", text=True, capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise AssertionError(f"Fixture shell failed ({result.returncode}): {result.stdout.strip()} {result.stderr.strip()}")
        return result

    def launch(self, script: str) -> subprocess.Popen:
        process = subprocess.Popen(self.adb, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        assert process.stdin is not None
        process.stdin.write(script + "\n")
        process.stdin.close()
        process.stdin = None
        self.active_writer = process
        return process

    def qualify(self) -> None:
        reply = self.run("printf '%s:%s:%s:%s:%s\\n' \"$(id -u)\" \"$(getprop ro.kernel.qemu)\" "
                         "\"$(getprop ro.build.version.sdk)\" \"$(getprop ro.hardware)\" \"$(getprop ro.product.name)\"").stdout.strip()
        uid, qemu, sdk, hardware, product = reply.split(":")
        if uid != "0" or qemu != "1" or sdk not in {"28", "30"} or hardware not in {"ranchu", "goldfish"} or not product.startswith("sdk_"):
            raise AssertionError("Requires a root shell on an isolated API 28/30 Android emulator; no device was modified")
        self.run(f"test ! -L {shlex.quote(FIXTURE_BUSYBOX)} && test -f {shlex.quote(FIXTURE_BUSYBOX)} && test -x {shlex.quote(FIXTURE_BUSYBOX)}")
        print(f"Qualified isolated API {sdk} emulator fixture", flush=True)

    def prepare_fixture(self) -> None:
        values = {"ro.product.name": "f0506h", "ro.build.fingerprint": "ovc-fixture/native:9/state-test", "ro.serialno": "OVC_GUEST_NATIVE_UNIT_1",
                  "qinggan.account.aid": "fixture-original", "qinggan.account.uid": "42", "qinggan.account.phone": "",
                  "com.qinggan.user_started": "  literal '$(never-executed)' ; % \\ tail  "}
        lines = ["set -eu", "umask 077", f"mkdir {shlex.quote(self.root)}",
                 f"mkdir {shlex.quote(self.base)} {shlex.quote(self.token)} {shlex.quote(self.root + '/properties')} {shlex.quote(self.root + '/original-properties')}",
                 f"printf '%s' {shlex.quote(ORIGINAL)} > {shlex.quote(self.token + '/accountInfo')}",
                 f"cp {shlex.quote(self.token + '/accountInfo')} {shlex.quote(self.root + '/original-account')}",
                 f"chown 1000:1000 {shlex.quote(self.token + '/accountInfo')}", f"chmod 0640 {shlex.quote(self.token + '/accountInfo')}",
                 f"printf '%s\\n' {shlex.quote(FIXTURE_BUSYBOX)} > {shlex.quote(self.root + '/busybox-pointer')}"]
        for name, value in values.items():
            lines.append(f"printf '%s\\n' {shlex.quote(value)} > {shlex.quote(self.root + '/properties/' + name)}")
            lines.append(f"cp {shlex.quote(self.root + '/properties/' + name)} {shlex.quote(self.root + '/original-properties/' + name)}")
        self.run("\n".join(lines))

    def command(self, action: str, slot: int = 1, hook: str = "") -> str:
        uid = "1000000" + str(slot)
        aid = "613f734f8fb7462ebd1db95d" + uid
        values = {"BASE": self.base, "ACTION": action, "RECORD": f"Fixture driver {slot}%0%1%{aid}%{uid}", "AID": aid, "UID": uid}
        body = self.parts["SCRIPT"]
        for key, value in values.items():
            body = body.replace("@" + key + "@", shlex.quote(value))
        body = body.replace("/private/configs/token", self.token)
        boundary = f"""fixture={shlex.quote(self.root)}
getprop() {{ cat "$fixture/properties/$1"; }}
setprop() {{ printf '%s\\n' "$2" > "$fixture/properties/$1"; }}
am() {{ printf '%s\\n' "$*" >> "$fixture/broadcasts"; }}
"""
        if hook:
            needle = 'mv -f "$new" "$account"'
            if body.count(needle) != 1:
                raise AssertionError("The account rename test boundary changed")
            body = body.replace(needle, hook + "\n" + needle)
            boundary += f"""rm -f {shlex.quote(self.root + '/writer.child')}
owner_line=$(cat /proc/$$/stat)
owner_start=$(printf '%s\\n' "${{owner_line##*) }}" | awk '{{print $20}}')
printf '%s %s\\n' "$$" "$owner_start" > {shlex.quote(self.root + '/writer.owner')}
"""
        wrapper = self.parts["LOCK"].replace("@BASE@", shlex.quote(self.base))
        wrapper = wrapper.replace("pointer=/data/adb/open-voyah-controls-access/busybox", "pointer=" + shlex.quote(self.root + "/busybox-pointer"))
        wrapper = wrapper.replace("/system/bin/busybox|/system/xbin/busybox|/vendor/bin/busybox", FIXTURE_BUSYBOX)
        wrapper = wrapper.replace("@PAYLOAD@", boundary + body)
        if "/private/configs/token" in wrapper or "pointer=/data/adb/" in wrapper:
            raise AssertionError("A production account/pointer path escaped fixture mapping")
        return wrapper

    def verified(self, action: str, slot: int = 1) -> None:
        result = self.run(self.command(action, slot))
        if result.stdout.strip() != f"OVC_GUEST_STATE_V2 {action} verified":
            raise AssertionError(f"Missing exact {action} completion receipt")

    def assert_original(self) -> None:
        lines = ["set -eu", f"fixture={shlex.quote(self.root)}", 'cmp -s "$fixture/original-account" "$fixture/token/accountInfo"',
                 '[ "$(stat -c \'%u:%g:%a\' "$fixture/token/accountInfo")" = 1000:1000:640 ]',
                 'test "$(cat "$fixture/base/profile-transaction")" = "OVC_GUEST_STATE_V2 restore verified"']
        for name in PROPERTIES:
            lines.append(f'cmp -s "$fixture/original-properties/{name}" "$fixture/properties/{name}"')
        self.run("\n".join(lines))

    @staticmethod
    def writer_output(writer: subprocess.Popen, timeout: int = 10) -> tuple[str, str]:
        if not hasattr(writer, "guest_output"):
            writer.guest_output = writer.communicate(timeout=timeout)
        return writer.guest_output

    def settle_writer(self, writer: subprocess.Popen) -> None:
        # A held writer records its start time and its single delay child. Stop only
        # those exact identities, then wait for the complete ADB subprocess to exit.
        self.run(f"""set -eu
fixture={shlex.quote(self.root)}
same_owner() {{
    [ -f "$1" ] || return 1
    read -r pid began < "$1"
    case "$pid:$began" in ''|*[!0-9:]*|:*) return 1;; esac
    [ "$pid" -gt 1 ] || return 1
    line=$(cat "/proc/$pid/stat" 2>/dev/null) || return 1
    rest=${{line##*) }}
    case "$rest" in Z*|X*) return 1;; esac
    [ "$(printf '%s\\n' "$rest" | awk '{{print $20}}')" = "$began" ] || return 1
    args=$(tr '\\000' ' ' < "/proc/$pid/cmdline")
    case "$args" in *"$fixture"*|'/system/bin/sleep 8 '|'/system/bin/sleep 30 ') ;; *) return 1;; esac
}}
for receipt in "$fixture/writer.child" "$fixture/writer.owner"; do
    if same_owner "$receipt"; then kill -TERM "$pid" 2>/dev/null || true; fi
done
for attempt in 1 2 3 4 5; do
    alive=0
    for receipt in "$fixture/writer.child" "$fixture/writer.owner"; do
        if same_owner "$receipt"; then alive=1; kill -KILL "$pid" 2>/dev/null || true; fi
    done
    [ "$alive" = 1 ] || exit 0
    sleep 1
done
exit 92
""", timeout=10)
        self.writer_output(writer)
        if writer.poll() is None:
            raise AssertionError("Owned writer did not terminate; retaining its fixture directory")
        self.active_writer = None

    def wait_held(self, writer: subprocess.Popen) -> None:
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if writer.poll() is not None:
                stdout, stderr = self.writer_output(writer)
                raise AssertionError(f"Writer exited before the locked rename ({writer.returncode}): stdout={stdout.strip()!r}; stderr={stderr.strip()!r}")
            result = self.run(f"test -s {shlex.quote(self.root + '/writer.held')}", check=False, timeout=5)
            if result.returncode == 0:
                return
            time.sleep(0.1)
        raise AssertionError("Delayed writer never reached its locked rename boundary")

    def lock_scenarios(self) -> None:
        self.verified("prepare")
        self.verified("switch", 1)
        self.verified("restore")
        self.assert_original()
        print("Full wrapper prepare/switch/restore and exact fixture readback passed", flush=True)

        hook = f"""/system/bin/sleep 8 &
delay_pid=$!
delay_line=$(cat "/proc/$delay_pid/stat")
delay_start=$(printf '%s\\n' "${{delay_line##*) }}" | awk '{{print $20}}')
printf '%s %s\\n' "$delay_pid" "$delay_start" > {shlex.quote(self.root + '/writer.child')}
printf 'held\\n' > {shlex.quote(self.root + '/writer.held')}
wait "$delay_pid"
"""
        writer = self.launch(self.command("switch", 2, hook))
        try:
            self.wait_held(writer)
            blocked = self.run(self.command("restore"), check=False)
            if blocked.returncode == 0 or "OVC_GUEST_STATE_V2 restore verified" in blocked.stdout:
                raise AssertionError("A concurrent restore bypassed the kernel lock")
            self.run(f'test "$(cat {shlex.quote(self.base + "/profile-transaction")})" = "OVC_GUEST_STATE_V2 switch pending"')
            stdout, stderr = self.writer_output(writer, timeout=20)
            if writer.returncode or stdout.strip() != "OVC_GUEST_STATE_V2 switch verified":
                raise AssertionError(f"Delayed writer failed: {stdout.strip()} {stderr.strip()}")
        finally:
            self.settle_writer(writer)
        self.verified("restore")
        self.assert_original()
        print("Concurrent late writer blocked restoration until the kernel lock released", flush=True)

        self.run(f"rm -f {shlex.quote(self.root + '/writer.held')}")
        death_hook = f"""printf 'held\\n' > {shlex.quote(self.root + '/writer.held')}
exec /system/bin/sleep 30"""
        writer = self.launch(self.command("switch", 3, death_hook))
        try:
            self.wait_held(writer)
            if self.run(self.command("restore"), check=False).returncode == 0:
                raise AssertionError("The exec'd lock owner lost its kernel lock")
            self.run(f"""set -eu
read -r owner_pid owner_start < {shlex.quote(self.root + '/writer.owner')}
case "$owner_pid:$owner_start" in ''|*[!0-9:]*) exit 91;; esac
line=$(cat "/proc/$owner_pid/stat")
actual=$(printf '%s\\n' "${{line##*) }}" | awk '{{print $20}}')
[ "$actual" = "$owner_start" ] || exit 91
[ "$(tr '\\000' ' ' < "/proc/$owner_pid/cmdline")" = '/system/bin/sleep 30 ' ] || exit 91
kill -KILL "$owner_pid"
""")
            self.writer_output(writer)
            if writer.returncode == 0:
                raise AssertionError("Killed writer unexpectedly reported success")
        finally:
            self.settle_writer(writer)
        self.verified("restore")
        self.assert_original()
        print("Owner death released the kernel lock; explicit restoration retained the first snapshot", flush=True)

    def cleanup(self) -> None:
        if not re.fullmatch(r"/data/local/tmp/ovc-guest-state-[a-f0-9]{32}", self.root):
            raise AssertionError("Refusing cleanup outside the unique emulator fixture")
        if self.active_writer is not None:
            self.settle_writer(self.active_writer)
        # Confirm no surviving holder owns the lock before removing its inode.
        self.run(f"""if [ -f {shlex.quote(self.base + '/profile-state.lock')} ]; then
{shlex.quote(FIXTURE_BUSYBOX)} sh -c 'exec 9<> "$1"; "$2" flock -n 9' ovc-cleanup {shlex.quote(self.base + '/profile-state.lock')} {shlex.quote(FIXTURE_BUSYBOX)}
fi""")
        self.run("rm -rf " + shlex.quote(self.root), check=False)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True, help="Absolute path to the selected SDK's adb executable")
    parser.add_argument("--port", required=True, type=int, help="Explicit ADB server port")
    parser.add_argument("--serial", required=True, help="Explicit isolated emulator serial")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not Path(args.adb).is_absolute():
        parser.error("Use an absolute --adb path and a valid explicit --port")
    emulator = Emulator(args.adb, args.port, args.serial)
    emulator.qualify()
    try:
        emulator.prepare_fixture()
        emulator.lock_scenarios()
    finally:
        emulator.cleanup()


if __name__ == "__main__":
    main()
