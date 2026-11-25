"""Offline voice command benchmark: Vosk (as the app uses it) against whisper.cpp and sherpa-onnx.

    python -I voice_bench.py fetch                       # engines, models, TTS voices -> cache
    python -I voice_bench.py vocab --language ru         # catalog words missing from the Vosk model
    python -I voice_bench.py synth --language ru --set D # synthetic labelled set (until car recordings exist)
    python -I voice_bench.py run --language ru --set D [--engines a,b]
    python -I voice_bench.py report --set D

A set is a folder of 16 kHz mono WAV files plus labels.jsonl (see README.md). Nothing here ships in the APK.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from dataclasses import asdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

DEFAULT_CACHE = Path(os.environ.get("LOCALAPPDATA", Path.home() / ".cache")) / "open-voyah-voice-bench"


def engines(cache: Path, language: str, catalog) -> dict:
    """Engine configurations by name; created lazily so that a worker loads only its own engine."""
    from bench_engines import SherpaKws, SherpaOffline, SherpaOnline, VoskGrammar, WhisperCli
    cli = cache / "whisper-bin" / "whisper-cli.exe"
    table = {}
    if language == "ru":
        table["vosk-small-ru"] = lambda: VoskGrammar(cache / "vosk-model-small-ru-0.22", catalog, "vosk-small-ru")
        for size in ("tiny", "base"):
            quants = ("", "-q8_0") if size == "tiny" else ("", "-q8_0", "-q5_1")
            for quant in quants:
                tag = quant.lstrip("-") or "f16"
                model = cache / f"ggml-{size}{quant}.bin"
                for mode in ("free", "gbnf"):
                    name = f"whisper-{size}-{tag}-{mode}"
                    table[name] = (lambda m=model, n=name, g=(mode == "gbnf"):
                                   WhisperCli(cli, m, "ru", catalog, n, grammar=g, use_vad=True, audio_ctx=384))
            table[f"whisper-{size}-f16-free-novad"] = (
                lambda s=size: WhisperCli(cli, cache / f"ggml-{s}.bin", "ru", catalog, f"whisper-{s}-f16-free-novad",
                                          grammar=False, use_vad=False, audio_ctx=384))
        table["whisper-base-q8_0-gbnf-beam5"] = lambda: WhisperCli(
            cli, cache / "ggml-base-q8_0.bin", "ru", catalog, "whisper-base-q8_0-gbnf-beam5", grammar=True,
            use_vad=True, audio_ctx=384, beam=5)
        stream = cache / "streaming-small-ru-int8"
        table["sherpa-stream-small-ru-free"] = lambda: SherpaOnline(stream, catalog, "sherpa-stream-small-ru-free",
                                                                    hotwords=False)
        table["sherpa-stream-small-ru-hotwords"] = lambda: SherpaOnline(
            stream, catalog, "sherpa-stream-small-ru-hotwords", hotwords=True)
        table["sherpa-kws-small-ru"] = lambda: SherpaKws(stream, catalog, "sherpa-kws-small-ru")
        table["gigaam-v3-ctc"] = lambda: SherpaOffline(cache / "gigaam-v3-ctc", catalog, "gigaam-v3-ctc", kind="nemo_ctc")
        table["gigaam-v3-rnnt"] = lambda: SherpaOffline(cache / "gigaam-v3-rnnt", catalog, "gigaam-v3-rnnt",
                                                        kind="nemo_transducer")
        table["zipformer-ru-offline-free"] = lambda: SherpaOffline(cache / "offline-ru-int8", catalog,
                                                                   "zipformer-ru-offline-free", kind="transducer")
        table["zipformer-ru-offline-hotwords"] = lambda: SherpaOffline(
            cache / "offline-ru-int8", catalog, "zipformer-ru-offline-hotwords", kind="transducer", hotwords=True)
    else:
        table["vosk-small-en"] = lambda: VoskGrammar(cache / "vosk-model-small-en-us-0.15", catalog, "vosk-small-en")
        for size in ("tiny.en", "base.en"):
            model = cache / f"ggml-{size}-q8_0.bin"
            for mode in ("free", "gbnf"):
                name = f"whisper-{size}-q8_0-{mode}"
                table[name] = (lambda m=model, n=name, g=(mode == "gbnf"):
                               WhisperCli(cli, m, "en", catalog, n, grammar=g, use_vad=True, audio_ctx=384))
        stream = cache / "streaming-en-20M"
        table["sherpa-stream-en-20M-free"] = lambda: SherpaOnline(stream, catalog, "sherpa-stream-en-20M-free",
                                                                  hotwords=False)
        table["sherpa-stream-en-20M-hotwords"] = lambda: SherpaOnline(stream, catalog, "sherpa-stream-en-20M-hotwords",
                                                                      hotwords=True)
        table["sherpa-kws-en-3.3M"] = lambda: SherpaKws(cache / "kws-en-3.3M", catalog, "sherpa-kws-en-3.3M")
    return table


def load_set(folder: Path, language: str) -> list[dict]:
    rows = [json.loads(line) for line in (folder / "labels.jsonl").read_text(encoding="utf-8").splitlines() if line]
    return [row for row in rows if row.get("language", language) == language]


def vad_table(folder: Path, rows: list[dict], cache: Path) -> dict:
    """Silero VAD per clip (cached in the set folder): speech span and when a live detector reports its end."""
    from bench_audio import Vad, read_wav
    path = folder / "vad.json"
    table = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    missing = [row["file"] for row in rows if row["file"] not in table]
    if missing:
        vad = Vad(cache / "silero_vad.onnx")
        for name in missing:
            result = vad.run(read_wav(folder / name))
            table[name] = asdict(result)
        path.write_text(json.dumps(table, indent=0), encoding="utf-8")
    return table


def worker(args) -> None:
    """Runs one engine over a set in this process and appends its rows to the results file."""
    import psutil
    from bench_audio import VadResult, read_wav
    from bench_catalog import load
    from bench_engines import tree_size
    cache = Path(args.cache)
    folder = Path(args.set)
    catalog = load(args.language, cache)
    rows = load_set(folder, args.language)
    vad = {k: VadResult(**v) for k, v in vad_table(folder, rows, cache).items()}
    clips = [(row["file"], read_wav(folder / row["file"])) for row in rows]
    process = psutil.Process()
    baseline = process.memory_info().rss
    engine = engines(cache, args.language, catalog)[args.engine]()
    started = time.perf_counter()
    engine.prepare()
    prepare_s = time.perf_counter() - started
    outcomes = engine.run(clips, vad)
    info = process.memory_info()
    peak = getattr(info, "peak_wset", info.rss)
    meta = dict(engine=engine.name, language=args.language, prepare_s=round(prepare_s, 2),
                model_mb=round(tree_size(engine.files) / 1e6, 1),
                ram_mb=round((getattr(engine, "peak_rss", 0) or max(peak - baseline, 0)) / 1e6, 1),
                ram_kind="process peak" if getattr(engine, "peak_rss", 0) else "peak above baseline",
                timings=getattr(engine, "timings", None), grammar_ms=getattr(engine, "grammar_ms", None),
                threads=getattr(engine, "threads", 1), host=host())
    with open(args.out, "a", encoding="utf-8") as handle:
        handle.write(json.dumps({"meta": meta}, ensure_ascii=False) + "\n")
        for outcome in outcomes:
            handle.write(json.dumps({"engine": engine.name, **asdict(outcome)}, ensure_ascii=False) + "\n")
    print(f"{engine.name}: {len(outcomes)} clips, prepare {prepare_s:.1f}s, ram {meta['ram_mb']} MB", flush=True)


def host() -> str:
    import platform
    try:
        import psutil
        cores = f"{psutil.cpu_count(logical=False)}c/{psutil.cpu_count()}t"
    except ImportError:
        cores = "?"
    return f"{platform.processor() or platform.machine()} {cores} {platform.system()}"


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["fetch", "vocab", "synth", "run", "report", "worker", "list"])
    parser.add_argument("--cache", default=str(DEFAULT_CACHE))
    parser.add_argument("--language", default="ru", choices=["ru", "en"])
    parser.add_argument("--set", help="folder with WAV files and labels.jsonl")
    parser.add_argument("--engines", help="comma-separated engine names (default: all for the language)")
    parser.add_argument("--engine", help=argparse.SUPPRESS)
    parser.add_argument("--out", help="results JSONL (default: <set>/results-<language>.jsonl)")
    parser.add_argument("--only", help="fetch: comma-separated asset names")
    args = parser.parse_args()
    cache = Path(args.cache)
    if args.command == "fetch":
        from bench_fetch import fetch
        fetch(cache, args.only.split(",") if args.only else None)
        return
    from bench_catalog import load, vosk_oov
    if args.command == "worker":
        worker(args)
        return
    catalog = load(args.language, cache)
    if args.command == "list":
        print("\n".join(engines(cache, args.language, catalog)))
    elif args.command == "vocab":
        model = cache / ("vosk-model-small-ru-0.22" if args.language == "ru" else "vosk-model-small-en-us-0.15")
        missing = vosk_oov(model, catalog.grammar)
        print(f"{len(catalog.grammar)} grammar phrases; words missing from {model.name}: {', '.join(missing)}")
        for phrase in catalog.grammar:
            if set(phrase.split()) & set(missing):
                print(f"  {catalog.labels.get(phrase, '?')}\t{phrase}")
    elif args.command == "synth":
        from bench_synth import build
        print(build(cache, args.language, catalog, Path(args.set)))
    elif args.command == "run":
        folder = Path(args.set)
        out = Path(args.out or folder / f"results-{args.language}.jsonl")
        names = args.engines.split(",") if args.engines else list(engines(cache, args.language, catalog))
        vad_table(folder, load_set(folder, args.language), cache)
        for name in names:
            subprocess.run([sys.executable, "-I", __file__, "worker", "--cache", str(cache), "--language",
                            args.language, "--set", str(folder), "--engine", name, "--out", str(out)], check=False)
    elif args.command == "report":
        from bench_report import report
        folder = Path(args.set)
        out = Path(args.out or folder / f"results-{args.language}.jsonl")
        print(report(folder, out, args.language, catalog, cache))


if __name__ == "__main__":
    main()
