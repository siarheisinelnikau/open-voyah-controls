"""Speech engines under test. Each engine turns one 16 kHz clip into a command label (or none) plus timing.

Timing model (seconds of audio vs. milliseconds of CPU):
- streaming engines (Vosk, sherpa-onnx online) consume 0.1 s chunks as the app does; the result is available when
  their endpoint fires, plus the CPU time of that last chunk and of producing the result;
- non-streaming engines (Whisper, GigaAM, offline zipformer) wait for the Silero VAD to report the end of speech
  (0.5 s of silence) and then decode the trimmed utterance.
Latency after the phrase = endpoint audio time - true end of speech + CPU time after the endpoint.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from bench_audio import SAMPLE_RATE, Vad, VadResult, read_wav, trim, write_wav
from bench_catalog import Catalog

CHUNK = 1600  # the app reads 1600 samples (0.1 s) per AudioRecord call
VOSK_CONFIDENCE = 0.65
APP_TRAILING_SILENCE = 0.6  # OfflineVoiceInput: setEndpointerDelays(2.5f, 0.6f, 20f)


@dataclass
class Outcome:
    file: str
    text: str = ""  # what the engine heard (free text or grammar phrase)
    label: str | None = None  # accepted command label
    label_fuzzy: str | None = None  # free-text engines: nearest catalog phrase (rapidfuzz ratio >= 85)
    endpoint_s: float | None = None  # audio time at which the engine (or VAD) declared the end of speech
    after_ms: float = 0.0  # CPU time after the endpoint until the result
    cpu_ms: float = 0.0  # CPU time for the whole clip
    extra: dict = field(default_factory=dict)


class Engine:
    name = "engine"
    files: list[Path] = []  # model files on disk (for the size column)

    def prepare(self) -> None: ...

    def run(self, clips: list[tuple[str, np.ndarray]], vad: dict[str, VadResult]) -> list[Outcome]:
        return [self.one(name, audio, vad[name]) for name, audio in clips]

    def one(self, name: str, audio: np.ndarray, vad: VadResult) -> Outcome:
        raise NotImplementedError


def chunks(audio: np.ndarray):
    pcm = (np.clip(audio, -1, 1) * 32767).astype(np.int16)
    for start in range(0, len(pcm), CHUNK):
        yield start + CHUNK, pcm[start: start + CHUNK]


def tree_size(paths: list[Path]) -> int:
    total = 0
    for path in paths:
        if path.is_dir():
            total += sum(p.stat().st_size for p in path.rglob("*") if p.is_file())
        elif path.exists():
            total += path.stat().st_size
    return total


# --------------------------------------------------------------------------------------------------------- Vosk
class VoskGrammar(Engine):
    """OfflineVoiceInput: catalog grammar plus [unk], words on, endpoint result, every word conf >= 0.65."""

    def __init__(self, model_dir: Path, catalog: Catalog, label: str):
        self.name = label
        self.model_dir = model_dir
        self.catalog = catalog
        self.files = [model_dir]

    def prepare(self) -> None:
        import vosk
        vosk.SetLogLevel(-1)
        self.model = vosk.Model(str(self.model_dir))
        started = time.perf_counter()
        grammar = json.dumps(self.catalog.grammar + ["[unk]"], ensure_ascii=False)
        self.recognizer = vosk.KaldiRecognizer(self.model, SAMPLE_RATE, grammar)
        self.recognizer.SetWords(True)
        self.grammar_ms = (time.perf_counter() - started) * 1000

    def one(self, name, audio, vad):
        rec = self.recognizer
        rec.Reset()
        cpu = 0.0
        endpoint = None
        result = None
        last = 0.0
        for position, pcm in chunks(audio):
            started = time.perf_counter()
            final = rec.AcceptWaveform(pcm.tobytes())
            if final:
                result = rec.Result()
            last = (time.perf_counter() - started) * 1000
            cpu += last
            if final:
                endpoint = position / SAMPLE_RATE
                break
        if result is None:  # window ended without an endpoint: the app reports no speech / not understood
            partial = json.loads(rec.PartialResult()).get("partial", "")
            return Outcome(name, text=partial, cpu_ms=cpu, extra={"no_endpoint": True})
        decoded = json.loads(result)
        text = decoded.get("text", "")
        words = decoded.get("result") or []
        confident = bool(words) and all(w.get("conf", 0) >= VOSK_CONFIDENCE for w in words)
        label = None
        if text.strip() and "[unk]" not in text and confident:
            label = self.catalog.match_exact(text)
        # Python Vosk 0.3.45 cannot set the app's endpointer delays; its model default rule is 0.5 s, the app's 0.6 s.
        return Outcome(name, text=text, label=label, endpoint_s=endpoint + (APP_TRAILING_SILENCE - 0.5),
                       after_ms=last, cpu_ms=cpu,
                       extra={"min_conf": min((w.get("conf", 0) for w in words), default=None)})


# ---------------------------------------------------------------------------------------------- whisper.cpp CLI
def gbnf(phrases: list[str]) -> str:
    """A prefix-factored GBNF grammar over the catalog phrases; the first letter may be capitalized."""
    trie: dict = {}
    for phrase in phrases:
        node = trie
        for word in phrase.split():
            node = node.setdefault(word, {})
        node[""] = {}
    rules: list[str] = []
    counter = [0]

    def literal(word: str, first: bool) -> str:
        if first and word[0].isalpha() and word[0].upper() != word[0]:
            return f'[{word[0].upper()}{word[0]}] "{word[1:]}"' if len(word) > 1 else f'[{word[0].upper()}{word[0]}]'
        return f'"{word}"'

    def emit(node: dict, first: bool) -> str:
        name = f"n{counter[0]}"
        counter[0] += 1
        alternatives = []
        for word, child in sorted(node.items()):
            if word == "":
                continue
            piece = literal(word, first)
            continuation = {k: v for k, v in child.items() if k != ""}
            if continuation:
                sub = emit(continuation, False)
                piece += f' (" " {sub})?' if "" in child else f' " " {sub}'
            alternatives.append(piece)
        rules.append(f"{name} ::= " + " | ".join(alternatives))
        return name

    top = emit(trie, True)
    rules.append(f'root ::= " " {top} [.!]?')
    return "\n".join(reversed(rules)) + "\n"


class WhisperCli(Engine):
    """whisper-cli over a batch of clips; per-utterance CPU time is the batch total minus model load, divided by N."""

    def __init__(self, cli: Path, model: Path, language: str, catalog: Catalog, label: str, *, grammar: bool,
                 use_vad: bool, audio_ctx: int, beam: int = 1, threads: int = 4):
        self.name = label
        self.cli, self.model, self.language, self.catalog = cli, model, language, catalog
        self.grammar, self.use_vad, self.audio_ctx, self.beam, self.threads = grammar, use_vad, audio_ctx, beam, threads
        self.files = [model]

    def run(self, clips, vad):
        work = Path(tempfile.mkdtemp(prefix="wcli-"))
        try:
            names, outcomes = [], {}
            for index, (name, audio) in enumerate(clips):
                segment = trim(audio, vad[name]) if self.use_vad else audio
                if segment is None:  # the VAD heard nothing: Whisper is not called
                    outcomes[name] = Outcome(name, endpoint_s=None, extra={"vad_empty": True})
                    continue
                short = f"{index:04d}.wav"
                write_wav(work / short, segment)
                names.append((name, short, vad[name]))
            args = [str(self.cli), "-m", str(self.model), "-l", self.language, "-t", str(self.threads),
                    "-bs", str(self.beam), "-bo", str(max(self.beam, 1)), "-sns", "-ojf", "-nt",
                    "-ac", str(self.audio_ctx)]
            if self.grammar:
                (work / "catalog.gbnf").write_text(gbnf(self.catalog.grammar), encoding="utf-8")
                args += ["--grammar", "catalog.gbnf", "--grammar-rule", "root", "--grammar-penalty", "100"]
            for _name, short, _v in names:
                args += ["-f", short]
            stderr, peak, wall = run_measured(args, work)
            self.peak_rss = peak
            load = float(re.search(r"load time =\s*([\d.]+) ms", stderr).group(1)) if names else 0.0
            total = float(re.search(r"total time =\s*([\d.]+) ms", stderr).group(1)) if names else 0.0
            per = (total - load) / max(len(names), 1)
            self.timings = {"load_ms": load, "total_ms": total, "per_utterance_ms": per, "wall_s": wall,
                            "model_mb": model_mb(stderr)}
            for name, short, v in names:
                data = json.loads((work / (short + ".json")).read_bytes().decode("utf-8", "replace"))
                text = "".join(s.get("text", "") for s in data.get("transcription", []))
                probs = [t["p"] for s in data.get("transcription", []) for t in s.get("tokens", [])
                         if not t.get("text", "").startswith("[_")]
                mean_p = float(np.mean(probs)) if probs else 0.0
                label = self.catalog.match_exact(text)
                fuzzy = None if self.grammar else self.catalog.match_fuzzy(text)
                outcomes[name] = Outcome(name, text=text.strip(), label=label, label_fuzzy=fuzzy,
                                         endpoint_s=v.detected_at, after_ms=per, cpu_ms=per,
                                         extra={"mean_p": mean_p})
            return [outcomes[name] for name, _ in clips]
        finally:
            shutil.rmtree(work, ignore_errors=True)


def model_mb(stderr: str) -> dict:
    sizes = {}
    for key, pattern in {"weights": r"model size\s*=\s*([\d.]+) MB", "kv_self": r"kv self size\s*=\s*([\d.]+) MB",
                         "kv_cross": r"kv cross size\s*=\s*([\d.]+) MB"}.items():
        found = re.search(pattern, stderr)
        if found:
            sizes[key] = float(found.group(1))
    return sizes


def run_measured(args: list[str], cwd: Path) -> tuple[str, int, float]:
    """Runs a process, returns stderr+stdout text, its peak working set in bytes and wall time."""
    import psutil
    started = time.perf_counter()
    process = subprocess.Popen(args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    peak = [0]

    def watch():
        try:
            handle = psutil.Process(process.pid)
            while process.poll() is None:
                info = handle.memory_info()
                peak[0] = max(peak[0], getattr(info, "peak_wset", info.rss))
                time.sleep(0.05)
        except psutil.Error:
            pass

    watcher = threading.Thread(target=watch, daemon=True)
    watcher.start()
    output = process.communicate()[0].decode("utf-8", "replace")
    watcher.join()
    if process.returncode != 0:
        raise RuntimeError(f"{args[0]} failed ({process.returncode}):\n{output[-3000:]}")
    return output, peak[0], time.perf_counter() - started


# -------------------------------------------------------------------------------------------- sherpa-onnx online
def bpe_vocab(model_dir: Path) -> Path | None:
    vocab = model_dir / "bpe.vocab"
    if vocab.exists():
        return vocab
    model = model_dir / "bpe.model"
    if not model.exists():
        return None
    import sentencepiece as spm
    sp = spm.SentencePieceProcessor(model_file=str(model))
    generated = Path(tempfile.gettempdir()) / f"bpe-{model_dir.name}.vocab"
    generated.write_text("".join(f"{sp.id_to_piece(i)}\t{sp.get_score(i)}\n" for i in range(sp.get_piece_size())),
                         encoding="utf-8")
    return generated


def find(model_dir: Path, stem: str) -> str:
    matches = sorted(model_dir.glob(f"{stem}*.onnx"), key=lambda p: ("int8" not in p.name, len(p.name)))
    if not matches:
        raise FileNotFoundError(f"{stem}*.onnx in {model_dir}")
    return str(matches[0])


class SherpaOnline(Engine):
    """Streaming zipformer transducer with its own endpoint (0.6 s trailing silence), free text or catalog hotwords."""

    def __init__(self, model_dir: Path, catalog: Catalog, label: str, *, hotwords: bool, score: float = 1.5,
                 threads: int = 4):
        self.name, self.model_dir, self.catalog = label, model_dir, catalog
        self.hotwords, self.score, self.threads = hotwords, score, threads
        self.files = [Path(find(model_dir, s)) for s in ("encoder", "decoder", "joiner")] + [model_dir / "tokens.txt"]

    def prepare(self):
        import sherpa_onnx
        options = dict(tokens=str(self.model_dir / "tokens.txt"), encoder=find(self.model_dir, "encoder"),
                       decoder=find(self.model_dir, "decoder"), joiner=find(self.model_dir, "joiner"),
                       num_threads=self.threads, sample_rate=SAMPLE_RATE, feature_dim=80,
                       enable_endpoint_detection=True, rule1_min_trailing_silence=2.5,
                       rule2_min_trailing_silence=APP_TRAILING_SILENCE, rule3_min_utterance_length=20,
                       decoding_method="greedy_search")
        if self.hotwords:
            listing = Path(tempfile.gettempdir()) / f"hotwords-{self.catalog.language}.txt"
            listing.write_text("\n".join(p.upper() if self.catalog.language == "en" else p
                                         for p in self.catalog.grammar) + "\n", encoding="utf-8")
            options.update(decoding_method="modified_beam_search", max_active_paths=4, hotwords_file=str(listing),
                           hotwords_score=self.score, modeling_unit="bpe", bpe_vocab=str(bpe_vocab(self.model_dir)))
        self.recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(**options)

    def one(self, name, audio, vad):
        rec = self.recognizer
        stream = rec.create_stream()
        cpu = 0.0
        endpoint = None
        text = ""
        last = 0.0
        tail = np.zeros(int(0.3 * SAMPLE_RATE), dtype=np.float32)  # model look-ahead needs a little more audio
        for position, pcm in chunks(np.concatenate([audio, tail])):
            started = time.perf_counter()
            stream.accept_waveform(SAMPLE_RATE, pcm.astype(np.float32) / 32768)
            while rec.is_ready(stream):
                rec.decode_stream(stream)
            hit = rec.is_endpoint(stream)
            if hit:
                text = rec.get_result(stream)
            last = (time.perf_counter() - started) * 1000
            cpu += last
            if hit and text.strip():
                endpoint = position / SAMPLE_RATE
                break
            if hit:
                rec.reset(stream)
        if endpoint is None:
            stream.input_finished()
            while rec.is_ready(stream):
                rec.decode_stream(stream)
            text = rec.get_result(stream)
        text = text.lower().strip()
        return Outcome(name, text=text, label=self.catalog.match_exact(text), label_fuzzy=self.catalog.match_fuzzy(text),
                       endpoint_s=endpoint, after_ms=last, cpu_ms=cpu, extra={"no_endpoint": endpoint is None})


class SherpaKws(Engine):
    """Keyword spotting with the streaming transducer: every catalog phrase is a keyword. Two policies: the first
    detection at once, or the longest detection once the clip is over (prefix commands need the latter)."""

    def __init__(self, model_dir: Path, catalog: Catalog, label: str, *, threshold: float = 0.25, score: float = 1.0,
                 threads: int = 4):
        self.name, self.model_dir, self.catalog = label, model_dir, catalog
        self.threshold, self.score, self.threads = threshold, score, threads
        self.files = [Path(find(model_dir, s)) for s in ("encoder", "decoder", "joiner")] + [model_dir / "tokens.txt"]

    def prepare(self):
        import sentencepiece as spm
        import sherpa_onnx
        sp = spm.SentencePieceProcessor(model_file=str(self.model_dir / "bpe.model"))
        lines, self.display = [], {}
        for index, phrase in enumerate(self.catalog.grammar):
            text = phrase.upper() if self.catalog.language == "en" else phrase
            pieces = sp.encode(text, out_type=str)
            lines.append(" ".join(pieces) + f" @k{index}")
            self.display[f"k{index}"] = phrase
        listing = Path(tempfile.gettempdir()) / f"keywords-{self.catalog.language}-{self.model_dir.name}.txt"
        listing.write_text("\n".join(lines) + "\n", encoding="utf-8")
        self.spotter = sherpa_onnx.KeywordSpotter(
            tokens=str(self.model_dir / "tokens.txt"), encoder=find(self.model_dir, "encoder"),
            decoder=find(self.model_dir, "decoder"), joiner=find(self.model_dir, "joiner"),
            num_threads=self.threads, keywords_file=str(listing), keywords_score=self.score,
            keywords_threshold=self.threshold, num_trailing_blanks=1, max_active_paths=4)

    def one(self, name, audio, vad):
        spotter = self.spotter
        stream = spotter.create_stream()
        hits = []
        cpu = 0.0
        tail = np.zeros(int(0.5 * SAMPLE_RATE), dtype=np.float32)
        for position, pcm in chunks(np.concatenate([audio, tail])):
            started = time.perf_counter()
            stream.accept_waveform(SAMPLE_RATE, pcm.astype(np.float32) / 32768)
            while spotter.is_ready(stream):
                spotter.decode_stream(stream)
                keyword = spotter.get_result(stream)
                if keyword:
                    hits.append((self.display.get(keyword, keyword), position / SAMPLE_RATE,
                                 (time.perf_counter() - started) * 1000))
                    spotter.reset_stream(stream)
            cpu += (time.perf_counter() - started) * 1000
        if not hits:
            return Outcome(name, cpu_ms=cpu)
        first = hits[0]
        longest = max(hits, key=lambda h: (len(h[0].split()), h[1]))
        return Outcome(name, text=longest[0], label=self.catalog.match_exact(longest[0]),
                       endpoint_s=longest[1], after_ms=longest[2], cpu_ms=cpu,
                       extra={"first": first[0], "first_label": self.catalog.match_exact(first[0]),
                              "first_at": first[1], "hits": [h[0] for h in hits]})


# ------------------------------------------------------------------------------------------- sherpa-onnx offline
class SherpaOffline(Engine):
    """A non-streaming model on the VAD-trimmed utterance (GigaAM CTC/RNNT, offline zipformer)."""

    def __init__(self, model_dir: Path, catalog: Catalog, label: str, *, kind: str, hotwords: bool = False,
                 threads: int = 4):
        self.name, self.model_dir, self.catalog, self.kind, self.hotwords, self.threads = (
            label, model_dir, catalog, kind, hotwords, threads)
        if kind == "nemo_ctc":
            self.files = [Path(find(model_dir, "model")), model_dir / "tokens.txt"]
        else:
            self.files = [Path(find(model_dir, s)) for s in ("encoder", "decoder", "joiner")] + [model_dir / "tokens.txt"]

    def prepare(self):
        import sherpa_onnx
        tokens = str(self.model_dir / "tokens.txt")
        if self.kind == "nemo_ctc":
            self.recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
                model=find(self.model_dir, "model"), tokens=tokens, num_threads=self.threads)
            return
        options = dict(encoder=find(self.model_dir, "encoder"), decoder=find(self.model_dir, "decoder"),
                       joiner=find(self.model_dir, "joiner"), tokens=tokens, num_threads=self.threads,
                       decoding_method="greedy_search")
        if self.kind == "nemo_transducer":
            options["model_type"] = "nemo_transducer"
        if self.hotwords:
            listing = Path(tempfile.gettempdir()) / f"hotwords-{self.catalog.language}.txt"
            listing.write_text("\n".join(self.catalog.grammar) + "\n", encoding="utf-8")
            options.update(decoding_method="modified_beam_search", max_active_paths=4, hotwords_file=str(listing),
                           hotwords_score=1.5, modeling_unit="bpe", bpe_vocab=str(bpe_vocab(self.model_dir)))
        self.recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(**options)

    def one(self, name, audio, vad):
        segment = trim(audio, vad)
        if segment is None:
            return Outcome(name, extra={"vad_empty": True})
        started = time.perf_counter()
        stream = self.recognizer.create_stream()
        stream.accept_waveform(SAMPLE_RATE, segment)
        self.recognizer.decode_stream(stream)
        text = stream.result.text.lower().strip()
        spent = (time.perf_counter() - started) * 1000
        return Outcome(name, text=text, label=self.catalog.match_exact(text), label_fuzzy=self.catalog.match_fuzzy(text),
                       endpoint_s=vad.detected_at, after_ms=spent, cpu_ms=spent)
