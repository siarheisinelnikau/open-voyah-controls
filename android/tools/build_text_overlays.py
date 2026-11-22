"""Build runtime resource overlays (RRO) that show a built-in dictionary in factory apps.

The Frida localization agent cannot attach to 32-bit app processes (on the 97C head unit, Settings and the Bluetooth
phone), so for those apps the same dictionary (`hooks/localization/<language>.json`) is turned into resource overlays.
Each dictionary key is an exact text the factory app shows; this tool finds the string resources whose text, as the
app resolves it for the dictionary's language, equals a key and writes the translation under the same resource name in
`values-<language>`. Only this project's dictionary is read; nothing is taken from other overlays.

Input is an apktool-decoded copy of each factory APK (`<root>/<package>/res`). The overlays are linked with aapt2,
aligned and signed with the release key from the OPEN_VOYAH_RELEASE_* variables, or with the Android debug key when
those are not set. Every result is listed in `overlays.json` next to the APKs.
"""
import argparse
from dataclasses import dataclass, field
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape as xml_escape

ROOT = Path(__file__).resolve().parents[1]
DICTIONARIES = ROOT / 'hooks/localization'
# The apps the localization agent cannot attach to on the 97C head unit (32-bit processes).
DEFAULT_PACKAGES = ('com.qinggan.app.setting', 'com.qinggan.bluetoothphone')
OVERLAY_PREFIX = 'org.openvoyah.controls.overlay'
# Same pattern as tests/localization-presets.test.cjs: a translation keeps the source's specifiers in the same order.
SPEC = re.compile(r'%(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[sdcfxXoeEgGbBhHn%]')
NAME = re.compile(r'^[A-Za-z_][A-Za-z0-9_.]*$')
LANGUAGE = re.compile(r'^[a-z]{2,3}$')
REGION = re.compile(r'^[A-Z]{2}$')
RELEASE_SIGNER = ('OPEN_VOYAH_RELEASE_KEYSTORE', 'OPEN_VOYAH_RELEASE_STORE_PASSWORD',
                  'OPEN_VOYAH_RELEASE_KEY_ALIAS', 'OPEN_VOYAH_RELEASE_KEY_PASSWORD')


def specifiers(text):
    return SPEC.findall(text)


def decode_resource_text(raw):
    """Return the text Android shows for a <string> body (XML entities already resolved), as aapt2 reads it."""
    out = []
    quoted = False
    pending_space = False
    index = 0
    while index < len(raw):
        char = raw[index]
        if char == '"':
            quoted = not quoted
            index += 1
            continue
        if char == '\\' and index + 1 < len(raw):
            if pending_space:
                out.append(' ')
                pending_space = False
            code = raw[index + 1]
            if code == 'u' and re.fullmatch(r'[0-9A-Fa-f]{4}', raw[index + 2:index + 6] or ''):
                out.append(chr(int(raw[index + 2:index + 6], 16)))
                index += 6
                continue
            out.append({'n': '\n', 't': '\t'}.get(code, code))
            index += 2
            continue
        if not quoted and char in ' \t\r\n':
            pending_space = bool(out)
            index += 1
            continue
        if pending_space:
            out.append(' ')
            pending_space = False
        out.append(char)
        index += 1
    return ''.join(out)


def encode_resource_text(text):
    """Return an XML-escaped <string> body that decode_resource_text (and aapt2) reads back as exactly `text`."""
    body = (text.replace('\\', '\\\\').replace('"', '\\"').replace("'", "\\'")
            .replace('\n', '\\n').replace('\t', '\\t'))
    if body[:1] in ('@', '?'):
        body = '\\' + body
    # Outside quotes aapt2 trims the ends and folds runs of white space, so such text is kept in quotes.
    if body != body.strip(' ') or '  ' in body:
        body = '"' + body + '"'
    return xml_escape(body)


@dataclass
class TargetResources:
    """String resources of one decoded APK, per values directory."""
    strings: dict = field(default_factory=dict)   # dir -> name -> (text, formatted attr or None, styled)
    arrays: dict = field(default_factory=dict)    # dir -> name -> [raw item text or None if styled]
    plurals: set = field(default_factory=set)


