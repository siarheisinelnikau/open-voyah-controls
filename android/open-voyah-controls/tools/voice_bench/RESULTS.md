# Results on the synthetic Russian set (2026-11-22)

Synthetic TTS speech, not in-car recordings: use the ranking, not the absolute accuracy. All numbers are from
an Intel i7-10750H (x86-64, 4 threads per engine) under Windows; nothing was measured on arm64 or the head unit.

The clips are not stored in the repository. To rebuild the same set (seed 7) and these results:

```
python -I voice_bench.py fetch
python -I voice_bench.py synth --language ru --set <folder>
python -I voice_bench.py run --language ru --set <folder>
python -I voice_bench.py report --language ru --set <folder>
```

The Windows SAPI voice (Microsoft Irina) is one of the five voices, so a rebuild elsewhere differs slightly.

## Findings

- **Whisper tiny/base does not fit Russian commands.** Free transcription matches a catalog phrase in 15–25% of
  clips (56% at best with fuzzy matching). A GBNF grammar from the catalog raises accuracy to 57–68% but turns 18–55%
  of ordinary speech into commands. Without VAD, base prints subtitle-like text on every silence and noise clip.
  Quantization (q8_0, q5_1) barely changes accuracy. The guided mode of the whisper.cpp `command` example scores only
  the first token, which cannot separate 756 multi-word phrases with shared prefixes, so the grammar mode was tested
  instead.
- **Offline zipformer-ru int8 and GigaAM v3** (free text, fuzzy match to the catalog) reach 92–93% clean and about
  91% with cabin noise, against Vosk's 90.8/89.1%, with **no** false commands on non-command speech or near misses.
  Vosk's grammar absorbs extra words and accepts 25% of the near misses ("мне не нравится эта песня" → "не
  нравится"). The price: they are not streaming (the result follows the VAD's end of speech), the models are 73 MB
  (zipformer) and 225 MB (GigaAM), and RAM is 210–290 MB. Latency after the phrase is lower than Vosk's.
- The streaming small-ru zipformer (29 MB) reaches 80–85% with fuzzy matching. Keyword spotting over the whole
  catalog is poor: keywords that are prefixes of longer commands give 33% wrong commands.
- Short single words ("жарко", "лицо", "стекло", "ноги") cause most errors of the free-text engines.
- Fuzzy matching (rapidfuzz ratio ≥ 85 over the whole utterance) adds almost no false commands, but sometimes
  confuses levels ("уровень один" → "уровень три"): 0.2–1% wrong commands.

## Catalog words missing from the Vosk models

`vosk-model-small-ru-0.22` lacks перезапусти, проветри, контролс, подогревы and рекуперация ("щетки" without ё is
missing too, but the "щётки" spelling is in the grammar). Vosk drops every grammar phrase that contains such a word,
so RestartMusic, RestartNavigator, EnableAllHeating, DisableAllHeating and the three recuperation commands cannot be
recognized at all; ControlsSettings and VentilationCommand keep other phrases. On the 26 affected clips Vosk scored
7.7%, on the other 452 command clips 94.7%. `vosk-model-small-en-us-0.15` lacks "powerhold". Check a catalog change
with `python -I voice_bench.py vocab --language ru`.

## Full set (640 clips)

Whisper latency and CPU in this table were measured with four Whisper runs in parallel; see the next table.

Columns: command accuracy (clean / cabin noise), wrong command among command clips, false commands on non-command speech (general TTS + human), on near misses, on silence and on noise-only clips, any text on silence/noise (hallucination), latency after the end of the phrase in s (median / p90, correct commands), CPU ms per clip, model MB on disk, RAM MB.

