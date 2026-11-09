"""Execute the real transaction against an isolated filesystem, never a vehicle."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'app/src/main/assets/cluster/transaction.sh'
BASH = shutil.which('bash') if os.name != 'nt' else r'C:\Program Files\Git\bin\bash.exe'
FILES = ['gauge', 'light', 'point', 'point_normal.kzb']


class TransactionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).as_posix()
        self.base = Path(self.root)
        self.var = self.base / 'var/ovc-cluster'
        (self.var / 'stage').mkdir(parents=True)
        for name in FILES:
            target = self.target(name)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(('original ' + name).encode())
            (self.var / 'stage' / name).write_bytes(('translated ' + name).encode())
        self.commands = self.base / 'commands'
        self.commands.mkdir()
        self.wrapper('mount', 'exit 0\n')
        self.wrapper('id', 'echo 0\n')

    def tearDown(self):
        self.temp.cleanup()

    def target(self, name):
        return self.base / ('usr/share/theme/0' if name.endswith('.kzb') else 'usr/bin') / name

    def wrapper(self, name, body):
        path = self.commands / name
        path.write_text('#!/bin/sh\n' + body, encoding='utf-8', newline='\n')
        path.chmod(0o755)

    def run_action(self, action, success=True):
        command_path = subprocess.check_output([BASH, '-c', f"cygpath -u '{self.commands.as_posix()}'"], text=True).strip() if os.name == 'nt' else self.commands.as_posix()
        prefix = f"export PATH='{command_path}':$PATH\nOVC_ROOT='{self.root}'\nOVC_FW='test-firmware'\nOVC_ACTION='{action}'\n"
        for name, var in zip(FILES, ['GAUGE', 'LIGHT', 'POINT', 'THEME']):
            original = ('translated ' + name).encode()
            expected = self.base / 'expected'
            expected.write_bytes(original)
            crc = subprocess.check_output([BASH, '-c', f"cksum '{expected.as_posix()}'"], text=True).split()[:2]
            prefix += f"OVC_{var}='{' '.join(crc)}'\n"
        script = self.base / 'run.sh'
        script.write_text(prefix + SCRIPT.read_text(encoding='utf-8'), encoding='utf-8', newline='\n')
        result = subprocess.run([BASH, script.as_posix()], capture_output=True, text=True, timeout=30)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def assert_original(self):
        for name in FILES:
            self.assertEqual(self.target(name).read_bytes(), ('original ' + name).encode())

    def test_apply_restore_and_repeat_restore_keep_backups(self):
        self.run_action('prepare')
        self.run_action('apply')
        self.assertEqual((self.var / 'state').read_text().strip(), 'APPLIED')
        for name in FILES:
            self.assertEqual(self.target(name).read_bytes(), ('translated ' + name).encode())
        self.run_action('restore')
        self.run_action('restore')
        self.assert_original()
        self.assertTrue((self.var / 'backup/ready').exists())

    def test_corrupt_stage_leaves_all_targets_unchanged(self):
        self.run_action('prepare')
        (self.var / 'stage/point').write_text('damaged')
        self.run_action('apply', False)
        self.assert_original()

    def test_corrupt_backup_blocks_apply_and_restore(self):
        self.run_action('prepare')
        (self.var / 'backup/light').write_text('damaged')
        self.run_action('apply', False)
        self.run_action('restore', False)
        self.assert_original()

    def test_failure_after_two_replacements_rolls_back(self):
        self.run_action('prepare')
        self.wrapper('mv', f'''for last; do :; done
if [ "$last" = '{self.target('point').as_posix()}' ] && [ ! -e '{self.root}/failed' ]; then
    touch '{self.root}/failed'; exit 1
fi
exec /usr/bin/mv "$@"
''')
        self.run_action('apply', False)
        self.assert_original()
        self.assertEqual((self.var / 'state').read_text().strip(), 'RESTORED')

    def test_process_death_requires_explicit_restore(self):
        self.run_action('prepare')
        self.wrapper('mv', f'''for last; do :; done
if [ "$last" = '{self.target('point').as_posix()}' ] && [ ! -e '{self.root}/failed' ]; then
    touch '{self.root}/failed'; kill -KILL "$PPID"; exit 1
fi
exec /usr/bin/mv "$@"
''')
        self.run_action('apply', False)
        self.assertEqual((self.var / 'state').read_text().strip(), 'apply_IN_PROGRESS')
        self.run_action('inspect')
        self.run_action('apply', False)
        self.run_action('restore')
        self.assert_original()

    def test_inspection_does_not_create_backup(self):
        self.run_action('inspect')
        self.assertFalse((self.var / 'backup').exists())
        self.assertFalse((self.var / 'lock').exists())
        self.assert_original()

    def test_changed_original_blocks_apply(self):
        self.run_action('prepare')
        self.target('gauge').write_text('firmware changed')
        self.run_action('apply', False)
        self.assertEqual(self.target('gauge').read_text(), 'firmware changed')

    def test_incomplete_staging_does_not_move_active_files(self):
        self.run_action('prepare')
        (self.var / 'stage/light').unlink()
        self.run_action('apply', False)
        self.assert_original()


if __name__ == '__main__':
    unittest.main()
