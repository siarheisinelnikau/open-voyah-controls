"""The app's voice grammar and phrase table, and matching of free transcripts against it."""
from __future__ import annotations

import os
import re
import unicodedata
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[2]
CORE = ANDROID / "core" / "src" / "main"
CATALOGS = {"ru": CORE / "resources" / "voice-catalog.tsv", "en": CORE / "resources" / "voice-catalog-en.tsv"}
CORE_SOURCES = [
    CORE / "java/org/openvoyah/controls/core/VoiceCatalog.java",
    CORE / "java/org/openvoyah/controls/core/voice/SpokenNumbers.java",
    CORE / "java/org/openvoyah/controls/core/voice/ClimatePhrases.java",
    CORE / "java/org/openvoyah/controls/core/voice/VolumePhrases.java",
    CORE / "java/org/openvoyah/controls/core/voice/PercentPhrases.java",
    CORE / "java/org/openvoyah/controls/core/voice/BrightnessPhrases.java",
    CORE / "java/org/openvoyah/controls/core/voice/SpokenValueCommands.java",
]
UNITS = re.compile(r"\b(процентов|процента|процент|градусов|градуса|градус|percent|degrees)\b")
UNIT_ROOT = {"процентов": "процент", "процента": "процент", "градусов": "градус", "градуса": "градус"}


def java_home() -> Path:
    candidates = [os.environ.get("JAVA_HOME", ""), r"C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot"]
    for candidate in candidates:
        if candidate and (Path(candidate) / "bin").exists():
            return Path(candidate)
    raise SystemExit("Set JAVA_HOME to a JDK 17+ (the grammar dump compiles the app's core voice classes)")


def normalize(text: str) -> str:
    """VoiceCatalog.normalize: lower case, punctuation to spaces, single spaces, ё -> е."""
    text = text.lower()
    text = re.sub(r"[.,!?;:]", " ", text)
    return re.sub(r"\s+", " ", text).strip().replace("ё", "е")


def transcript_key(text: str) -> str:
    """Normalization for engine output: also drops other punctuation and unifies unit word forms and %."""
    text = unicodedata.normalize("NFC", text).lower().replace("ё", "е").replace("%", " процент ").replace("°", " градус ")
    text = re.sub(r"[^\w\s]", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return UNITS.sub(lambda m: UNIT_ROOT.get(m.group(1), m.group(1)), text)


@dataclass
class Catalog:
    language: str
    labels: dict[str, str]  # normalized phrase -> label (ID or ID:value)
    grammar: list[str]  # Vosk grammar phrases without [unk], as the app sends them
    groups: dict[str, str] = field(default_factory=dict)  # label -> catalog group
    aliases: dict[str, str] = field(default_factory=dict)  # transcript-only aliases -> label

    def __post_init__(self) -> None:
        self.keys: dict[str, str] = {}
        for phrase, label in list(self.labels.items()) + list(self.aliases.items()):
            self.keys.setdefault(transcript_key(phrase), label)
        self._choices = list(self.keys)

    def match_exact(self, text: str) -> str | None:
        return self.keys.get(transcript_key(text))

    def match_fuzzy(self, text: str, cutoff: float = 85.0) -> str | None:
        from rapidfuzz import fuzz, process
        key = transcript_key(text)
        if not key:
            return None
        if key in self.keys:
            return self.keys[key]
        best = process.extractOne(key, self._choices, scorer=fuzz.ratio, score_cutoff=cutoff)
        return self.keys[best[0]] if best else None

    def command_id(self, label: str) -> str:
        return label.split(":", 1)[0]


def load(language: str, cache: Path) -> Catalog:
    build = cache / "grammar-dump"
    stamp = build / "GrammarDump.class"
    sources = CORE_SOURCES + [Path(__file__).with_name("GrammarDump.java")]
    if not stamp.exists() or any(s.stat().st_mtime > stamp.stat().st_mtime for s in sources):
        build.mkdir(parents=True, exist_ok=True)
        subprocess.run([str(java_home() / "bin" / "javac"), "-encoding", "UTF-8", "-d", str(build),
                        *map(str, sources)], check=True)
    output = subprocess.run([str(java_home() / "bin" / "java"), "-Dfile.encoding=UTF-8", "-cp", str(build),
                             "GrammarDump", language, str(CATALOGS[language])],
                            check=True, capture_output=True).stdout.decode("utf-8")
    labels, grammar, groups = {}, [], {}
    for line in output.splitlines():
        phrase, label, in_grammar, group = line.split("\t")
        labels[phrase] = label
        groups[label] = group
        if in_grammar == "1":
            grammar.append(phrase)
    aliases = {}
    for line in CATALOGS[language].read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if line.startswith("#") or len(parts) != 4:
            continue
        for alias in parts[3].split("|"):
            aliases[normalize(alias)] = parts[0]
    return Catalog(language, labels, sorted(set(grammar)), groups, aliases)


def vosk_oov(model_dir: Path, grammar: list[str]) -> list[str]:
    """Words the Vosk model drops from the grammar (it skips every phrase that contains one)."""
    script = ("import json,sys,vosk;vosk.SetLogLevel(0);"
              "m=vosk.Model(sys.argv[1]);g=json.loads(open(sys.argv[2],encoding='utf-8').read());"
              "vosk.KaldiRecognizer(m,16000,json.dumps(g+['[unk]'],ensure_ascii=False))")
    listing = model_dir.parent / f"grammar-{model_dir.name}.json"
    import json
    listing.write_text(json.dumps(grammar, ensure_ascii=False), encoding="utf-8")
    result = subprocess.run([sys.executable, "-I", "-c", script, str(model_dir), str(listing)],
                            capture_output=True)
    log = result.stderr.decode("utf-8", "replace")
    return sorted(set(re.findall(r"missing in vocabulary: '([^']+)'", log)))