def read_resources(res_dir):
    resources = TargetResources()
    for values in sorted(Path(res_dir).glob('values*')):
        if not values.is_dir():
            continue
        strings, arrays = {}, {}
        for xml in sorted(values.glob('*.xml')):
            if xml.name in ('public.xml', 'ids.xml'):
                continue
            for element in ET.parse(xml).getroot():
                name = element.get('name', '')
                if not NAME.match(name) or name.startswith('APKTOOL_DUMMY'):
                    continue
                if element.tag == 'string':
                    # Styled text (<b>, <xliff:g>) keeps its plain text for the report only.
                    text = decode_resource_text(''.join(element.itertext()))
                    strings[name] = (text, element.get('formatted'), len(element) > 0)
                elif element.tag == 'string-array':
                    arrays[name] = [None if len(item) else (item.text or '') for item in element.findall('item')]
                elif element.tag == 'plurals':
                    resources.plurals.add(name)
        if strings:
            resources.strings[values.name] = strings
        if arrays:
            resources.arrays[values.name] = arrays
    return resources


def locale_chain(language, region=None):
    """values directories Android consults for a locale without other qualifiers, most specific first."""
    chain = ['values-' + language, 'values']
    if region:
        chain.insert(0, 'values-%s-r%s' % (language, region))
    return chain


def effective(table, chain, name):
    for directory in chain:
        if name in table.get(directory, {}):
            return table[directory][name]
    return None


@dataclass
class OverlayPlan:
    target: str
    language: str
    strings: dict = field(default_factory=dict)   # name -> (translation, formatted attr or None)
    arrays: dict = field(default_factory=dict)    # name -> [item text]
    skipped: list = field(default_factory=list)   # (kind, name, reason)
    matched_keys: set = field(default_factory=set)
    unmatched_keys: list = field(default_factory=list)
    plurals: int = 0


def plan_overlay(target, table, resources, language, region=None):
    """Map dictionary keys to the target's resource names, as the app resolves them for the dictionary language."""
    chain = locale_chain(language, region)
    plan = OverlayPlan(target, language, plurals=len(resources.plurals))
    names = sorted({name for directory in chain for name in resources.strings.get(directory, {})})
    for name in names:
        text, formatted, styled = effective(resources.strings, chain, name)
        if text not in table:
            continue
        if styled:
            plan.skipped.append(('string', name, 'styled text'))
            continue
        value = table[text]
        if specifiers(value) != specifiers(text):
            plan.skipped.append(('string', name, 'format specifiers differ'))
            continue
        plan.strings[name] = (value, formatted)
        plan.matched_keys.add(text)
    array_names = sorted({name for directory in chain for name in resources.arrays.get(directory, {})})
    for name in array_names:
        resolved, matched, blocked = [], [], False
        for raw in effective(resources.arrays, chain, name):
            literal = raw is not None and not raw.startswith(('@', '?'))
            if literal:
                text = decode_resource_text(raw)
            elif raw is not None and raw.startswith('@string/'):
                entry = effective(resources.strings, chain, raw[len('@string/'):])
                text = entry[0] if entry and not entry[2] else None
            else:
                text = None
            if text is None:
                blocked = True
                continue
            value = table.get(text)
            if value is not None and specifiers(value) == specifiers(text):
                resolved.append(value)
                if literal:
                    matched.append(text)
            else:
                resolved.append(text)
        # Items that reference a string are already covered by that string's own overlay entry.
        if not matched:
            continue
        if blocked:
            plan.skipped.append(('string-array', name, 'item that cannot be inlined'))
            continue
        plan.arrays[name] = resolved
        plan.matched_keys.update(matched)
    plan.unmatched_keys = sorted(key for key in table if key not in plan.matched_keys)
    return plan


def overlay_package(target, language):
    return '%s.%s.%s' % (OVERLAY_PREFIX, language, target.rsplit('.', 1)[-1].lower())


def values_xml(plan):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- Generated by android/tools/build_text_overlays.py from hooks/localization/%s.json. -->' % plan.language,
             '<resources>']
    for name, (value, formatted) in sorted(plan.strings.items()):
        attribute = ' formatted="%s"' % formatted if formatted in ('true', 'false') else ''
        lines.append('    <string name="%s"%s>%s</string>' % (name, attribute, encode_resource_text(value)))
    for name, items in sorted(plan.arrays.items()):
        lines.append('    <string-array name="%s">' % name)
        lines.extend('        <item>%s</item>' % encode_resource_text(item) for item in items)
        lines.append('    </string-array>')
    lines.append('</resources>')
    return '\n'.join(lines) + '\n'


