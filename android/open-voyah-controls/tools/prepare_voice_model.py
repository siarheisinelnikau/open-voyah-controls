"""Stage the pinned Russian Zipformer model and sherpa-onnx Android runtime."""

import argparse
import hashlib
from pathlib import Path
import shutil
import tempfile
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
MODEL_ID = "sherpa-onnx-zipformer-ru-int8-2025-04-20"
MODEL_REPO = f"https://huggingface.co/csukuangfj/{MODEL_ID}/resolve/641de8d322c05b9087ad2927ccda4bda3cccc159/"
SHERPA_RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/"
RUNTIME = (
    SHERPA_RELEASE + "v2.0.2/sherpa-onnx-2.0.2.aar",
    52_321_252,
    "6420cfb86c2ef8462dadd77b2202b7430487bdfb2bc715e9ce2864871209a80e",
)
MODEL = (
    (MODEL_REPO + "encoder.int8.onnx", 70_876_638, "eb6c12fbad810d5bc3e427802e604604c69b5943a91feebc43424dd09d9ec407"),
    (MODEL_REPO + "decoder.onnx", 2_093_080, "dcbe1ffa0211e77ca6d3a80164df13fbda3ec00e47d12b9f449f89572df12136"),
    (MODEL_REPO + "joiner.int8.onnx", 259_417, "93f2e1d12b78d53e7802f1606488c14bb3d764b15fadf5ef6c022f6ba1fa40f7"),
    (MODEL_REPO + "tokens.txt", 6_388, "93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6"),
    (MODEL_REPO + "bpe.model", 246_184, "c7a756aeb3550417d6b2ed3efde9a7aa3eea54787d4eac011e9cce6090c9c64a"),
    (SHERPA_RELEASE + "asr-models/silero_vad.onnx", 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
)


def verified(path, size, sha256):
    if not path.is_file() or path.stat().st_size != size:
        return False
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() == sha256


def obtain(folder, pin, offline):
    url, size, sha256 = pin
    destination = folder / url.rsplit("/", 1)[-1]
    if verified(destination, size, sha256):
        return destination
    if offline:
        raise ValueError(f"No verified copy of {destination.name} at {destination}. Build once with network access.")

    folder.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=folder) as stage:
        partial = Path(stage) / destination.name
        request_url = url + "?download=true" if url.startswith(MODEL_REPO) else url
        request = Request(request_url, headers={"User-Agent": "OpenVoyahControls build"})
        with urlopen(request, timeout=45) as source, partial.open("wb") as output:
            if not source.geturl().startswith("https://"):
                raise ValueError(f"HTTPS is required for {destination.name}")
            count = 0
            while chunk := source.read(1024 * 1024):
                count += len(chunk)
                if count > size:
                    raise ValueError(f"{destination.name} exceeds its pinned size")
                output.write(chunk)
        if not verified(partial, size, sha256):
            raise ValueError(f"{destination.name} does not match its pinned size and SHA-256")
        partial.replace(destination)
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()

    obtain(ROOT / "dist", RUNTIME, args.offline)
    cache = ROOT / "dist" / "voice-model-cache" / MODEL_ID
    sources = [obtain(cache, pin, args.offline) for pin in MODEL]
    target = args.assets / MODEL_ID
    target.mkdir(parents=True, exist_ok=True)
    for source in sources:
        shutil.copyfile(source, target / source.name)
    print(f"Verified and staged {len(sources)} bundled speech model files.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        raise SystemExit(f"Voice assets could not be prepared: {error}") from None
