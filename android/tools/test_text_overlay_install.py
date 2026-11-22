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
FINGERPRINT = 'brand/product/device:11/X/1:user/release-keys,test-keys'
BUILD_DATE = '1720866928'
SERIAL = 'unit0001'
PREFIX = 'org.openvoyah.controls.overlay.ru.'
# The overlay manager as seen through "dumpsys overlay" and changed by "cmd overlay": one "<package> <enabled>" line per
# overlay the package manager has scanned. Every dumpsys call is counted.
WRAPPERS = {
    'id': 'echo 0\n',
    'getprop': ('case "$1" in ro.build.fingerprint) echo "$FAKE_FP";; ro.serialno) echo "$FAKE_SN";; '
                'ro.build.date.utc) echo "$FAKE_DATE";; esac\n'),
    'chown': 'exit 0\n',
    'chcon': 'printf "%s %s\\n" "$1" "${2##*/}" >> "$OVC_ROOT/fake/chcon"\n',
    # Git Bash on NTFS cannot set Unix modes; the real mode is checked through the stat wrapper.
    'mkdir': '[ "$1" = -m ] && shift 2\nexec /usr/bin/mkdir "$@"\n',
    'stat': 'case "$2" in %u:%a) echo 0:700;; %s) exec /usr/bin/stat -c %s "$3";; *) echo 0:0:644;; esac\n',
    'mount': 'printf "%s\\n" "$*" >> "$OVC_ROOT/fake/mount"\n',
    'pm': 'f="$OVC_ROOT/system/app/$4.apk"; [ -f "$f" ] && echo "package:$f"\n',
    'dumpsys': '''echo x >> "$OVC_ROOT/fake/dumpsys"
[ -f "$OVC_ROOT/fake/overlays" ] || exit 0
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
    shell = BASH
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.vendor = self.base / 'vendor/overlay'
        self.vendor.mkdir(parents=True)
        (self.vendor / 'framework-res__auto_generated_rro_vendor.apk').write_bytes(b'oem')
        for folder in ('data/adb', 'system/app', 'fake', 'bin', 'proc/sys/kernel/random'):
            (self.base / folder).mkdir(parents=True)
        for name, body in WRAPPERS.items():
            path = self.base / 'bin' / name
            path.write_text('#!/bin/sh\n' + body, encoding='utf-8', newline='\n')
            path.chmod(0o755)
        for target in ('com.example.one', 'com.example.two'):
            (self.base / 'system/app' / (target + '.apk')).write_bytes(target.encode())
        self.state = self.base / 'data/adb/open-voyah-controls-overlays'
        self.boot = 1
        (self.base / 'proc/sys/kernel/random/boot_id').write_text('boot-1\n')

    def posix(self, path):
        if os.name != 'nt':
            return Path(path).as_posix()
        return subprocess.check_output([BASH, '-c', f"cygpath -u '{Path(path).as_posix()}'"], text=True).strip()

    def run_mode(self, mode, *arguments, success=True, fingerprint=FINGERPRINT, serial=SERIAL, env=''):
        root = self.posix(self.base)
        prefix = (f"export OVC_ROOT='{root}' OVC_TEST_PATH='{root}/bin:/usr/bin:/bin' FAKE_FP='{FINGERPRINT}' "
                  f"FAKE_SN='{SERIAL}' FAKE_DATE='{BUILD_DATE}' {env}\n"
                  f"set -- {mode} '{fingerprint}' {serial} {' '.join(arguments)}\n")
        script = self.base / 'run.sh'
        script.write_text(prefix + SCRIPT.read_text(encoding='utf-8'), encoding='utf-8', newline='\n')
        result = subprocess.run([self.shell, script.as_posix()], capture_output=True, text=True, timeout=60)
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode == 0, success, output)
        return output

    def stage(self, *overlays, pin=(FINGERPRINT, BUILD_DATE)):
        """Writes what the caller stages as root: the APKs and a manifest. Overlays are (short, content, target)."""
        stage = self.state / 'stage'
        shutil.rmtree(stage, ignore_errors=True)
        stage.mkdir(parents=True)
        rows = ['pin %s %s' % pin]
        for short, content, target, *wrong in overlays:
            data = (short + content).encode()
            (stage / (PREFIX + short + '.apk')).write_bytes(data)
            app = self.base / 'system/app' / (target + '.apk')
            target_sha = '0' * 64 if wrong or not app.exists() else sha(app.read_bytes())
            rows.append('overlay %s %s %s %s' % (sha(data), PREFIX + short + '.apk', target, target_sha))
        (stage / 'manifest').write_text(''.join(row + '\n' for row in rows), newline='\n')

    def restart(self):
        """A new start: new overlays come up disabled, removed ones are forgotten, the boot id changes."""
        self.boot += 1
        (self.base / 'proc/sys/kernel/random/boot_id').write_text('boot-%d\n' % self.boot)
        fake = self.base / 'fake/overlays'
        known = dict(line.split() for line in fake.read_text().splitlines()) if fake.exists() else {}
        lines = ['%s %s' % (path.stem, known.get(path.stem, 'false')) for path in sorted(self.vendor.glob(PREFIX + '*.apk'))]
        fake.write_text(''.join(line + '\n' for line in lines), newline='\n')

    def ours(self):
        return {path.name: path.read_bytes() for path in self.vendor.glob(PREFIX + '*.apk')}

    def states(self):
        return (self.base / 'fake/overlays').read_text().split()

    def test_install_restart_enable_reinstall_and_roll_back(self):
        self.stage(('one', ' v1', 'com.example.one'), ('two', ' v1', 'com.example.two'))
        output = self.run_mode('install')
        self.assertIn('OVC_OVERLAYS_NEED_RESTART', output)
        self.assertEqual(self.ours(), {PREFIX + 'one.apk': b'one v1', PREFIX + 'two.apk': b'two v1'})
        self.assertFalse((self.state / 'stage').exists())
        self.assertIn('u:object_r:vendor_overlay_file:s0', (self.base / 'fake/chcon').read_text())
        self.assertTrue((self.state / 'previous/complete').exists())
        self.assertIn('OVC_OVERLAY_INSTALLED_THIS_BOOT', self.run_mode('status'))
        # Android still holds the replaced overlays under the same names until the next start.
        self.assertIn('Restart the head unit first', self.run_mode('enable', success=False))

        self.restart()
        self.assertNotIn('OVC_OVERLAY_INSTALLED_THIS_BOOT', self.run_mode('status'))
        self.assertIn('enabled=true', self.run_mode('enable'))
        self.assertEqual(self.states(), [PREFIX + 'one', 'true', PREFIX + 'two', 'true'])

        # A new build replaces the whole set; the set it replaced is kept for a rollback.
        self.stage(('one', ' v2', 'com.example.one'))
        self.run_mode('install')
        self.assertEqual(self.ours(), {PREFIX + 'one.apk': b'one v2'})
        self.assertEqual(self.states(), [PREFIX + 'one', 'false', PREFIX + 'two', 'false'])
        self.assertIn('OVC_OVERLAY_ROLLBACK_AVAILABLE', self.run_mode('status'))

        self.run_mode('rollback')
        self.assertEqual(self.ours(), {PREFIX + 'one.apk': b'one v1', PREFIX + 'two.apk': b'two v1'})
        self.assertEqual(len((self.state / 'installed').read_text().splitlines()), 2)
        self.assertFalse((self.state / 'previous').exists())
        self.assertIn('There is no install to roll back.', self.run_mode('rollback', success=False))
        self.assertEqual((self.vendor / 'framework-res__auto_generated_rro_vendor.apk').read_bytes(), b'oem')

    def installed_v1(self):
        self.stage(('one', ' v1', 'com.example.one'), ('two', ' v1', 'com.example.two'))
        self.run_mode('install')
        self.stage(('one', ' v2', 'com.example.one'), ('two', ' v2', 'com.example.two'))
        return self.ours(), (self.state / 'installed').read_text()

    def test_a_failure_before_the_swap_changes_nothing(self):
        # Every new file is written and checked first (a refused relabel or copy fails here).
        good, record = self.installed_v1()
        output = self.run_mode('install', success=False, env="OVC_TEST_FAIL='install-write:1'")
        self.assertIn('the overlays in place are unchanged', output)
        self.assertNotIn('OVC_OVERLAY_REMOVED', output)
        self.assertEqual(self.ours(), good)
        self.assertEqual((self.state / 'installed').read_text(), record)
        self.assertEqual(list(self.vendor.glob('.*.tmp')), [])
        self.assertFalse((self.state / 'installed.new').exists())

    def test_a_failure_during_the_swap_puts_the_previous_set_back(self):
        good, record = self.installed_v1()
        output = self.run_mode('install', success=False, env="OVC_TEST_FAIL='install-move:1'")
        self.assertIn('OVC_OVERLAY_PREVIOUS_RESTORED', output)
        self.assertIn('the previous set is back in place', output)
        self.assertEqual(self.ours(), good)
        self.assertEqual((self.state / 'installed').read_text(), record)
        self.assertEqual(list(self.vendor.glob('.*.tmp')), [])
        # A second failed attempt still keeps the good set, not a half-written one.
        self.run_mode('install', success=False, env="OVC_TEST_FAIL='install-move:1'")
        self.assertEqual({path.name for path in (self.state / 'previous').glob('*.apk')}, set(good))
        self.assertEqual(self.ours(), good)

    def test_a_restore_that_cannot_write_says_so(self):
        good, _ = self.installed_v1()
        output = self.run_mode('install', success=False, env="OVC_TEST_FAIL='install-move:1 restore-write:0'")
        self.assertIn('OVC_OVERLAY_RESTORE_FAILED', output)
        self.assertNotIn('the previous set is back in place', output)
        self.assertIn('could not be put back', output)
        # The kept set survives for a later rollback.
        self.assertEqual({path.name for path in (self.state / 'previous').glob('*.apk')}, set(good))
        self.assertIn('OVC_OVERLAY_INTERRUPTED', self.run_mode('status'))
        self.assertIn('OVC_OVERLAY_INTERRUPTED', self.run_mode('rollback'))
        self.assertEqual(self.ours(), good)
        self.assertNotIn('OVC_OVERLAY_INTERRUPTED', self.run_mode('status'))

    def test_rollback_needs_a_restart_before_enable(self):
        self.installed_v1()
        self.restart()
        self.run_mode('install')
        self.restart()
        self.run_mode('rollback')
        self.assertIn('OVC_OVERLAY_INSTALLED_THIS_BOOT', self.run_mode('status'))
        self.assertIn('Restart the head unit first', self.run_mode('enable', success=False))

    def test_a_failed_read_only_remount_is_reported(self):
        self.stage(('one', ' v1', 'com.example.one'))
        output = self.run_mode('install', env='OVC_TEST_REMOUNT=1 OVC_TEST_RO_FAILS=1')
        self.assertIn('OVC_OVERLAY_VENDOR_STILL_WRITABLE', output)
        self.assertIn('OVC_OVERLAYS_NEED_RESTART', output)

    def test_a_broken_set_on_disk_never_replaces_the_rollback_copy(self):
        self.stage(('one', ' v1', 'com.example.one'))
        self.run_mode('install')
        self.stage(('one', ' v2', 'com.example.one'))
        self.run_mode('install')
        (self.vendor / (PREFIX + 'one.apk')).write_bytes(b'damaged')
        self.stage(('one', ' v3', 'com.example.one'))
        self.assertIn('OVC_OVERLAY_PREVIOUS_KEPT', self.run_mode('install'))
        self.assertEqual((self.state / 'previous' / (PREFIX + 'one.apk')).read_bytes(), b'one v1')

    def test_rollback_of_a_first_install_leaves_no_overlay(self):
        self.stage(('one', ' v1', 'com.example.one'))
        self.run_mode('install')
        self.run_mode('rollback')
        self.assertEqual(self.ours(), {})
        self.assertFalse((self.state / 'installed').exists())

    def test_apps_that_do_not_match_are_skipped(self):
        self.stage(('one', ' v1', 'com.example.one'), ('two', ' v1', 'com.example.two', 'other build'),
                   ('three', ' v1', 'com.example.missing'))
        output = self.run_mode('install')
        self.assertIn('OVC_OVERLAY_SKIPPED %stwo.apk other-build' % PREFIX, output)
        self.assertIn('OVC_OVERLAY_SKIPPED %sthree.apk missing' % PREFIX, output)
        self.assertEqual(self.ours(), {PREFIX + 'one.apk': b'one v1'})
        self.stage(('two', ' v1', 'com.example.two', 'other build'))
        self.assertIn('No factory app on this head unit matches', self.run_mode('install', success=False))
        self.assertEqual(self.ours(), {PREFIX + 'one.apk': b'one v1'})

    def test_enable_and_disable_single_apps_with_few_dumpsys_calls(self):
        self.stage(('one', ' v1', 'com.example.one'), ('two', ' v1', 'com.example.two'))
        self.run_mode('install')
        self.restart()
        (self.base / 'fake/dumpsys').unlink()
        self.run_mode('enable')
        self.assertEqual(len((self.base / 'fake/dumpsys').read_text().split()), 2)
        self.run_mode('disable', PREFIX + 'two')
        self.assertEqual(self.states(), [PREFIX + 'one', 'true', PREFIX + 'two', 'false'])
        self.assertIn('Unexpected overlay', self.run_mode('enable', 'com.other.overlay', success=False))
        status = self.run_mode('status')
        self.assertIn('OVC_OVERLAY_RECORD ' + PREFIX + 'two.apk ', status)
        self.assertIn('OVC_OVERLAY ' + PREFIX + 'two state=STATE_DISABLED enabled=false', status)

    def test_remove_deletes_only_this_projects_overlays(self):
        self.stage(('one', ' v1', 'com.example.one'))
        self.run_mode('install')
        (self.vendor / 'org.other.overlay.apk').write_bytes(b'other')
        (self.vendor / ('.' + PREFIX + 'one.apk.tmp')).write_bytes(b'left over')
        self.restart()
        output = self.run_mode('remove')
        self.assertIn('OVC_OVERLAY_REMOVED ' + PREFIX + 'one.apk', output)
        self.assertEqual(sorted(path.name for path in self.vendor.iterdir()),
                         ['framework-res__auto_generated_rro_vendor.apk', 'org.other.overlay.apk'])
        self.assertFalse((self.state / 'installed').exists())

    def test_remount_is_undone_after_a_write(self):
        self.stage(('one', ' v1', 'com.example.one'))
        self.run_mode('install', env='OVC_TEST_REMOUNT=1')
        self.assertEqual((self.base / 'fake/mount').read_text().splitlines(),
                         ['-o remount,rw /vendor', '-o remount,ro /vendor'])
        self.run_mode('status', env='OVC_TEST_REMOUNT=1')
        self.assertEqual(len((self.base / 'fake/mount').read_text().splitlines()), 2)

    def test_refuses_other_builds_units_and_damaged_files_without_changes(self):
        self.stage(('one', ' v1', 'com.example.one'))
        damaged = self.state / 'stage' / (PREFIX + 'one.apk')
        damaged.write_bytes(b'changed after staging')
        self.assertIn('did not arrive intact', self.run_mode('install', success=False))
        self.stage(('one', ' v1', 'com.example.one'), pin=(FINGERPRINT, '1'))
        self.assertIn('built for another firmware build', self.run_mode('install', success=False))
        self.stage(('one', ' v1', 'com.example.one'), pin=('other', BUILD_DATE))
        self.assertIn('built for another firmware build', self.run_mode('install', success=False))
        self.stage(('one', ' v1', 'com.example.one'))
        self.assertIn('different firmware', self.run_mode('install', success=False, fingerprint='other'))
        self.assertIn('different head unit', self.run_mode('install', success=False, serial='other'))
        self.assertEqual(self.ours(), {})
        # Controls passes "-" for the serial: it runs on the unit it changes.
        self.run_mode('install', serial='-')
        self.assertEqual(set(self.ours()), {PREFIX + 'one.apk'})

    def test_an_unpinned_set_is_refused(self):
        self.stage(('one', ' v1', 'com.example.one'), pin=('-', '-'))
        self.assertIn('built for another firmware build', self.run_mode('install', success=False))
        self.assertEqual(self.ours(), {})

    def test_kept_names_are_replaced_in_place(self):
        self.stage(('one', ' v1', 'com.example.one'), ('two', ' v1', 'com.example.two'))
        self.run_mode('install')
        self.stage(('one', ' v2', 'com.example.one'))
        output = self.run_mode('install')
        # "one" is renamed over the old file, never deleted first; only "two", which the new set drops, is deleted.
        self.assertNotIn('OVC_OVERLAY_REMOVED %sone.apk' % PREFIX, output)
        self.assertIn('OVC_OVERLAY_REMOVED %stwo.apk' % PREFIX, output)
        self.assertEqual(self.ours(), {PREFIX + 'one.apk': b'one v2'})

    def test_nothing_staged_is_refused(self):
        self.assertIn('Nothing is staged', self.run_mode('install', success=False))


DASH = r'C:\Program Files\Git\usr\bin\dash.exe' if os.name == 'nt' else shutil.which('dash')


@unittest.skipUnless(DASH and Path(DASH).exists(), 'dash is needed')
class DashInstallTest(InstallTest):
    """The same cases under dash, a strict POSIX shell closer to Android's mksh than bash."""
    shell = DASH


if __name__ == '__main__':
    unittest.main()
