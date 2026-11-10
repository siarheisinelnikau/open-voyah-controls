# Open Voyah Controls

- Independent MIT Android app. Kotlin 2.1.21 and Java 17, minSdk 28, Gradle 8.11.1 / AGP 8.9.2.
- `core/`: vocabulary and session/button logic; `app/`: UI, preferences, Android adapters;
  `hooks/`: Frida agents; `OemProfiles.Shell`: injectable OEM account transport.
- Apply the [head unit development skill](../.agents/skills/head-unit-development/SKILL.md)
  and the `android-clean-mvvm` skill for Android work. Keep the existing Gradle
  modules. Keep domain state, command rules, and ports independent of Android.
  Presentation owns state and effects, data adapters own persistence and Frida, and platform adapters own
  Android intents, receivers, services, media, and audio. Preserve installed Android
  component names through thin entry points.
- Prefer Kotlin for new Android code. Use structured coroutines for background work,
  lifecycle-aware StateFlow collection for screen state, and Compose for app-owned
  UI. Keep native Android boundaries for OS dialogs, display surfaces, and services.
- Write comments, identifiers and diagnostic code in English. UI: `values` (EN),
  `values-ru` (RU). Voice language is independent; both catalogs must keep identical IDs.
- Use only observed OEM APIs and model branches. No guessed enum ordinals or login hooks.
- Preserve OEM button fallback and independent hook recovery. Never replay an ambiguous physical command.
- The voice shortcut opens one foreground capture over the current app. Accept only
  final phrases with confidence checks. Offline actions use exact catalog matches. Apply the verified
  Park gate to seat memory recall and exterior light shutoff. Keep control of the popup on the button.
- Firmware/account writes need a complete backup first. Keep restore available after failure.
- Firmware endpoints are loopback Telnet and the fixed cluster QNX endpoint `172.16.104.41:8000`.
  OpenRouter is an optional voice fallback. Send only the short recognized text and accept only
  the fixed media and assigned-app actions. Keep the API key outside settings archives.
- Cluster: exact firmware/hash catalog, complete durable backup before writes, no automatic replay/reboot.
- Vendor cluster packs are local imports outside the MIT sources/APK. Never commit their binaries or credentials.
- Prepare both models: `python tools/prepare_voice_model.py --language ru` and `--language en`.
- Build: `npm ci`, `gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :core:test`, `npm test`.
- Android tests need mic permission and silent input on an explicit isolated emulator serial. Never select a vehicle implicitly.
- Cluster tests: `python tools/test_cluster_transaction.py`; fixture preparation is in `docs/CLUSTER.md`.
- Read `docs/FIRMWARE.md`, `docs/VOICE.md`, and `docs/CLUSTER.md` for behavior and limitations.
- Commit coherent changes; update artifact checksums and validation records after the final build.
