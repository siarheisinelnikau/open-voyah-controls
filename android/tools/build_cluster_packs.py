"""Build local-only cluster packs from verified firmware files."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / 'app/src/main/assets/cluster/profiles.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('assets', type=Path, help='Directory containing the files listed in the cluster profile catalog')
    args = parser.parse_args()
    catalog = json.loads(CATALOG.read_text(encoding='utf-8'))
    results = []
    for profile in catalog:
        contents = {}
        for entry in profile['files']:
            data = (args.assets / entry['source']).read_bytes()
            if len(data) != entry['size'] or hashlib.sha256(data).hexdigest() != entry['sha256']:
                raise ValueError('Unexpected source: ' + entry['source'])
            contents[entry['name']] = data
        path = ROOT / 'dist' / ('cluster-ru-' + profile['firmware'] + '.zip')
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name, data in {'profile.json': json.dumps({'schema': 1, 'firmware': profile['firmware'], 'language': 'ru'}).encode(), **contents}.items():
                info = zipfile.ZipInfo(name, (2026, 9, 14, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data)
        results.append({'file': path.name, 'size': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'firmware': profile['firmware']})
    (ROOT / 'dist/CLUSTER_PACKS.json').write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
