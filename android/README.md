# Open Voyah Controls for Android

Open Voyah Controls 0.5.0 supports launcher button assignments on the center and passenger displays, Android hardware key bindings, and 129 offline command IDs in Russian and English. The app also supports OEM guest profiles on four observed firmware branches, OEM text localization, and backup and recovery for two instrument cluster firmware profiles.

## Build

Use JDK 17 or 21, Android SDK Platform 35 and Build Tools 35.0.0, Node.js with npm, and Python 3. From this directory, set `JAVA_HOME` and `ANDROID_HOME` to your local installations and run the following commands.

```powershell
python tools/prepare_voice_model.py --language ru
python tools/prepare_voice_model.py --language en
npm ci
.\gradlew.bat :app:assembleDebug :app:lintDebug :core:test
npm test
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. On Linux or macOS, use `./gradlew`. The Gradle wrapper pins Gradle 8.11.1. The hook build bundles the pinned Frida Java bridge, and the voice preparation scripts verify the upstream Vosk archives.

## Button assignments

- For Android hardware keys, enable the accessibility service and capture the desired key. Short and long presses can have separate assignments.
- For the OEM launcher, use a prepared Android head unit with an existing root shell on `127.0.0.1:23`. Run `python tools/prepare_runtime.py` to fetch and verify the pinned ARM64 injector. Run `./tools/install-runtime.ps1 -Serial <ADB-serial>` to copy it to an explicitly selected device.
- In the app, assign buttons and select **Connect Frida**. Short presses run the configured action. Long presses retain the OEM action. Empty bindings and failed launches fall back to the stock handler. The worker reconnects after launcher restarts and can be stopped from the app.

The OEM hook targets the observed Qinggan `NavigationBar.onClick(View)` implementation. Vehicle and firmware compatibility still require hardware testing.

## Documentation

- [Voice commands and settings](docs/VOICE.md)
- [Firmware, profiles, and text localization](docs/FIRMWARE.md)
- [Instrument cluster packages, backup, and recovery](docs/CLUSTER.md)
- [Third-party dependencies and licenses](THIRD_PARTY.md)
