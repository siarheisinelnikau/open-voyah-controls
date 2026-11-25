# Voice engine benchmark

An offline comparison of speech engines for the voice command catalog. It is a desktop tool: nothing here is part of
the Gradle build or the APK, and it never talks to the head unit.

Engines:

- **Vosk** `vosk-model-small-ru-0.22` / `vosk-model-small-en-us-0.15`, exactly as `OfflineVoiceInput` uses them:
  the app's grammar (catalog phrases, built-in climate and volume phrases, `[unk]`), 0.1 s chunks, the endpoint
  result, and a 0.65 confidence floor on every word. The grammar is dumped by `GrammarDump.java`, which compiles the
  app's own `VoiceCatalog`, `ClimatePhrases` and `VolumePhrases` classes.
- **whisper.cpp** (`whisper-cli`) with tiny and base, f16 and quantized: free transcription matched to the catalog
  (exact and fuzzy), and decoding constrained by a GBNF grammar generated from the catalog. Silero VAD trims the
  utterance and a reduced `audio_ctx` (384) is used; the `-novad` configurations feed whole clips and show
  hallucinations on silence and noise. Temperature fallback stays on: without it tiny loops on short clips.
- **sherpa-onnx**: the streaming Russian zipformer from the Vosk team (int8, 25 MB) with greedy search, with catalog
  hotwords, and as a keyword spotter over all catalog phrases; GigaAM v3 (CTC and RNN-T) and the offline Russian
  zipformer on the VAD-trimmed utterance.

## Setup

Python 3.11+ and a JDK 17+ (`JAVA_HOME`).

```
pip install vosk sherpa-onnx soundfile scipy psutil rapidfuzz sentencepiece piper-tts
python -I voice_bench.py fetch
```

`fetch` puts engines, models and TTS voices (about 2 GB) into `%LOCALAPPDATA%\open-voyah-voice-bench` (`--cache`
changes it). The Vosk models are unpacked there from `android/dist/vosk-model-small-*.zip`, the same archives the app
bundles. whisper-cli is the Windows x64 CPU build; on Linux or macOS, point `bench_engines.WhisperCli` at a local
`whisper-cli`.

## Running

```
python -I voice_bench.py vocab  --language ru                  # catalog words missing from the Vosk model
python -I voice_bench.py synth  --language ru --set <folder>   # synthetic set (see below)
python -I voice_bench.py list   --language ru                  # engine configurations
python -I voice_bench.py run    --language ru --set <folder> [--engines vosk-small-ru,whisper-base-q8_0-gbnf]
python -I voice_bench.py report --language ru --set <folder>
```

Each engine runs in its own process, so its RAM is measured alone. Results are appended to
`<folder>/results-<language>.jsonl`; a later run of the same engine replaces its earlier rows in the report. Results
from the synthetic Russian set are in [RESULTS.md](RESULTS.md).

Latency is the time from the true end of the phrase to the result: the engine's endpoint (Vosk and sherpa-onnx
streaming: 0.6 s of trailing silence, as the app configures Vosk) or the VAD's end of speech (0.5 s of silence) for
the non-streaming engines, plus the CPU time spent after that point.

## The synthetic set

`synth` speaks every command's first phrase and one alternative with four Piper voices and the Windows SAPI voice,
once clean and once with cabin acoustics and synthetic cabin noise (road rumble, engine orders, HVAC fan) at 0–12 dB
SNR. It adds non-command TTS speech from `phrases/non_command_<language>.txt` (general talk and near misses that share
words with commands), a few human speech samples shipped with the models, silence and noise-only clips.

TTS is much cleaner and more regular than a driver in a moving car, so treat these numbers as a ranking, not as the
accuracy to expect. The decision needs in-car recordings.

## In-car recordings

Record in the cabin with the engine (or the range extender) running and the climate fan on its usual level:

1. Put a phone near the roof microphone above the rear-view mirror, or record from the driver's seat at the usual
   speaking distance. Use a recorder app that saves WAV (16 kHz or 44.1/48 kHz, mono or stereo; `read_wav`
   converts). FLAC, OGG and MP3 also work; M4A does not.
2. One file per utterance. Start recording, wait about half a second, say the phrase as you would to the car, wait
   about a second, stop. Leading and trailing silence matter: the endpoint and VAD latencies are measured from it.
3. Record a few dozen catalog phrases (any spelling from the catalog), ideally two or three speakers, some while
   driving. Then about twenty non-command utterances (conversation, near misses such as "открой окно пожалуйста
   полностью", a few seconds of radio or music), and a few clips of just cabin noise.
4. Name the files freely and write `labels.jsonl` next to them, one JSON object per line:

```
{"file": "001.wav", "kind": "command", "expected": "OpenSunroof", "text": "открой люк", "condition": "cabin"}
{"file": "002.wav", "kind": "command", "expected": "SetVolume:50", "text": "громкость пятьдесят процентов", "condition": "cabin"}
{"file": "050.wav", "kind": "speech", "expected": "none", "text": "давай заедем в магазин", "condition": "cabin", "near_miss": false}
{"file": "070.wav", "kind": "noise", "expected": "none", "text": "", "condition": "cabin"}
```

`expected` is the command ID from the catalog TSV; volume and climate setpoints use `ID:value`. `kind` is `command`,
`speech`, `silence` or `noise`. Without `speech_end`, latency is measured from the end of speech found by the VAD.

A spoken phrase alone is enough: a short `text` → label helper is `bench_catalog.load(language, cache).match_exact`.
