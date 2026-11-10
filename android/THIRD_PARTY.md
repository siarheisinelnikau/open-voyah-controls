# Dependency notices

Open Voyah Controls is MIT-licensed. The following build and runtime dependencies retain their own licenses.

| Component | Version | License |
| --- | --- | --- |
| Frida Java bridge | 7.0.13 | LGPL-2.0 with wxWindows exception 3.1 |
| Kotlin standard library | 2.1.21 | Apache-2.0 |
| Kotlin coroutines | 1.10.2 | Apache-2.0 |
| AndroidX Jetpack Compose and Material 3 | Compose BOM 2025.05.01 | Apache-2.0 |
| AndroidX Activity and Lifecycle | 1.10.1 and 2.9.0 | Apache-2.0 |
| Vosk for Android | 0.3.75 | Apache-2.0 |
| Russian and English Vosk models | 0.22 and 0.15 | Apache-2.0 |
| Java Native Access | 5.18.1 | Apache-2.0 or LGPL-2.1-or-later. This project uses the Apache-2.0 option. |

The pinned Java bridge source package and integrity information are in [package-lock.json](package-lock.json). `npm ci` retrieves the source package used to build the hooks. The standalone injector is a separate runtime download pinned in [FRIDA_RUNTIME.json](dist/FRIDA_RUNTIME.json). The voice model download checksums are in [VOICE_MODEL.json](dist/VOICE_MODEL.json) and [VOICE_MODEL_EN.json](dist/VOICE_MODEL_EN.json).

The APK packages the applicable [license texts](app/src/main/assets/licenses/). Local firmware packages remain separate from the APK and the MIT source license.