def manifest_xml(target, language, fingerprint=None):
    pinned = ''
    if fingerprint:
        # The package manager ignores the overlay on any other build, so a firmware update cannot pair it with an
        # app whose resource names may mean something else.
        pinned = ('\n        android:requiredSystemPropertyName="ro.build.fingerprint"'
                  '\n        android:requiredSystemPropertyValue="%s"' % xml_escape(fingerprint, {'"': '&quot;'}))
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    package="%s">\n'
            '    <application android:hasCode="false" />\n'
            '    <!-- Not static: the owner enables and disables it with "cmd overlay" without a restart. -->\n'
            '    <overlay\n'
            '        android:targetPackage="%s"\n'
            '        android:isStatic="false"\n'
            '        android:priority="1"%s />\n'
            '</manifest>\n') % (overlay_package(target, language), target, pinned)


def write_sources(plan, directory, fingerprint=None):
    directory = Path(directory)
    if directory.exists():
        shutil.rmtree(directory)
    values = directory / 'res' / ('values-' + plan.language)
    values.mkdir(parents=True)
    (values / 'strings.xml').write_text(values_xml(plan), encoding='utf-8', newline='\n')
    (directory / 'AndroidManifest.xml').write_text(manifest_xml(plan.target, plan.language, fingerprint),
                                                   encoding='utf-8', newline='\n')
    return directory


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class Toolchain:
    def __init__(self, sdk, build_tools, platform):
        tools = Path(sdk) / 'build-tools' / build_tools
        suffix = '.exe' if os.name == 'nt' else ''
        script = '.bat' if os.name == 'nt' else ''
        self.aapt2 = tools / ('aapt2' + suffix)
        self.zipalign = tools / ('zipalign' + suffix)
        self.apksigner = tools / ('apksigner' + script)
        self.android_jar = Path(sdk) / 'platforms' / platform / 'android.jar'
        for path in (self.aapt2, self.zipalign, self.apksigner, self.android_jar):
            if not path.is_file():
                raise SystemExit('Missing ' + str(path))

    @staticmethod
    def run(*command):
        result = subprocess.run([str(part) for part in command], capture_output=True, text=True)
        if result.returncode != 0:
            raise SystemExit('%s failed:\n%s%s' % (Path(str(command[0])).name, result.stdout, result.stderr))
        return result.stdout

    def build(self, sources, apk, version_code, version_name, signer):
        sources = Path(sources)
        with tempfile.TemporaryDirectory() as work:
            work = Path(work)
            self.run(self.aapt2, 'compile', '--dir', sources / 'res', '-o', work / 'compiled.zip')
            # An overlay has only values-<language>; without --no-resource-removal aapt2 drops every string that
            # lacks a default. resources.arsc stays uncompressed and aligned, as Android 11 requires for target SDK 30.
            self.run(self.aapt2, 'link', '--manifest', sources / 'AndroidManifest.xml', '-I', self.android_jar,
                     '--min-sdk-version', '28', '--target-sdk-version', '30', '--version-code', str(version_code),
                     '--version-name', version_name, '--no-resource-removal', '--no-compress',
                     '-o', work / 'linked.apk', work / 'compiled.zip')
            self.run(self.zipalign, '-p', '-f', '4', work / 'linked.apk', work / 'aligned.apk')
            self.run(self.apksigner, 'sign', '--ks', signer['keystore'], '--ks-key-alias', signer['alias'],
                     '--ks-pass', signer['store_pass'], '--key-pass', signer['key_pass'], '--v4-signing-enabled', 'false',
                     '--out', apk, work / 'aligned.apk')
        certificate = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})',
                                self.run(self.apksigner, 'verify', '--print-certs', apk))
        return certificate.group(1) if certificate else ''


def signer_from_environment(environment=os.environ):
    if all(environment.get(name) for name in RELEASE_SIGNER):
        # Passwords stay in the environment; apksigner reads them from there.
        return {'kind': 'release', 'keystore': environment['OPEN_VOYAH_RELEASE_KEYSTORE'],
                'alias': environment['OPEN_VOYAH_RELEASE_KEY_ALIAS'],
                'store_pass': 'env:OPEN_VOYAH_RELEASE_STORE_PASSWORD',
                'key_pass': 'env:OPEN_VOYAH_RELEASE_KEY_PASSWORD'}
    debug = Path.home() / '.android' / 'debug.keystore'
    if not debug.is_file():
        raise SystemExit('Set the OPEN_VOYAH_RELEASE_* variables or create the Android debug keystore.')
    return {'kind': 'debug', 'keystore': str(debug), 'alias': 'androiddebugkey',
            'store_pass': 'pass:android', 'key_pass': 'pass:android'}


