# Dependency notices

Open Voyah Controls is licensed under the GNU General Public License v3.0 or later (see [LICENSE](../../LICENSE)). The following build and runtime dependencies retain their own licenses.

| Component | Version | License |
| --- | --- | --- |
| Frida Java bridge | 7.0.13 | LGPL-2.0 with wxWindows exception 3.1 |
| Kotlin standard library | 2.1.21 | Apache-2.0 |
| Kotlin coroutines | 1.10.2 | Apache-2.0 |
| AndroidX Jetpack Compose and Material 3 | Compose BOM 2025.05.01 | Apache-2.0 |
| AndroidX Activity and Lifecycle | 1.10.1 and 2.9.0 | Apache-2.0 |
| Vosk for Android | 0.3.75 | Apache-2.0 |
| [Vosk models](https://alphacephei.com/vosk/models) (optional downloads) | Russian 0.22, English 0.15, Kazakh 0.42 | Apache-2.0 |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.2) | 2.0.2 | Apache-2.0 |
| [Russian Zipformer model](https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20) | 2025-04-20, int8 | Apache-2.0 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | Android runtime from sherpa-onnx 2.0.2 | [MIT](app/src/main/assets/licenses/ONNX-Runtime-MIT.txt) |
| [Silero VAD](https://github.com/snakers4/silero-vad) | Pinned ONNX model from sherpa-onnx | [MIT](app/src/main/assets/licenses/Silero-VAD-MIT.txt) |
| Java Native Access | 5.18.1 | Apache-2.0 or LGPL-2.1-or-later. This project uses the Apache-2.0 option. |
| [Golos Text](https://github.com/google/fonts/tree/main/ofl/golostext) | Google Fonts blob `05ee5ecc570b97818e24e58693662cd8294fdfc9` | [SIL Open Font License 1.1](app/src/main/assets/licenses/Golos-Text-OFL.txt) |

The pinned Java bridge source package and integrity information are in [package-lock.json](package-lock.json). `npm ci` retrieves the source package used to build the hooks. The standalone injector is a separate runtime download pinned in [FRIDA_RUNTIME.json](dist/FRIDA_RUNTIME.json). The bundled speech assets and Vosk downloads have separate pins in [the build preparation script](tools/prepare_voice_model.py) and [the Vosk model catalog](app/src/main/kotlin/org/openvoyah/controls/data/voice/SpeechModelCatalog.kt).

The APK packages the applicable [license texts](app/src/main/assets/licenses/). Local firmware packages remain separate from the APK and the GPL-3.0 source license.
