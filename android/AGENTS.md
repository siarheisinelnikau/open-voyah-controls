# Open Voyah Controls

- Independent MIT Android app. Java 17, minSdk 28, Gradle 8.11.1 / AGP 8.9.2.
- `core/`: vocabulary and session/button logic; `app/`: UI, preferences, Android adapters;
  `hooks/`: Frida agents; `OemProfiles.Shell`: injectable OEM account transport.
- Write comments, identifiers and diagnostic code in English. UI: `values` (EN),
  `values-ru` (RU). Voice language is independent; both catalogs must keep identical IDs.
- Use only observed OEM APIs and model branches. No guessed enum ordinals or login hooks.
- Preserve OEM button fallback and independent hook recovery. Never replay an ambiguous physical command.
- Firmware/account writes need a complete backup first. Keep restore available after failure.
- Runtime endpoints: loopback Telnet and the fixed cluster QNX endpoint `172.16.104.41:8000`. No internet services.
- Cluster: exact firmware/hash catalog, complete durable backup before writes, no automatic replay/reboot.
- Vendor cluster packs are local imports outside the MIT sources/APK. Never commit their binaries or credentials.
- Prepare both models: `python tools/prepare_voice_model.py --language ru` and `--language en`.
- Build: `npm ci`, `gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :core:test`, `npm test`.
- Android tests need mic permission; target an explicit isolated emulator serial. Never select a vehicle implicitly.
- Cluster tests: `python tools/test_cluster_transaction.py`; fixture preparation is in `docs/CLUSTER.md`.
- Read `docs/FIRMWARE.md`, `docs/VOICE.md`, and `docs/CLUSTER.md` for behavior and limitations.
- Commit coherent changes; update artifact checksums and validation records after the final build.