def load_dictionary(language, path=None):
    path = Path(path) if path else DICTIONARIES / (language + '.json')
    document = json.loads(path.read_text(encoding='utf-8'))
    if document.get('schema') != 1 or not isinstance(document.get('packages'), dict):
        raise SystemExit('Unexpected dictionary format: ' + str(path))
    return document['packages'], sha256(path)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('apktool_root', type=Path,
                        help='Folder with an apktool-decoded copy of each target APK (<root>/<package>/res)')
    parser.add_argument('--package', action='append', dest='packages',
                        help='Target package (repeatable); default: %s' % ', '.join(DEFAULT_PACKAGES))
    parser.add_argument('--language', default='ru', help='Dictionary and values-<language> directory (default ru)')
    parser.add_argument('--dictionary', type=Path,
                        help='Schema 1 dictionary to use instead of hooks/localization/<language>.json')
    parser.add_argument('--region', help='Also resolve values-<language>-r<REGION> (for example RU)')
    parser.add_argument('--target-apks', type=Path,
                        help='Folder with <package>.apk copies of the targets; their SHA-256 goes into overlays.json')
    parser.add_argument('--require-fingerprint', help='Pin the overlays to this ro.build.fingerprint')
    parser.add_argument('--out', type=Path, default=ROOT / 'build/text-overlays', help='Output folder')
    parser.add_argument('--sources-only', action='store_true', help='Write the resource sources and the report only')
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
                        or str(Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk'))
    parser.add_argument('--build-tools', default='35.0.0')
    parser.add_argument('--platform', default='android-30')
    parser.add_argument('--version-code', type=int,
                        default=int(datetime.now(timezone.utc).strftime('%y%m%d%H')),
                        help='Overlay versionCode; raise it with every rebuild that goes to a head unit')
    args = parser.parse_args(argv)
    if not LANGUAGE.match(args.language) or (args.region and not REGION.match(args.region)):
        raise SystemExit('Use a language such as ru and a region such as RU.')
    if args.require_fingerprint and not re.fullmatch(r'[A-Za-z0-9_.:/+,-]+', args.require_fingerprint):
        raise SystemExit('Unexpected fingerprint.')
    packages, dictionary_sha = load_dictionary(args.language, args.dictionary)
    targets = args.packages or list(DEFAULT_PACKAGES)
    toolchain = None if args.sources_only else Toolchain(args.sdk, args.build_tools, args.platform)
    signer = None if args.sources_only else signer_from_environment()
    out = args.out / args.language
    out.mkdir(parents=True, exist_ok=True)
    report = {'schema': 1, 'language': args.language, 'dictionary_sha256': dictionary_sha,
              'fingerprint': args.require_fingerprint or '', 'version_code': args.version_code,
              'signer': signer['kind'] if signer else '', 'overlays': []}
    for target in targets:
        if target not in packages:
            raise SystemExit('The %s dictionary has no table for %s.' % (args.language, target))
        res = args.apktool_root / target / 'res'
        if not res.is_dir():
            raise SystemExit('Missing decoded resources: ' + str(res))
        plan = plan_overlay(target, packages[target], read_resources(res), args.language, args.region)
        package = overlay_package(target, args.language)
        sources = write_sources(plan, out / 'src' / package, args.require_fingerprint)
        entry = {'package': package, 'target': target, 'file': package + '.apk', 'strings': len(plan.strings),
                 'arrays': len(plan.arrays), 'dictionary_entries': len(packages[target]),
                 'matched_entries': len(plan.matched_keys), 'unmatched_entries': plan.unmatched_keys,
                 'skipped': [{'type': kind, 'name': name, 'reason': reason} for kind, name, reason in plan.skipped],
                 'plurals_not_translated': plan.plurals}
        if args.target_apks:
            apk = args.target_apks / (target + '.apk')
            entry['target_apk_sha256'] = sha256(apk) if apk.is_file() else ''
        if toolchain:
            apk = out / entry['file']
            entry['signer_sha256'] = toolchain.build(sources, apk, args.version_code,
                                                     '%s-%s' % (args.language, dictionary_sha[:12]), signer)
            entry['sha256'] = sha256(apk)
            entry['size'] = apk.stat().st_size
        report['overlays'].append(entry)
        print('%s -> %s: %d strings, %d arrays, %d of %d dictionary entries matched, %d skipped' % (
            target, package, len(plan.strings), len(plan.arrays), len(plan.matched_keys),
            len(packages[target]), len(plan.skipped)))
    (out / 'overlays.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n',
                                       encoding='utf-8', newline='\n')
    print('Report: ' + str(out / 'overlays.json'))
    return report


if __name__ == '__main__':
    main()
