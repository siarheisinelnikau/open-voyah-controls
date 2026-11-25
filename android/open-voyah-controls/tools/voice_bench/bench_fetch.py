"""Downloads the engines, models and test voices of the voice benchmark into a cache outside the repository.

Nothing here is bundled with the app. Every archive is unpacked into its own empty directory.
"""
from __future__ import annotations

import shutil
import subprocess
import tarfile
import urllib.request
import zipfile
from pathlib import Path

GITHUB_SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
HF_WHISPER = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
WHISPER_BUILD = "b5130"  # whisper.cpp 1.9.4 CPU build for Windows x64

# name -> (url, kind, licence, purpose). kind: file | zip | tar
ASSETS: dict[str, tuple[str, str, str, str]] = {
    # whisper.cpp runtime and models (MIT)
    "whisper-bin": (f"https://github.com/ggml-org/whisper.cpp/releases/download/{WHISPER_BUILD}/whisper-bin-x64.zip",
                    "zip", "MIT", "engine"),
    "ggml-tiny.bin": (f"{HF_WHISPER}/ggml-tiny.bin", "file", "MIT", "engine"),
    "ggml-tiny-q8_0.bin": (f"{HF_WHISPER}/ggml-tiny-q8_0.bin", "file", "MIT", "engine"),
    "ggml-tiny-q5_1.bin": (f"{HF_WHISPER}/ggml-tiny-q5_1.bin", "file", "MIT", "engine"),
    "ggml-base.bin": (f"{HF_WHISPER}/ggml-base.bin", "file", "MIT", "engine"),
    "ggml-base-q8_0.bin": (f"{HF_WHISPER}/ggml-base-q8_0.bin", "file", "MIT", "engine"),
    "ggml-base-q5_1.bin": (f"{HF_WHISPER}/ggml-base-q5_1.bin", "file", "MIT", "engine"),
    "ggml-tiny.en-q8_0.bin": (f"{HF_WHISPER}/ggml-tiny.en-q8_0.bin", "file", "MIT", "engine-en"),
    "ggml-base.en-q8_0.bin": (f"{HF_WHISPER}/ggml-base.en-q8_0.bin", "file", "MIT", "engine-en"),
    "ggml-silero-v5.1.2.bin": ("https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin",
                               "file", "MIT", "engine"),
    # sherpa-onnx models (Apache-2.0 runtime; model licences in the report)
    "silero_vad.onnx": (f"{GITHUB_SHERPA}/asr-models/silero_vad.onnx", "file", "MIT", "engine"),
    "streaming-small-ru-int8": (
        f"{GITHUB_SHERPA}/asr-models/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16.tar.bz2",
        "tar", "Apache-2.0", "engine"),
    "offline-ru-int8": (f"{GITHUB_SHERPA}/asr-models/sherpa-onnx-zipformer-ru-int8-2025-04-20.tar.bz2",
                        "tar", "Apache-2.0", "engine"),
    "gigaam-v3-ctc": (f"{GITHUB_SHERPA}/asr-models/sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16.tar.bz2",
                      "tar", "MIT", "engine"),
    "gigaam-v3-rnnt": (
        f"{GITHUB_SHERPA}/asr-models/sherpa-onnx-nemo-transducer-giga-am-v3-russian-2025-12-16.tar.bz2",
        "tar", "MIT", "engine"),
    "streaming-en-20M": (
        f"{GITHUB_SHERPA}/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2",
        "tar", "Apache-2.0", "engine-en"),
    "kws-en-3.3M": (f"{GITHUB_SHERPA}/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2",
                    "tar", "Apache-2.0", "engine-en"),
    # Real Russian speech used only as non-command input.
    "russian_test_short.wav": (f"{GITHUB_SHERPA}/asr-models/russian_test_short_from_t_one.wav", "file", "Apache-2.0",
                               "data"),
    # Piper voices for synthetic test phrases (test data only, never shipped)
    **{f"tts-ru-{voice}": (f"{GITHUB_SHERPA}/tts-models/vits-piper-ru_RU-{voice}-medium.tar.bz2", "tar",
                           "see voice MODEL_CARD", "tts")
       for voice in ("irina", "denis", "dmitri", "ruslan")},
    **{f"tts-en-{voice}": (f"{GITHUB_SHERPA}/tts-models/vits-piper-en_US-{voice}-medium.tar.bz2", "tar",
                           "see voice MODEL_CARD", "tts")
       for voice in ("amy", "ryan", "lessac", "joe")},
}


def download(url: str, part: Path) -> None:
    """curl resumes stalled transfers (Hugging Face sometimes stops sending); urllib is the fallback."""
    curl = shutil.which("curl")
    if curl:
        for _attempt in range(20):
            done = subprocess.run([curl, "-sSL", "--fail", "-C", "-", "--speed-limit", "20000", "--speed-time", "20",
                                   "--retry", "3", "-A", "voice-bench", "-o", str(part), url]).returncode == 0
            if done:
                return
        raise SystemExit(f"Download failed: {url}")
    request = urllib.request.Request(url, headers={"User-Agent": "voice-bench"})
    with urllib.request.urlopen(request, timeout=120) as response, part.open("wb") as output:
        shutil.copyfileobj(response, output, 1 << 20)


def fetch(cache: Path, names: list[str] | None = None, purposes: set[str] | None = None) -> None:
    cache.mkdir(parents=True, exist_ok=True)
    for name, (url, kind, _licence, purpose) in ASSETS.items():
        if names and name not in names:
            continue
        if purposes and purpose not in purposes:
            continue
        target = cache / name
        if target.exists():
            continue
        part = cache / (name + ".part")
        print(f"fetch {name} <- {url}", flush=True)
        download(url, part)
        if kind == "file":
            part.replace(target)
            continue
        staging = cache / (name + ".unpack")
        if staging.exists():
            shutil.rmtree(staging)
        staging.mkdir()
        if kind == "zip":
            with zipfile.ZipFile(part) as archive:
                for item in archive.infolist():
                    destination = (staging / item.filename).resolve()
                    if not destination.is_relative_to(staging.resolve()):
                        raise SystemExit(f"Unsafe path in {name}: {item.filename}")
                archive.extractall(staging)
        else:
            with tarfile.open(part, "r:*") as archive:
                archive.extractall(staging, filter="data")
        entries = list(staging.iterdir())
        root = entries[0] if len(entries) == 1 and entries[0].is_dir() else staging
        root.replace(target) if root != staging else staging.replace(target)
        if staging.exists():
            shutil.rmtree(staging)
        part.unlink()
