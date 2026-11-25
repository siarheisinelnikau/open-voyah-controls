"""Builds a labelled synthetic test set: catalog phrases and non-command speech from offline TTS voices, clean and
mixed with synthetic cabin noise, plus silence and noise-only clips. It is a stand-in until in-car recordings exist:
TTS speech is cleaner and more regular than a driver's voice, so absolute numbers are optimistic for every engine.
"""
from __future__ import annotations

import json
import os
import random
import subprocess
import tempfile
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy import signal

from bench_audio import SAMPLE_RATE, speech_bounds, write_wav
from bench_catalog import Catalog, normalize

PHRASES = Path(__file__).with_name("phrases")
VOICES = {"ru": ["irina", "denis", "dmitri", "ruslan"], "en": ["amy", "ryan", "lessac", "joe"]}
SAPI = {"ru": ["Microsoft Irina Desktop"], "en": ["Microsoft David Desktop", "Microsoft Zira Desktop"]}
SPEECH_DBFS = -26.0
HISS_DBFS = -62.0


def rms_db(x: np.ndarray) -> float:
    return 20 * np.log10(np.sqrt(np.mean(x.astype(np.float64) ** 2)) + 1e-12)


def colored(n: int, rng: np.random.Generator, exponent: float) -> np.ndarray:
    """Noise with a 1/f^exponent power spectrum (0 white, 1 pink, 2 brown)."""
    spectrum = np.fft.rfft(rng.standard_normal(n))
    freqs = np.fft.rfftfreq(n, 1 / SAMPLE_RATE)
    freqs[0] = freqs[1]
    spectrum /= freqs ** (exponent / 2)
    out = np.fft.irfft(spectrum, n)
    return out / (np.std(out) + 1e-12)


def cabin_noise(n: int, rng: np.random.Generator) -> np.ndarray:
    """Road and tyre rumble, an engine or generator hum with harmonics, and HVAC fan hiss, randomly weighted."""
    t = np.arange(n) / SAMPLE_RATE
    road = colored(n, rng, 2.0)
    road = signal.sosfilt(signal.butter(2, [25, 600], "bandpass", fs=SAMPLE_RATE, output="sos"), road)
    fan = signal.sosfilt(signal.butter(2, [250, 4000], "bandpass", fs=SAMPLE_RATE, output="sos"), colored(n, rng, 1.0))
    f0 = rng.uniform(35, 90)  # 2nd engine order at about 1000-2700 rpm
    wobble = 1 + 0.01 * np.sin(2 * np.pi * rng.uniform(0.1, 0.5) * t)
    engine = sum(np.sin(2 * np.pi * f0 * k * np.cumsum(wobble) / SAMPLE_RATE + rng.uniform(0, 6.3)) / k ** 1.2
                 for k in range(1, 8))
    mix = (rng.uniform(0.6, 1.0) * road / (np.std(road) + 1e-12)
           + rng.uniform(0.0, 0.6) * engine / (np.std(engine) + 1e-12)
           + rng.uniform(0.1, 0.5) * fan / (np.std(fan) + 1e-12))
    return mix / (np.std(mix) + 1e-12)


