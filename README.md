# Open Voyah Controls

Open Voyah Controls is an Android app for Voyah head units. It opens directly to installed apps and Controls shortcuts. Assign launcher and hardware buttons, use offline English or Russian voice commands, and access driver seat/mirror memory and translations on reviewed firmware.

## Projects

- [Android](android/README.md) — source, build instructions, and feature guides.
- [Windows installer](installer/README.md) — USB and Wi-Fi setup, APK installation, head unit maintenance, and diagnostics.

## Screenshots

| App grid | Settings |
| --- | --- |
| ![Controls app grid on Android 9](android/docs/screenshots/home.png) | ![Compose settings on Android 9](android/docs/screenshots/settings.png) |

Screenshots are from Android emulators. See [Head unit qualification and recovery](android/docs/PRODUCTION.md) for the device evidence required before enabling a production build.

## Android structure

Normal screens share `MainActivity` with Compose destinations and ViewModels exposing StateFlow. `core` owns portable rules and ports; app data adapters own persistence and Frida, while platform adapters handle Android permissions and external services. The voice popup and first-run/installer handshake retain separate Activity boundaries, and old normal-screen component names remain compatibility aliases. See [Compose routes and motion](android/docs/COMPOSE_UI.md).

[Driver 1/2/3](android/docs/DRIVER_MEMORY.md) recall driver seat and mirror memory. The separate [account-record recovery appendix](android/docs/ACCOUNT_RECOVERY.md) explains OEM account limits.

[MIT license](LICENSE)
