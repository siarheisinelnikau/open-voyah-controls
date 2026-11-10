# Open Voyah Controls Installer for Windows

The installer connects to a selected Android head unit over USB or Wi-Fi. Use it to install, update, launch, or remove Open Voyah Controls, install a folder of standalone APKs, set up the project's Frida injector, and collect diagnostic logs.

## Set up the installer

1. Install [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) and Android SDK Build-Tools in the same SDK directory. Keep `adb.exe`, `AdbWinApi.dll`, and `AdbWinUsbApi.dll` from the same Platform-Tools installation together. The installer uses `aapt2.exe` in that SDK to check the package name of app and keyboard APKs.
2. Enable USB debugging on the head unit and accept the Android authorization prompt. Connect by USB and select **Refresh**, or enter the head unit's ADB address and select **Connect Wi-Fi**. The default port is 5555. The device must already accept TCP ADB for Wi-Fi connections.
3. Select the head unit from the list. Use **Browse APK** to select a local Open Voyah Controls APK, or check **Releases and logs** for official downloads. Choose **Install or update** to keep existing app data and let Android enforce package signing.

**Install Frida injector** accepts the ARM64 binary prepared by [the Android runtime script](../android/tools/prepare_runtime.py). The installer checks its pinned SHA-256 and tests the binary on the selected unit. The Android app's OEM hooks require the [existing local root transport](../android/README.md#button-assignments).

## Maintain a head unit

The maintenance operations check the product and firmware fingerprint before sending writes. They are limited to products `f0506h` and `f0505h`. A matching product and fingerprint do not certify firmware compatibility. Keep the vehicle parked with stable power while performing maintenance.

- **Reset vehicle settings with backup** uses ADB root and saves the database and available SQLite journals to the PC before moving the originals aside on the device. **Restore vehicle settings backup** moves the originals back on the same head unit.
- **Disable dm-verity** and **Enable dm-verity** use the firmware's ADB commands. Use **Restart head unit** separately when the tool or firmware requires a reboot. Restore stock system files before re-enabling verification.
- **Install signed OEM keyboard update** accepts a local compatible APK for either `com.qinggan.app.qgime` or `com.qinggan.app.qgime.second`. For **Restore stock OEM keyboard**, enter the exact package name. Stock restoration requires package-specific support in Android. OEM packages are supplied separately.
- **Collect logs** saves an on-device diagnostic snapshot as a local ZIP with a summary of unavailable reports. Android's access controls determine which logs are readable. **Open logs folder** shows saved reports.

## Engineering menu

Open **Engineering menu** for yesterday's, today's, and tomorrow's codes relative to the current date in Beijing. Use the date shown on the head unit if its clock differs. Select any code to copy it. This tool works offline without a connected head unit.

For early firmware, **Load local codes** opens up to four case-sensitive codes from a private JSON list in the form `[{"label":"My head unit","code":"EXAMPLE_ONLY"}]`. To include a local list in a build, set `OPEN_VOYAH_LEGACY_CODES_FILE` to its absolute path before running Cargo. Keep this file outside version control.

## Build and test

Use Rust 1.96 and the Windows SDK with the MSVC build tools. From this directory run:

```powershell
cargo build --release --locked
cargo test --release --locked
```

For a standalone release, copy the binary from `target/release/OpenVoyahControlsInstaller.exe` as `OpenVoyahControlsInstaller-v0.1.2.exe`. The **Releases and logs** panel checks the project's stable GitHub releases for versioned `OpenVoyahControlsInstaller-vVERSION.exe` and `OpenVoyahControls-vVERSION.apk` assets. It requires a SHA-256 digest from the release. Startup checks are enabled by default and can be disabled from the same panel.

To make a local portable copy, run `./tools/prepare-portable.ps1 -SdkRoot C:/Android/Sdk -BuildToolsVersion 35.0.0` after the release build. It copies the executable, the ADB DLL pair, and APK verification tool from your installed SDK into `dist`. Keep the `android-sdk` directory next to the executable. The app looks there before looking for an installed SDK. A toolchain selected explicitly in **Releases and logs** takes precedence.

## Screenshots

These client-area captures come from the Windows release build. Installation shows a sample device and APK. The engineering preview uses an example date and masks the early-firmware codes.

![Installation tab showing connection controls and APK actions](docs/screenshots/installation.png)

![Engineering menu with the previous, current, and next access codes](docs/screenshots/engineering-menu.png)

![Maintenance tab showing firmware checks and service operations](docs/screenshots/maintenance.png)

![Releases and logs tab showing the startup update setting and diagnostic actions](docs/screenshots/releases-and-logs.png)