| Engine | Mode | Acc clean | Acc cabin | Wrong cmd | False speech | False near-miss | False silence | False noise | Any text silence/noise | Latency s | CPU ms | Model MB | RAM MB |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| vosk-small-ru | as run | 90.8% | 89.1% | 0.6% | 8.3% | 25.0% | 0.0% | 0.0% | 0.0% | 0.98 / 1.46 | 84 | 91.3 | 231.0 |
| sherpa-stream-small-ru-free | as run | 60.3% | 57.3% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 1.21 / 1.48 | 224 | 28.6 | 99.3 |
| sherpa-stream-small-ru-free | +fuzzy match | 85.4% | 80.3% | 0.6% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 1.21 / 1.49 | 224 | 28.6 | 99.3 |
| sherpa-stream-small-ru-hotwords | as run | 69.0% | 66.5% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 1.22 / 1.50 | 328 | 28.6 | 104.0 |
| sherpa-stream-small-ru-hotwords | +fuzzy match | 83.3% | 81.6% | 4.8% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 1.22 / 1.51 | 328 | 28.6 | 104.0 |
| sherpa-kws-small-ru | as run | 52.3% | 52.7% | 32.8% | 6.9% | 52.5% | 0.0% | 0.0% | 0.0% | 0.50 / 0.80 | 250 | 28.6 | 101.3 |
| sherpa-kws-small-ru | first keyword | 52.3% | 52.7% | 32.8% | 6.9% | 52.5% | 0.0% | 0.0% | 0.0% | 0.50 / 0.80 | 250 | 28.6 | 101.3 |
| gigaam-v3-ctc | as run | 76.2% | 75.3% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.79 / 1.09 | 156 | 224.7 | 290.6 |
| gigaam-v3-ctc | +fuzzy match | 93.3% | 91.2% | 0.2% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.79 / 1.08 | 156 | 224.7 | 290.6 |
| gigaam-v3-rnnt | as run | 75.7% | 72.4% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.82 / 1.12 | 189 | 229.3 | 293.0 |
| gigaam-v3-rnnt | +fuzzy match | 93.3% | 91.6% | 0.2% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.82 / 1.11 | 189 | 229.3 | 293.0 |
| zipformer-ru-offline-free | as run | 69.9% | 66.1% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.70 / 0.99 | 71 | 73.2 | 256.7 |
| zipformer-ru-offline-free | +fuzzy match | 92.1% | 87.0% | 0.2% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.70 / 0.99 | 71 | 73.2 | 256.7 |
| zipformer-ru-offline-hotwords | as run | 82.4% | 82.4% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.71 / 1.01 | 89 | 73.2 | 260.0 |
| zipformer-ru-offline-hotwords | +fuzzy match | 92.9% | 91.2% | 1.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.71 / 1.01 | 89 | 73.2 | 260.0 |
| whisper-tiny-q8_0-free | as run | 15.9% | 8.4% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 76.94 / 77.20 | 70121 | 43.5 | 117.9 |
| whisper-tiny-q8_0-free | +fuzzy match | 33.1% | 29.3% | 3.8% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 76.94 / 77.22 | 70121 | 43.5 | 117.9 |
| whisper-tiny-q8_0-gbnf | as run | 56.9% | 44.4% | 5.4% | 20.8% | 25.0% | 0.0% | 0.0% | 0.0% | 1.79 / 2.05 | 1073 | 43.5 | 127.9 |
| whisper-tiny-q8_0-gbnf | mean p ≥ 0.65 | 54.8% | 41.8% | 3.3% | 11.1% | 15.0% | 0.0% | 0.0% | 0.0% | 1.78 / 2.05 | 1073 | 43.5 | 127.9 |
| whisper-tiny-f16-gbnf | as run | 56.5% | 45.6% | 5.4% | 23.6% | 22.5% | 0.0% | 0.0% | 0.0% | 1.38 / 1.64 | 700 | 77.7 | 162.9 |
| whisper-tiny-f16-gbnf | mean p ≥ 0.65 | 54.8% | 43.5% | 3.3% | 11.1% | 17.5% | 0.0% | 0.0% | 0.0% | 1.38 / 1.64 | 700 | 77.7 | 162.9 |
| whisper-base-q8_0-free | as run | 24.7% | 18.8% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 1.64 / 1.90 | 940 | 81.8 | 171.8 |
| whisper-base-q8_0-free | +fuzzy match | 55.6% | 49.4% | 7.1% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 1.64 / 1.92 | 940 | 81.8 | 171.8 |
| whisper-base-q8_0-gbnf | as run | 66.9% | 61.9% | 8.2% | 22.2% | 55.0% | 0.0% | 0.0% | 0.0% | 2.04 / 2.32 | 1307 | 81.8 | 180.3 |
| whisper-base-q8_0-gbnf | mean p ≥ 0.75 | 59.0% | 50.6% | 3.8% | 9.7% | 22.5% | 0.0% | 0.0% | 0.0% | 2.04 / 2.32 | 1307 | 81.8 | 180.3 |
| whisper-tiny-f16-free | as run | 14.6% | 7.5% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 1.42 / 1.68 | 738 | 77.7 | 150.9 |
| whisper-tiny-f16-free | +fuzzy match | 34.3% | 31.4% | 4.2% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 1.42 / 1.70 | 738 | 77.7 | 150.9 |
| whisper-base-f16-gbnf | as run | 67.8% | 61.1% | 8.6% | 18.1% | 52.5% | 0.0% | 0.0% | 0.0% | 2.16 / 2.44 | 1418 | 148.0 | 247.7 |
| whisper-base-f16-gbnf | mean p ≥ 0.75 | 59.0% | 49.0% | 3.8% | 8.3% | 22.5% | 0.0% | 0.0% | 0.0% | 2.16 / 2.44 | 1418 | 148.0 | 247.7 |
| whisper-base-f16-free-novad | as run | 25.1% | 21.8% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 100.0% | 1.49 / 1.77 | 871 | 148.0 | 238.6 |
| whisper-base-f16-free-novad | +fuzzy match | 56.5% | 53.6% | 6.7% | 0.0% | 0.0% | 0.0% | 0.0% | 100.0% | 1.49 / 1.77 | 871 | 148.0 | 238.6 |

