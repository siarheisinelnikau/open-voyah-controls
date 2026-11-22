"""Resource overlays from the built-in dictionary: name mapping, escaping and the aapt2 build."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_text_overlays as overlays  # noqa: E402

SDK = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
           or Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk')
AAPT2 = SDK / 'build-tools/35.0.0' / ('aapt2.exe' if os.name == 'nt' else 'aapt2')
ANDROID_JAR = SDK / 'platforms/android-30/android.jar'

DEFAULT_STRINGS = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="wifi">Wi-Fi settings</string>
    <string name="already_russian">Bluetooth</string>
    <string name="multiline">"Line one
Line two"</string>
    <string name="apostrophe">Don\\'t display</string>
    <string name="template">%1$s connected</string>
    <string name="two_values" formatted="false">%s of %s</string>
    <string name="broken">Bad %d</string>
    <string name="untouched">Untranslated</string>
    <string name="styled">Press <b>OK</b></string>
    <string name="chinese">设置</string>
    <string name="item_ref">Off</string>
    <string name="APKTOOL_DUMMY_1">Wi-Fi settings</string>
    <string-array name="modes">
        <item>On</item>
        <item>@string/item_ref</item>
        <item>Unknown mode</item>
    </string-array>
    <string-array name="refs_only">
        <item>@string/item_ref</item>
    </string-array>
    <string-array name="with_attribute">
        <item>On</item>
        <item>?android:attr/textColor</item>
    </string-array>
    <plurals name="minutes"><item quantity="one">%d minute</item></plurals>
</resources>
'''
RU_STRINGS = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="already_russian">Блютуз</string>
</resources>
'''
EN_STRINGS = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="untouched">Wi-Fi settings</string>
</resources>
'''
TABLE = {
    'Wi-Fi settings': 'Настройки Wi-Fi',
    'Bluetooth': 'Bluetooth',
    'Line one\nLine two': 'Строка один\nСтрока два',
    "Don't display": 'Не показывать',
    '%1$s connected': '%1$s подключено',
    '%s of %s': '%s из %s',
    'Bad %d': 'Плохо',
    'Press OK': 'Нажмите OK',
    '设置': 'Настройки',
    'On': 'Вкл.',
    'Off': 'Выкл.',
    'Set from code': 'Задано в коде',
}


def fixture(root):
    res = Path(root) / 'res'
    for directory, text in (('values', DEFAULT_STRINGS), ('values-ru', RU_STRINGS), ('values-en', EN_STRINGS)):
        (res / directory).mkdir(parents=True)
        (res / directory / 'strings.xml').write_text(text, encoding='utf-8')
    (res / 'values' / 'public.xml').write_text('<resources><public type="string" name="wifi" id="0x7f0b0000" />'
                                               '</resources>', encoding='utf-8')
    return res


class EscapingTest(unittest.TestCase):
    SAMPLES = ['Plain text', "Don't", 'Say "hi"', 'back\\slash', 'Line\nbreak', 'Tab\there', '@not a reference',
               '?not an attribute', ' leading space', 'trailing space ', 'two  spaces', 'a & b < c > d',
               '%1$s подключено', '50% заряда', 'Юникод — ок', '\\n literal', '"quoted"', "'single'"]

    def test_round_trip(self):
        for text in self.SAMPLES:
            encoded = overlays.encode_resource_text(text)
            body = ET.fromstring('<string>%s</string>' % encoded).text or ''
            self.assertEqual(overlays.decode_resource_text(body), text, encoded)

    def test_decode_follows_aapt2_rules(self):
        self.assertEqual(overlays.decode_resource_text('  a \n  b  '), 'a b')
        self.assertEqual(overlays.decode_resource_text('"  a  "'), '  a  ')
        self.assertEqual(overlays.decode_resource_text('\\@x \\u0041\\\'s'), "@x A's")
        self.assertEqual(overlays.decode_resource_text('"Line one\nLine two"'), 'Line one\nLine two')

    def test_specifiers_match_the_preset_test(self):
        self.assertEqual(overlays.specifiers('%1$s of %2$d, 50%% %s'), ['%1$s', '%2$d', '%%', '%s'])
        self.assertEqual(overlays.specifiers('50% for'), [])


class PlanTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.resources = overlays.read_resources(fixture(self.temp.name))
        self.plan = overlays.plan_overlay('com.example.target', TABLE, self.resources, 'ru')

    def test_maps_texts_the_app_shows_for_the_language(self):
        strings = self.plan.strings
        self.assertEqual(strings['wifi'], ('Настройки Wi-Fi', None))
        self.assertEqual(strings['multiline'][0], 'Строка один\nСтрока два')
        self.assertEqual(strings['apostrophe'][0], 'Не показывать')
        self.assertEqual(strings['chinese'][0], 'Настройки')
        # values-ru already wins for this name; values-en is not consulted for a Russian locale.
        self.assertNotIn('already_russian', strings)
        self.assertNotIn('untouched', strings)
        self.assertNotIn('APKTOOL_DUMMY_1', strings)

    def test_templates_keep_specifiers_and_formatted_attribute(self):
        self.assertEqual(self.plan.strings['template'], ('%1$s подключено', None))
        self.assertEqual(self.plan.strings['two_values'], ('%s из %s', 'false'))
        self.assertNotIn('broken', self.plan.strings)
        self.assertIn(('string', 'broken', 'format specifiers differ'), self.plan.skipped)
        self.assertIn(('string', 'styled', 'styled text'), self.plan.skipped)

    def test_arrays_inline_items_and_skip_what_strings_cover(self):
        self.assertEqual(self.plan.arrays['modes'], ['Вкл.', 'Выкл.', 'Unknown mode'])
        self.assertNotIn('refs_only', self.plan.arrays)
        self.assertNotIn('with_attribute', self.plan.arrays)
        self.assertIn(('string-array', 'with_attribute', 'item that cannot be inlined'), self.plan.skipped)
        self.assertEqual(self.plan.plurals, 1)

    def test_reports_entries_no_resource_holds(self):
        self.assertEqual(self.plan.unmatched_keys, ['Bad %d', 'Bluetooth', 'Press OK', 'Set from code'])
        self.assertIn('Off', self.plan.matched_keys)

    def test_region_directory_takes_precedence(self):
        res = Path(self.temp.name) / 'res' / 'values-ru-rRU'
        res.mkdir()
        (res / 'strings.xml').write_text('<resources><string name="wifi">Off</string></resources>', encoding='utf-8')
        plan = overlays.plan_overlay('com.example.target', TABLE, overlays.read_resources(res.parent), 'ru', 'RU')
        self.assertEqual(plan.strings['wifi'][0], 'Выкл.')

    def test_written_sources(self):
        out = overlays.write_sources(self.plan, Path(self.temp.name) / 'src', 'brand/product/device:11/X/1:user/k')
        values = ET.parse(out / 'res/values-ru/strings.xml').getroot()
        decoded = {item.get('name'): overlays.decode_resource_text(item.text or '') for item in values.iter('string')}
        self.assertEqual(decoded, {name: value for name, (value, _) in self.plan.strings.items()})
        self.assertEqual(values.find("string[@name='two_values']").get('formatted'), 'false')
        manifest = (out / 'AndroidManifest.xml').read_text(encoding='utf-8')
        android = '{http://schemas.android.com/apk/res/android}'
        element = ET.fromstring(manifest).find('overlay')
        self.assertEqual(ET.fromstring(manifest).get('package'), 'org.openvoyah.controls.overlay.ru.example.target')
        self.assertEqual(element.get(android + 'targetPackage'), 'com.example.target')
        self.assertEqual(element.get(android + 'isStatic'), 'false')
        self.assertEqual(element.get(android + 'requiredSystemPropertyValue'), 'brand/product/device:11/X/1:user/k')
        self.assertNotIn('requiredSystemProperty', overlays.manifest_xml('com.example.target', 'ru'))


class DictionaryTest(unittest.TestCase):
    def test_default_targets_have_tables(self):
        packages, targets, digest = overlays.load_tables('ru')
        self.assertRegex(digest, '^[0-9a-f]{64}$')
        for target in overlays.DEFAULT_PACKAGES:
            self.assertIn(target, targets)
        for target in targets:
            self.assertTrue(packages[target], target)

    def test_overlay_tables_add_to_the_preset(self):
        preset, _ = overlays.load_dictionary('ru')
        packages, _, _ = overlays.load_tables('ru')
        for target, table in preset.items():
            for key, value in table.items():
                self.assertEqual(packages[target][key], value, key)

    def test_overlay_dictionary_follows_the_preset_rules(self):
        # Same rules as tests/localization-presets.test.cjs for the texts only the overlays translate.
        path = overlays.OVERLAY_DICTIONARIES / 'ru.json'
        document = json.loads(path.read_text(encoding='utf-8'))
        self.assertEqual((document['schema'], document['products']), (1, []))
        cjk, cyrillic = re.compile('[㐀-鿿]'), re.compile('[Ѐ-ӿ]')
        for package, table in document['packages'].items():
            self.assertRegex(package, r'^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$')
            self.assertTrue(table, package)
            for source, value in table.items():
                where = '%s %r' % (package, source)
                self.assertTrue(source.strip() and value.strip() and value != source, where)
                # Not bundled into the agent, so whole legal texts fit (the presets keep 1,024).
                self.assertLessEqual(max(len(source), len(value)), 4096, where)
                self.assertEqual(overlays.specifiers(value), overlays.specifiers(source), where)
                self.assertEqual(source.count('\n'), value.count('\n'), where)
                self.assertEqual(source.strip() == source, value.strip() == value, where)
                if not cjk.search(source):
                    self.assertRegex(value, cyrillic, where)

    def test_overlay_names_are_unique_and_short(self):
        names = {target: overlays.overlay_package(target, 'ru') for target in (
            'com.qinggan.app.setting', 'com.qinggan.bluetoothphone', 'com.qinggan.app.qgime',
            'com.qinggan.app.qgime.second', 'com.thunder.carplay', 'com.qinggan.media')}
        self.assertEqual(names['com.qinggan.app.setting'], 'org.openvoyah.controls.overlay.ru.setting')
        self.assertEqual(names['com.qinggan.bluetoothphone'], 'org.openvoyah.controls.overlay.ru.bluetoothphone')
        self.assertEqual(names['com.qinggan.app.qgime.second'], 'org.openvoyah.controls.overlay.ru.qgime.second')
        self.assertEqual(names['com.thunder.carplay'], 'org.openvoyah.controls.overlay.ru.thunder.carplay')
        self.assertEqual(len(set(names.values())), len(names))

    def test_release_signer_passwords_stay_in_the_environment(self):
        environment = {name: 'value' for name in overlays.RELEASE_SIGNER}
        signer = overlays.signer_from_environment(environment)
        self.assertEqual(signer['kind'], 'release')
        self.assertEqual(signer['store_pass'], 'env:OPEN_VOYAH_RELEASE_STORE_PASSWORD')
        self.assertEqual(signer['key_pass'], 'env:OPEN_VOYAH_RELEASE_KEY_PASSWORD')


@unittest.skipUnless(AAPT2.is_file() and ANDROID_JAR.is_file(), 'Android build-tools 35.0.0 and android-30 needed')
class Aapt2Test(unittest.TestCase):
    def test_linked_overlay_holds_every_translation(self):
        with tempfile.TemporaryDirectory() as temp:
            temp = Path(temp)
            plan = overlays.plan_overlay('com.example.target', TABLE, overlays.read_resources(fixture(temp)), 'ru')
            sources = overlays.write_sources(plan, temp / 'src')
            subprocess.run([str(AAPT2), 'compile', '--dir', str(sources / 'res'), '-o', str(temp / 'c.zip')],
                           check=True, capture_output=True)
            subprocess.run([str(AAPT2), 'link', '--manifest', str(sources / 'AndroidManifest.xml'), '-I',
                            str(ANDROID_JAR), '--min-sdk-version', '28', '--target-sdk-version', '30',
                            '--no-resource-removal', '--no-compress', '-o', str(temp / 'o.apk'), str(temp / 'c.zip')],
                           check=True, capture_output=True)
            pool = subprocess.run([str(AAPT2), 'dump', 'strings', str(temp / 'o.apk')], check=True,
                                  capture_output=True, encoding='utf-8').stdout
            entries = {entry.rstrip('\n') for entry in re.split(r'\nString #\d+ : ', '\n' + pool.split('\n', 1)[1])[1:]}
            expected = {value for value, _ in plan.strings.values()} | {i for items in plan.arrays.values() for i in items}
            self.assertEqual(expected - entries, set())
            table = subprocess.run([str(AAPT2), 'dump', 'resources', str(temp / 'o.apk')], check=True,
                                   capture_output=True, encoding='utf-8').stdout
            self.assertIn('string/two_values', table)
            self.assertIn('(ru)', table)


if __name__ == '__main__':
    unittest.main()
