"""Audio input and the voice activity detector shared by the non-streaming engines."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy import signal

SAMPLE_RATE = 16_000


def read_wav(path: Path) -> np.ndarray:
    audio, rate = sf.read(path, dtype="float32", always_2d=True)
    audio = audio.mean(axis=1)
    if rate != SAMPLE_RATE:
        audio = signal.resample_poly(audio, SAMPLE_RATE, rate).astype(np.float32)
    return audio


def write_wav(path: Path, audio: np.ndarray) -> None:
    sf.write(path, np.clip(audio, -1, 1), SAMPLE_RATE, subtype="PCM_16")


def speech_bounds(audio: np.ndarray, frame: int = 160) -> tuple[int, int]:
    """First and last sample of frames above 4% of the loudest frame RMS (for clean TTS only)."""
    frames = len(audio) // frame
    if frames == 0:
        return 0, len(audio)
    rms = np.sqrt(np.mean(audio[: frames * frame].reshape(frames, frame).astype(np.float64) ** 2, axis=1))
    active = np.nonzero(rms > rms.max() * 0.04)[0]
    if len(active) == 0:
        return 0, len(audio)
    return int(active[0] * frame), int((active[-1] + 1) * frame)


@dataclass
class VadResult:
    start: float | None  # seconds
    end: float | None
    detected_at: float | None  # audio time at which a live detector would report the end of speech


class Vad:
    """Silero VAD through sherpa-onnx, as a live endpoint: speech ends after `min_silence` seconds of silence."""

    def __init__(self, model: Path, min_silence: float = 0.5, threshold: float = 0.5):
        import sherpa_onnx
        config = sherpa_onnx.VadModelConfig()
        config.silero_vad.model = str(model)
        config.silero_vad.threshold = threshold
        config.silero_vad.min_silence_duration = min_silence
        config.silero_vad.min_speech_duration = 0.2
        config.silero_vad.max_speech_duration = 20
        config.sample_rate = SAMPLE_RATE
        config.num_threads = 1
        self.config = config
        self.window = config.silero_vad.window_size
        self.sherpa = sherpa_onnx

    def run(self, audio: np.ndarray) -> VadResult:
        vad = self.sherpa.VoiceActivityDetector(self.config, buffer_size_in_seconds=30)
        first_start = last_end = detected = None
        position = 0
        while position < len(audio):
            chunk = audio[position: position + self.window]
            position += len(chunk)
            if len(chunk) < self.window:
                chunk = np.pad(chunk, (0, self.window - len(chunk)))
            vad.accept_waveform(chunk)
            while not vad.empty():
                segment = vad.front
                start = segment.start / SAMPLE_RATE
                end = start + len(segment.samples) / SAMPLE_RATE
                if first_start is None:
                    first_start = start
                last_end = end
                detected = position / SAMPLE_RATE
                vad.pop()
        vad.flush()
        while not vad.empty():
            segment = vad.front
            start = segment.start / SAMPLE_RATE
            end = start + len(segment.samples) / SAMPLE_RATE
            if first_start is None:
                first_start = start
            last_end = end
            detected = len(audio) / SAMPLE_RATE  # only found at the end of the clip
            vad.pop()
        return VadResult(first_start, last_end, detected)


def trim(audio: np.ndarray, vad: VadResult, pad: float = 0.2) -> np.ndarray | None:
    if vad.start is None:
        return None
    a = max(0, int((vad.start - pad) * SAMPLE_RATE))
    b = min(len(audio), int((vad.end + pad) * SAMPLE_RATE))
    return audio[a:b]