Measured on: Intel64 Family 6 Model 165 Stepping 2, GenuineIntel 6c/12t Windows; 4 threads per engine unless noted.
- vosk-small-ru: grammar recognizer created in 22 ms (prepared once and reset per request, as the app does)
- vosk-small-ru: 26 command clips contain words missing from the model (контролс, перезапусти, подогревы, проветри, рекуперация, щетки); accuracy on them 7.7%, on the other 452 clips 94.7%

## Clean timing run (60 command clips, one engine at a time)

| Engine | Mode | Acc clean | Acc cabin | Wrong cmd | False speech | False near-miss | False silence | False noise | Any text silence/noise | Latency s | CPU ms | Model MB | RAM MB |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| vosk-small-ru | as run | 100.0% | 92.9% | 0.0% | – | – | – | – | – | 1.01 / 1.51 | 89 | 91.3 | 199.3 |
| sherpa-stream-small-ru-free | as run | 59.4% | 71.4% | 0.0% | – | – | – | – | – | 1.26 / 1.43 | 182 | 28.6 | 104.3 |
| sherpa-stream-small-ru-hotwords | as run | 78.1% | 75.0% | 0.0% | – | – | – | – | – | 1.26 / 1.43 | 179 | 28.6 | 107.6 |
| gigaam-v3-ctc | as run | 81.2% | 85.7% | 0.0% | – | – | – | – | – | 0.80 / 1.06 | 161 | 224.7 | 285.2 |
| gigaam-v3-rnnt | as run | 78.1% | 89.3% | 0.0% | – | – | – | – | – | 0.83 / 1.12 | 203 | 229.3 | 288.2 |
| zipformer-ru-offline-free | as run | 81.2% | 82.1% | 0.0% | – | – | – | – | – | 0.71 / 1.01 | 88 | 73.2 | 211.3 |
| zipformer-ru-offline-hotwords | as run | 81.2% | 89.3% | 0.0% | – | – | – | – | – | 0.73 / 1.03 | 102 | 73.2 | 214.1 |
| whisper-tiny-q8_0-free | as run | 12.5% | 10.7% | 0.0% | – | – | – | – | – | 0.98 / 1.24 | 361 | 43.5 | 115.8 |
| whisper-tiny-q8_0-gbnf | as run | 65.6% | 42.9% | 6.7% | – | – | – | – | – | 1.29 / 1.55 | 676 | 43.5 | 126.9 |
| whisper-tiny-f16-gbnf | as run | 65.6% | 50.0% | 6.7% | – | – | – | – | – | 2.03 / 2.29 | 1413 | 77.7 | 160.1 |
| whisper-base-q8_0-free | as run | 28.1% | 21.4% | 0.0% | – | – | – | – | – | 1.89 / 2.07 | 1272 | 81.8 | 170.2 |
| whisper-base-q8_0-gbnf | as run | 75.0% | 67.9% | 6.7% | – | – | – | – | – | 7.36 / 7.62 | 6734 | 81.8 | 181.4 |
| whisper-base-q5_1-gbnf | as run | 75.0% | 64.3% | 10.0% | – | – | – | – | – | 1.52 / 1.78 | 898 | 59.7 | 158.6 |
| whisper-base-f16-gbnf | as run | 75.0% | 64.3% | 8.3% | – | – | – | – | – | 1.51 / 1.76 | 887 | 148.0 | 246.9 |

- whisper-tiny-q8_0-free: whisper-cli load 130 ms, 361 ms per utterance, buffers {'weights': 42.96, 'kv_self': 3.15, 'kv_cross': 9.44}
- whisper-tiny-q8_0-gbnf: whisper-cli load 81 ms, 676 ms per utterance, buffers {'weights': 42.96, 'kv_self': 3.15, 'kv_cross': 9.44}
- whisper-tiny-f16-gbnf: whisper-cli load 185 ms, 1413 ms per utterance, buffers {'weights': 77.11, 'kv_self': 3.15, 'kv_cross': 9.44}
- whisper-base-q8_0-free: whisper-cli load 460 ms, 1272 ms per utterance, buffers {'weights': 81.18, 'kv_self': 6.29, 'kv_cross': 18.87}
- whisper-base-q8_0-gbnf: whisper-cli load 170 ms, 6734 ms per utterance, buffers {'weights': 81.18, 'kv_self': 6.29, 'kv_cross': 18.87}
- whisper-base-q5_1-gbnf: whisper-cli load 175 ms, 898 ms per utterance, buffers {'weights': 59.12, 'kv_self': 6.29, 'kv_cross': 18.87}
- whisper-base-f16-gbnf: whisper-cli load 462 ms, 887 ms per utterance, buffers {'weights': 147.37, 'kv_self': 6.29, 'kv_cross': 18.87}
