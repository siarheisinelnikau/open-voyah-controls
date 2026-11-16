"""Verify packaged speech assets against the pinned local model archives before building."""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def verify(language):
    name = "vosk-model-small-en-us-0.15" if language == "en" else "vosk-model-small-ru-0.22"
    archive = ROOT / "dist" / (name + ".zip")
    metadata = ROOT / "dist" / ("VOICE_MODEL_EN.json" if language == "en" else "VOICE_MODEL.json")
    assets = ROOT / "app/src/main/assets" / ("speech-model-" + language)
    expected = json.loads(metadata.read_text(encoding="utf-8"))["sha256"]
    with archive.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != expected:
            raise ValueError("The local speech archive does not match its pinned digest")
    files = set()
    with zipfile.ZipFile(archive) as package:
        if sum(item.file_size for item in package.infolist() if not item.is_dir()) > 96 * 1024 * 1024:
            raise ValueError("Speech model exceeds the 128 MiB runtime preparation reserve; review the space policy")
        for item in package.infolist():
            if item.is_dir(): continue
            parts = Path(item.filename).parts
            if parts[0] != name or ".." in parts: raise ValueError("Unexpected speech archive path")
            relative = Path(*parts[1:])
            destination = assets / relative
            if destination.is_symlink() or destination.stat().st_size != item.file_size:
                raise ValueError("Incomplete speech asset: " + str(relative))
            with destination.open("rb") as source, package.open(item) as original:
                if hashlib.file_digest(source, "sha256").digest() != hashlib.file_digest(original, "sha256").digest():
                    raise ValueError("Changed speech asset: " + str(relative))
            files.add(relative.as_posix())
    if files != {path.relative_to(assets).as_posix() for path in assets.rglob("*") if path.is_file()}:
        raise ValueError("The packaged speech model has unexpected or missing files")
    print(f"Verified {language} offline model: {len(files)} files")


if __name__ == "__main__":
    try:
        for language in ("ru", "en"): verify(language)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        raise SystemExit(f"{error}. Run tools/prepare_voice_model.py for both languages.") from None
