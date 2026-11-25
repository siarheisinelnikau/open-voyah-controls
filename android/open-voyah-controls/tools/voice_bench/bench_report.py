"""Summary tables from a results file: accuracy, false commands, hallucinations, latency, size and memory."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from bench_catalog import Catalog, vosk_oov

THRESHOLDS = [round(0.05 * i, 2) for i in range(0, 20)]


def pct(n: int, d: int) -> str:
    return "–" if d == 0 else f"{100 * n / d:.1f}%"


def load_results(path: Path) -> tuple[dict, dict]:
    metas, rows = {}, {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        item = json.loads(line)
        if "meta" in item:
            metas[item["meta"]["engine"]] = item["meta"]
            rows[item["meta"]["engine"]] = {}
        else:
            rows.setdefault(item["engine"], {})[item["file"]] = item
    return metas, rows


def score(labels: dict, set_rows: list[dict], label_of) -> dict:
    """Counts by clip kind for one way of turning an outcome into a label."""
    stats = {k: [0, 0] for k in ("clean", "cabin", "wrong", "speech", "near", "real", "silence", "noise")}
    for row in set_rows:
        outcome = labels.get(row["file"])
        if outcome is None:
            continue
        label = label_of(outcome)
        if row["kind"] == "command":
            key = "cabin" if row.get("condition") == "cabin" else "clean"
            stats[key][1] += 1
            stats[key][0] += label == row["expected"]
            stats["wrong"][1] += 1
            stats["wrong"][0] += label is not None and label != row["expected"]
        else:
            key = row["kind"]
            if key == "speech":
                key = "real" if row.get("condition") == "real" else ("near" if row.get("near_miss") else "speech")
            stats[key][1] += 1
            stats[key][0] += label is not None
    return stats


def latency(labels: dict, set_rows: list[dict], label_of) -> tuple[float | None, float | None]:
    values = []
    for row in set_rows:
        outcome = labels.get(row["file"])
        if (row["kind"] != "command" or outcome is None or label_of(outcome) != row["expected"]
                or outcome.get("endpoint_s") is None or row.get("speech_end") is None):
            continue
        values.append(outcome["endpoint_s"] - row["speech_end"] + outcome.get("after_ms", 0) / 1000)
    if not values:
        return None, None
    return float(np.median(values)), float(np.percentile(values, 90))


def hallucinations(labels: dict, set_rows: list[dict]) -> tuple[int, int]:
    total = hits = 0
    for row in set_rows:
        if row["kind"] in ("silence", "noise") and row["file"] in labels:
            total += 1
            hits += bool(labels[row["file"]].get("text", "").strip())
    return hits, total


def line(name: str, mode: str, stats: dict, lat, hall, meta: dict, cpu: float | None) -> str:
    med, p90 = lat
    latency_text = "–" if med is None else f"{med:.2f} / {p90:.2f}"
    false_speech = (stats["speech"][0] + stats["real"][0], stats["speech"][1] + stats["real"][1])
    return (f"| {name} | {mode} | {pct(*stats['clean'])} | {pct(*stats['cabin'])} | {pct(*stats['wrong'])} | "
            f"{pct(*false_speech)} | {pct(*stats['near'])} | {pct(*stats['silence'])} | {pct(*stats['noise'])} | "
            f"{'–' if hall is None else pct(*hall)} | {latency_text} | "
            f"{'–' if cpu is None else f'{cpu:.0f}'} | {meta.get('model_mb', '–')} | {meta.get('ram_mb', '–')} |")


def report(folder: Path, results: Path, language: str, catalog: Catalog, cache: Path) -> str:
    set_rows = [json.loads(l) for l in (folder / "labels.jsonl").read_text(encoding="utf-8").splitlines() if l]
    set_rows = [r for r in set_rows if r.get("language", language) == language]
    vad_path = folder / "vad.json"
    vad = json.loads(vad_path.read_text(encoding="utf-8")) if vad_path.exists() else {}
    for row in set_rows:  # recordings without a known end of speech: use the VAD's
        if row.get("speech_end") is None and vad.get(row["file"], {}).get("end") is not None:
            row["speech_end"] = vad[row["file"]]["end"]
    metas, rows = load_results(results)
    kinds = {}
    for row in set_rows:
        key = row["kind"] if row["kind"] != "command" else f"command/{row.get('condition')}"
        if row["kind"] == "speech":
            key = "speech/real" if row.get("condition") == "real" else (
                "speech/near-miss" if row.get("near_miss") else "speech/general")
        kinds[key] = kinds.get(key, 0) + 1
    out = [f"Set: {folder} ({len(set_rows)} clips: " + ", ".join(f"{k} {v}" for k, v in sorted(kinds.items())) + ")",
           "", "Columns: command accuracy (clean / cabin noise), wrong command among command clips, false commands on "
           "non-command speech (general TTS + human), on near misses, on silence and on noise-only clips, any text on "
           "silence/noise (hallucination), latency after the end of the phrase in s (median / p90, correct commands), "
           "CPU ms per clip, model MB on disk, RAM MB.", "",
           "| Engine | Mode | Acc clean | Acc cabin | Wrong cmd | False speech | False near-miss | False silence | "
           "False noise | Any text silence/noise | Latency s | CPU ms | Model MB | RAM MB |",
           "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    notes = []
    for name, labels in rows.items():
        meta = metas.get(name, {})
        cpu = float(np.mean([o.get("cpu_ms", 0) for o in labels.values()])) if labels else None
        hall = hallucinations(labels, set_rows)
        exact = lambda o: o.get("label")
        out.append(line(name, "as run", score(labels, set_rows, exact), latency(labels, set_rows, exact), hall, meta,
                        cpu))
        if any(o.get("label_fuzzy") for o in labels.values()):
            fuzzy = lambda o: o.get("label_fuzzy")
            out.append(line(name, "+fuzzy match", score(labels, set_rows, fuzzy), latency(labels, set_rows, fuzzy),
                            hall, meta, cpu))
        if any("first_label" in o.get("extra", {}) for o in labels.values()):
            first = lambda o: o.get("extra", {}).get("first_label")
            out.append(line(name, "first keyword", score(labels, set_rows, first), latency(labels, set_rows, first),
                            hall, meta, cpu))
        if name.startswith("whisper") and "gbnf" in name:
            best = None
            for threshold in THRESHOLDS:
                gated = lambda o, t=threshold: o.get("label") if o.get("extra", {}).get("mean_p", 0) >= t else None
                stats = score(labels, set_rows, gated)
                accuracy = (stats["clean"][0] + stats["cabin"][0]) / max(stats["clean"][1] + stats["cabin"][1], 1)
                false = sum(stats[k][0] for k in ("speech", "near", "real", "silence", "noise")) / max(
                    sum(stats[k][1] for k in ("speech", "near", "real", "silence", "noise")), 1)
                value = accuracy - 2 * false
                if best is None or value > best[0]:
                    best = (value, threshold, gated, stats)
            _, threshold, gated, stats = best
            out.append(line(name, f"mean p ≥ {threshold}", stats, latency(labels, set_rows, gated), hall, meta, cpu))
        if meta.get("timings"):
            t = meta["timings"]
            notes.append(f"- {name}: whisper-cli load {t['load_ms']:.0f} ms, {t['per_utterance_ms']:.0f} ms per "
                         f"utterance, buffers {t.get('model_mb')}")
        if meta.get("grammar_ms") is not None:
            notes.append(f"- {name}: grammar recognizer created in {meta['grammar_ms']:.0f} ms (prepared once and reset "
                         f"per request, as the app does)")
    # Vosk on phrases with and without out-of-vocabulary words
    for name, labels in rows.items():
        if not name.startswith("vosk"):
            continue
        model = cache / ("vosk-model-small-ru-0.22" if language == "ru" else "vosk-model-small-en-us-0.15")
        missing = set(vosk_oov(model, catalog.grammar))
        oov = [r for r in set_rows if r["kind"] == "command" and set(r["text"].replace("ё", "е").split()) & missing]
        inv = [r for r in set_rows if r["kind"] == "command" and r not in oov]
        ok = lambda subset: sum(labels.get(r["file"], {}).get("label") == r["expected"] for r in subset)
        notes.append(f"- {name}: {len(oov)} command clips contain words missing from the model "
                     f"({', '.join(sorted(missing))}); accuracy on them {pct(ok(oov), len(oov))}, on the other "
                     f"{len(inv)} clips {pct(ok(inv), len(inv))}")
    host = next((m.get("host") for m in metas.values() if m.get("host")), "?")
    out += ["", f"Measured on: {host}; 4 threads per engine unless noted.", *notes]
    return "\n".join(out)
