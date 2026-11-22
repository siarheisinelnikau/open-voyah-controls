"""Run text-overlays.sh against an isolated filesystem with a simulated overlay manager, never a vehicle."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parent / 'text-overlays.sh'
BASH = shutil.which('bash') if os.name != 'nt' else r'C:\Program Files\Git\bin\bash.exe'
FINGERPRINT = 'brand/product/device:11/X/1:user/test-keys'
SERIAL = 'unit0001'
PREFIX = 'org.openvoyah.controls.overlay.ru.'
# The overlay manager as seen through "dumpsys overlay" and changed by "cmd overlay": one "<package> <enabled>" line per
# overlay the package manager has scanned.
WRAPPERS = {
    'id': 'echo 0\n',
    'getprop': 'case "$1" in ro.build.fingerprint) echo "$FAKE_FP";; ro.serialno) echo "$FAKE_SN";; esac\n',
    'chown': 'exit 0\n',
    # Git Bash on NTFS cannot set Unix modes; the real mode is checked through the stat wrapper.
    'mkdir': '[ "$1" = -m ] && shift 2\nexec /usr/bin/mkdir "$@"\n',
    'chcon': 'printf "%s %s\\n" "$1" "${2##*/}" >> "$OVC_ROOT/fake/chcon"\n',
    'stat': 'case "$2" in %u:%a) echo 0:700;; *) echo 0:0:644;; esac\n',
    'pm': 'echo "package:$OVC_ROOT/system/app/$4.apk"\n',
    'dumpsys': '''[ -f "$OVC_ROOT/fake/overlays" ] || exit 0
while read -r package enabled; do
    printf '%s:0 {\\n  mPackageName...........: %s\\n' "$package" "$package"
    if [ "$enabled" = true ]; then state=STATE_ENABLED; else state=STATE_DISABLED; fi
    printf '  mState.................: %s\\n  mIsEnabled.............: %s\\n}\\n' "$state" "$enabled"
done < "$OVC_ROOT/fake/overlays"
''',
    'cmd': '''[ "$1" = overlay ] || exit 1
value=false; [ "$2" = enable ] && value=true
awk -v p="$5" -v v="$value" '$1 == p { $2 = v } { print }' "$OVC_ROOT/fake/overlays" > "$OVC_ROOT/fake/next"
mv "$OVC_ROOT/fake/next" "$OVC_ROOT/fake/overlays"
''',
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


@unittest.skipUnless(BASH and Path(BASH).exists(), 'bash is needed')
class InstallTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.vendor = self.base / 'vendor/overlay'
        self.vendor.mkdir(parents=True)
        (self.vendor / 'framework-res__auto_generated_rro_vendor.apk').write_bytes(b'oem')
        for folder in ('data/adb', 'data/local/tmp', 'system/app', 'fake', 'bin'):
            (self.base / folder).mkdir(parents=True)
        for name, body in WRAPPERS.items():
            path = self.base / 'bin' / name
            path.write_text('#!/bin/sh\n' + body, encoding='utf-8', newline='\n')
            path.chmod(0o755)
        for target in ('com.example.one', 'com.example.two'):
            (self.base / 'system/app' / (target + '.apk')).write_bytes(target.encode())
        self.state = self.base / 'data/adb/open-voyah-controls-overlays'

    def posix(self, path):
        if os.name != 'nt':
            return Path(path).as_posix()
        return subprocess.check_output([BASH, '-c', f"cygpath -u '{Path(path).as_posix()}'"], text=True).strip()

    def run_mode(self, mode, *arguments, success=True, fingerprint=FINGERPRINT):
        root = self.posix(self.base)
        prefix = (f"export OVC_ROOT='{root}' OVC_TEST_PATH='{root}/bin:/usr/bin:/bin' FAKE_FP='{FINGERPRINT}' FAKE_SN='{SERIAL}'\n"
                  f"set -- {mode} '{fingerprint}' {SERIAL} {' '.join(arguments)}\n")
        script = self.base / 'run.sh'
        script.write_text(prefix + SCRIPT.read_text(encoding='utf-8'), encoding='utf-8', newline='\n')
        result = subprocess.run([BASH, script.as_posix()], capture_output=True, text=True, timeout=60)
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode == 0, success, output)
        return output

    def stage(self, short, content, target):
        data = (short + content).encode()
        name = 'ovc-overlay.%s.apk' % sha(data)[:16]
        (self.base / 'data/local/tmp' / name).write_bytes(data)
        target_sha = sha((self.base / 'system/app' / (target + '.apk')).read_bytes())
        return [self.posix(self.base / 'data/local/tmp' / name), sha(data), PREFIX + short + '.apk', target, target_sha]

    def restart(self):
        """The package manager scans /vendor/overlay: new overlays start disabled, removed ones are forgotten."""
        fake = self.base / 'fake/overlays'
        known = dict(line.split() for line in fake.read_text().splitlines()) if fake.exists() else {}
        lines = ['%s %s' % (path.stem, known.get(path.stem, 'false')) for path in sorted(self.vendor.glob(PREFIX + '*.apk'))]
        fake.write_text(''.join(line + '\n' for line in lines), newline='\n')

    def ours(self):
        return {path.name: path.read_bytes() for path in self.vendor.glob(PREFIX + '*.apk')}

    def test_install_enable_reinstall_and_roll_back(self):
        first = self.stage('one', ' v1', 'com.example.one') + self.stage('two', ' v1', 'com.example.two')
        output = self.run_mode('install', *first)
        self.assertIn('OVC_OVERLAYS_NEED_RESTART', output)
        self.assertEqual(self.ours(), {PREFIX + 'one.apk': b'one v1', PREFIX + 'two.apk': b'two v1'})
        self.assertEqual(list((self.base / 'data/local/tmp').iterdir()), [])
        self.assertIn('u:object_r:vendor_overlay_file:s0', (self.base / 'fake/chcon').read_text())
        self.assertTrue((self.state / 'previous/complete').exists())

        self.assertIn('OVC_OVERLAY_UNKNOWN', self.run_mode('enable', success=False))
        self.restart()
        self.assertIn('enabled=true', self.run_mode('enable'))
        self.assertEqual((self.base / 'fake/overlays').read_text().split(), [PREFIX + 'one', 'true', PREFIX + 'two', 'true'])

        # A new build replaces the whole set; the set it replaced is kept for a rollback.
        self.run_mode('install', *self.stage('one', ' v2', 'com.example.one'))
        self.assertEqual(self.ours(), {PREFIX + 'one.apk': b'one v2'})
        self.assertEqual((self.base / 'fake/overlays').read_text().split(), [PREFIX + 'one', 'false', PREFIX + 'two', 'false'])
        self.assertIn('OVC_OVERLAY_ROLLBACK_AVAILABLE', self.run_mode('status'))

        self.run_mode('rollback')
        self.assertEqual(self.ours(), {PREFIX + 'one.apk': b'one v1', PREFIX + 'two.apk': b'two v1'})
        self.assertEqual(len((self.state / 'installed').read_text().splitlines()), 2)
        self.assertFalse((self.state / 'previous').exists())
        self.assertIn('There is no install to roll back.', self.run_mode('rollback', success=False))
        self.assertEqual((self.vendor / 'framework-res__auto_generated_rro_vendor.apk').read_bytes(), b'oem')

    def test_rollback_of_a_first_install_leaves_no_overlay(self):
        self.run_mode('install', *self.stage('one', ' v1', 'com.example.one'))
        self.run_mode('rollback')
        self.assertEqual(self.ours(), {})
        self.assertFalse((self.state / 'installed').exists())

    def test_remove_deletes_only_this_projects_overlays(self):
        self.run_mode('install', *self.stage('one', ' v1', 'com.example.one'))
        (self.vendor / 'org.other.overlay.apk').write_bytes(b'other')
        self.restart()
        output = self.run_mode('remove')
        self.assertIn('OVC_OVERLAY_REMOVED ' + PREFIX + 'one.apk', output)
        self.assertEqual(sorted(path.name for path in self.vendor.iterdir()),
                         ['framework-res__auto_generated_rro_vendor.apk', 'org.other.overlay.apk'])
        self.assertFalse((self.state / 'installed').exists())

    def test_refuses_a_different_target_build_without_changes(self):
        self.run_mode('install', *self.stage('one', ' v1', 'com.example.one'))
        arguments = self.stage('one', ' v2', 'com.example.one')
        arguments[4] = '0' * 64
        self.assertIn('not the APK', self.run_mode('install', *arguments, success=False))
        self.assertEqual(self.ours(), {PREFIX + 'one.apk': b'one v1'})

    def test_refuses_damaged_files_names_and_other_units(self):
        arguments = self.stage('one', ' v1', 'com.example.one')
        damaged = list(arguments)
        damaged[1] = '1' * 64
        self.assertIn('did not arrive intact', self.run_mode('install', *damaged, success=False))
        renamed = list(arguments)
        renamed[2] = 'com.other.overlay.apk'
        self.assertIn('Unexpected overlay name', self.run_mode('install', *renamed, success=False))
        self.assertIn('different firmware', self.run_mode('install', *arguments, success=False, fingerprint='other'))
        self.assertEqual(self.ours(), {})

    def test_read_only_vendor_is_reported(self):
        self.vendor.chmod(0o555)
        self.addCleanup(self.vendor.chmod, 0o755)
        if os.access(self.vendor, os.W_OK):
            self.skipTest('this filesystem ignores the read-only mode')
        self.assertIn('Remount /vendor first.', self.run_mode('remove', success=False))


if __name__ == '__main__':
    unittest.main()