def cabin_acoustics(x: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """A small reflective room (RT60 about 0.1-0.2 s) and a band-limited microphone."""
    rt60 = rng.uniform(0.08, 0.2)
    length = int(rt60 * SAMPLE_RATE)
    decay = np.exp(-6.9 * np.arange(length) / length)
    ir = rng.standard_normal(length) * decay * 10 ** (-rng.uniform(6, 12) / 20)
    ir[0] = 1.0
    wet = signal.fftconvolve(x, ir)[: len(x)]
    return signal.sosfilt(signal.butter(2, [120, 7000], "bandpass", fs=SAMPLE_RATE, output="sos"), wet)


class PiperVoices:
    """Piper voices through the piper-tts package (sherpa-onnx 2.x no longer bundles the espeak-ng front end)."""

    def __init__(self, cache: Path, language: str):
        from piper import PiperVoice
        self.engines = {}
        for voice in VOICES[language]:
            folder = cache / f"tts-{language}-{voice}"
            model = next(folder.glob("*.onnx"))
            self.engines[voice] = PiperVoice.load(str(model), config_path=str(model) + ".json")

    def say(self, voice: str, text: str, speed: float) -> np.ndarray:
        from piper import SynthesisConfig
        engine = self.engines[voice]
        parts = engine.synthesize(text, syn_config=SynthesisConfig(length_scale=1 / speed))
        samples = np.concatenate([part.audio_float_array for part in parts]).astype(np.float32)
        return signal.resample_poly(samples, SAMPLE_RATE, engine.config.sample_rate).astype(np.float32)


def sapi_batch(items: list[tuple[str, str, int, Path]]) -> None:
    """Windows SAPI voices (voice, text, rate -10..10, output path) in one PowerShell process."""
    if not items:
        return
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as listing:
        json.dump([dict(voice=v, text=t, rate=r, path=str(p)) for v, t, r, p in items], listing, ensure_ascii=False)
    script = (
        "Add-Type -AssemblyName System.Speech;"
        "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;"
        "$f=New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000,"
        "[System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,[System.Speech.AudioFormat.AudioChannel]::Mono);"
        f"$items=Get-Content -Raw -Encoding UTF8 '{listing.name}' | ConvertFrom-Json;"
        "foreach($i in $items){$s.SelectVoice($i.voice);$s.Rate=$i.rate;"
        "$s.SetOutputToWaveFile($i.path,$f);$s.Speak($i.text);$s.SetOutputToNull()}")
    subprocess.run(["powershell", "-NoProfile", "-Command", script], check=True)
    os.unlink(listing.name)


def pick_commands(catalog: Catalog, rng: random.Random) -> list[tuple[str, str]]:
    """The first phrase of every command plus one random alternative; a few setpoint phrases per built-in command."""
    by_label: dict[str, list[str]] = {}
    for phrase, label in catalog.labels.items():
        if any(ch.isdigit() for ch in phrase):
            continue
        by_label.setdefault(label, []).append(phrase)
    by_id: dict[str, list[tuple[str, str]]] = {}
    for label, phrases in by_label.items():
        for phrase in phrases:
            by_id.setdefault(catalog.command_id(label), []).append((phrase, label))
    chosen = []
    for command, entries in by_id.items():
        if ":" in entries[0][1]:  # volume and climate setpoints
            sample = rng.sample(entries, min(len(entries), 6 if command == "SetVolume" else 3))
            chosen += sample
            continue
        chosen.append(entries[0])
        if len(entries) > 1:
            chosen.append(rng.choice(entries[1:]))
    return chosen


def non_command_lines(language: str, catalog: Catalog) -> list[tuple[str, bool]]:
    lines = []
    for raw in (PHRASES / f"non_command_{language}.txt").read_text(encoding="utf-8").splitlines():
        if not raw.strip() or raw.startswith("#"):
            continue
        near = raw.startswith("~")
        text = raw.lstrip("~ ").strip()
        if catalog.match_exact(text) is None:
            lines.append((text, near))
    return lines


def build(cache: Path, language: str, catalog: Catalog, out: Path, seed: int = 7) -> Path:
    rng = random.Random(seed)
    nrng = np.random.default_rng(seed)
    out.mkdir(parents=True, exist_ok=True)
    piper = PiperVoices(cache, language)
    voices = [("piper", v) for v in VOICES[language]] + [("sapi", v) for v in SAPI[language]]
    items = []  # (kind, label, text, condition, near_miss)
    for phrase, label in pick_commands(catalog, rng):
        items.append(("command", label, phrase, "clean", False))
        items.append(("command", label, phrase, "cabin", False))
    for text, near in non_command_lines(language, catalog):
        items.append(("speech", "none", text, rng.choice(["clean", "cabin"]), near))
    # Render TTS: Piper in-process, SAPI in one batch.
    raw_dir = out / "_tts"
    raw_dir.mkdir(exist_ok=True)
    rendered, sapi_jobs = [], []
    for index, (kind, label, text, condition, near) in enumerate(items):
        engine, voice = rng.choice(voices)
        speed = rng.uniform(0.9, 1.15)
        path = raw_dir / f"{index:04d}.wav"
        if engine == "piper":
            if not path.exists():
                write_wav(path, piper.say(voice, text, speed))
        elif not path.exists():
            sapi_jobs.append((voice, text, rng.randint(-2, 2), path))
        rendered.append((index, kind, label, text, condition, near, f"{engine}:{voice}", path))
    sapi_batch(sapi_jobs)
    rows = []
    for index, kind, label, text, condition, near, voice, path in rendered:
        speech, rate = sf.read(path, dtype="float32")
        if speech.ndim > 1:
            speech = speech.mean(axis=1)
        if rate != SAMPLE_RATE:
            speech = signal.resample_poly(speech, SAMPLE_RATE, rate).astype(np.float32)
        start, end = speech_bounds(speech)
        speech = speech[max(0, start - 800): end + 800]
        start, end = speech_bounds(speech)
        lead = int(rng.uniform(0.25, 0.6) * SAMPLE_RATE)
        tail = int(1.4 * SAMPLE_RATE)
        clip = np.zeros(lead + len(speech) + tail, dtype=np.float64)
        voiced = speech[start:end].astype(np.float64)
        gain = 10 ** ((SPEECH_DBFS - rms_db(voiced)) / 20)
        speech = speech.astype(np.float64) * gain
        snr = None
        if condition == "cabin":
            speech = cabin_acoustics(speech, nrng)
            snr = rng.uniform(0.0, 12.0)
            noise = cabin_noise(len(clip), nrng) * 10 ** ((SPEECH_DBFS - snr) / 20)
            clip += noise
        clip[lead: lead + len(speech)] += speech
        clip += nrng.standard_normal(len(clip)) * 10 ** (HISS_DBFS / 20)
        name = f"{language}-{kind}-{index:04d}.wav"
        write_wav(out / name, np.clip(clip, -1, 1).astype(np.float32))
        rows.append(dict(file=name, kind=kind, expected=label, language=language, text=text, condition=condition,
                         near_miss=near, voice=voice, snr_db=None if snr is None else round(snr, 1),
                         speech_start=round((lead + start) / SAMPLE_RATE, 3),
                         speech_end=round((lead + end) / SAMPLE_RATE, 3)))
    for index in range(20):
        n = int(3.5 * SAMPLE_RATE)
        clip = nrng.standard_normal(n) * 10 ** (HISS_DBFS / 20)
        name = f"{language}-silence-{index:02d}.wav"
        write_wav(out / name, clip.astype(np.float32))
        rows.append(dict(file=name, kind="silence", expected="none", language=language, text="", condition="clean"))
    for index in range(30):
        n = int(3.5 * SAMPLE_RATE)
        level = SPEECH_DBFS - rng.uniform(0.0, 12.0)
        clip = cabin_noise(n, nrng) * 10 ** (level / 20) + nrng.standard_normal(n) * 10 ** (HISS_DBFS / 20)
        name = f"{language}-noise-{index:02d}.wav"
        write_wav(out / name, np.clip(clip, -1, 1).astype(np.float32))
        rows.append(dict(file=name, kind="noise", expected="none", language=language, text="", condition="cabin",
                         level_dbfs=round(level, 1)))
    # Real (human) speech that is not a command, when available in the cache.
    if language == "ru":
        real = [cache / "russian_test_short.wav"] + sorted(cache.glob("*/test_wavs/*.wav"))
        for index, source in enumerate(p for p in real if p.exists()):
            audio, rate = sf.read(source, dtype="float32")
            if audio.ndim > 1:
                audio = audio.mean(axis=1)
            if rate != SAMPLE_RATE:
                audio = signal.resample_poly(audio, SAMPLE_RATE, rate).astype(np.float32)
            audio = audio[: int(6 * SAMPLE_RATE)]
            name = f"{language}-real-{index:02d}.wav"
            write_wav(out / name, audio)
            rows.append(dict(file=name, kind="speech", expected="none", language=language, text="(human speech)",
                             condition="real", near_miss=False, voice="human"))
    manifest = out / "labels.jsonl"
    with manifest.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    return manifest
