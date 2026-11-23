"""Download the Apache-2.0 Russian or English Vosk model on the build PC, never in the car."""
import hashlib, json, urllib.request, zipfile, argparse
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--language',choices=['ru','en'],default='ru')
lang=parser.parse_args().language
name='vosk-model-small-en-us-0.15' if lang=='en' else 'vosk-model-small-ru-0.22'
URL=f'https://alphacephei.com/vosk/models/{name}.zip'
archive=ROOT/f'dist/{name}.zip'
archive.parent.mkdir(exist_ok=True)
if not archive.exists():
    with urllib.request.urlopen(URL,timeout=60) as response, archive.with_suffix('.part').open('wb') as output:
        while chunk:=response.read(1024*1024): output.write(chunk)
    archive.with_suffix('.part').replace(archive)
digest=hashlib.sha256(archive.read_bytes()).hexdigest()
lock=ROOT/('dist/VOICE_MODEL_EN.json' if lang=='en' else 'dist/VOICE_MODEL.json')
if lock.exists() and json.loads(lock.read_text())['sha256']!=digest: raise SystemExit('Model digest mismatch')
target=ROOT/f'app/src/main/assets/speech-model-{lang}'
with zipfile.ZipFile(archive) as package:
    for item in package.infolist():
        if item.is_dir(): continue
        parts=Path(item.filename).parts
        if parts[0]!=name or '..' in parts: raise SystemExit('Unsafe archive path')
        destination=target.joinpath(*parts[1:]).resolve()
        if not destination.is_relative_to(target.resolve()): raise SystemExit('Unsafe archive path')
        destination.parent.mkdir(parents=True,exist_ok=True)
        destination.write_bytes(package.read(item))
lock.write_text(json.dumps(dict(url=URL,sha256=digest,license='Apache-2.0',bytes=archive.stat().st_size),indent=2)+'\n')
print('Prepared Vosk model:',digest)
