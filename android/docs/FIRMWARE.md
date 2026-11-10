# Firmware, profiles, and buttons

The app identifies the vehicle profile using Android's `Build.PRODUCT`. You can select the CAN command family manually for voice actions. Account changes always validate the head unit's actual product identifier.

| Product | Vehicle |
| --- | --- |
| `f0501h` | NXP |
| `f0506h` | Free |
| `f6802h` | Free Rest |
| `f6805h` | Free Rest BY |
| `f6811` | Free Rest Oversea |
| `f6801f` | Free Norway |
| `f0505h` | Dreamer |
| `f6804h` | Dreamer Rest |
| `f6803f` | Dreamer Rest Mi |
| `f6806` | Dreamer Rest Mi V2 |

## Driver profiles

Free, Free Rest, Free Rest BY, and Dreamer support guest profiles A, B, and C. The app checks the local profile service, backs up the current account and its file metadata and system properties, and saves the current seat positions before switching. The first completed backup stays available across switches. Open **Firmware and OEM profiles** and select **Restore original OEM account** to restore it. Other models show that guest profiles are unavailable.

## Launcher buttons

| Display | Launcher resource | Assignment |
| --- | --- | --- |
| 0 | `screen_up_icon_1`, `screen_up_icon_2` | Navigation, music |
| 1 | `screen_up_home_icon`, `screen_up_icon_1`–`screen_up_icon_4` | Passenger buttons 1–5. Position 4 is radio on the inspected layout. |

The supported Qinggan `NavigationBar` loads the icon of the assigned Android activity. The icon refreshes after assignment and launcher theme changes. Clearing the assignment or disabling panel assignments restores the original appearance. A tap opens the assigned activity on the button's display. On the branch that reports the full foreground component, the assigned icon highlights when that activity is active on the same display. A hold, an empty assignment, or a failed launch uses the original action. Center Home, climate, vehicle controls, and the app drawer keep their factory behavior. Launcher button assignments, voice control, and translation recover independently.

After installing an APK update, choose **Connect Frida** again to load the bundled launcher changes. Use **Connection status** to see whether the panel icon and foreground-selection adapters loaded.

### Absolute front climate

The H97 climate adapter verifies the OEM `AppConfig.isH97()` result and reads `AC_POWER_SWITCH` before issuing a confirmed request. It uses `setAirConditionStateEx(AirConditionState, float)` for left and right temperatures and `setAirConditionState(AirConditionState, int)` for the front blower. Absolute temperature requests use whole degrees from 16 to 30. Relative temperature requests on H97 respect the stock range of 15.5–30.5 °C. Fan requests use levels 1 through 8. These paths have been checked against the local stock launcher and vehicle settings APKs and exercised with mocked CAN calls.

## Startup and restore

On a prepared head unit, **Connect Frida** stages the local agents and enables recovery through the Android boot job. The supervisor waits for `sys.boot_completed` and three samples of the same `system_server` PID, then watches both the system process and the target PID. Connection status reports each installed adapter.

An optional init entry is available for Android 9 and 11 head units where firmware already runs `/system/etc/init.logcat.sh`. Use an explicit ADB serial and the full `ro.build.fingerprint`. Start with `-Mode status`. **Connect Frida** stages the worker before you run `-Mode install`. A prepared root ADB shell and writable system partition are required for the change.

```powershell
.\tools\configure-boot.ps1 -Serial <ADB-serial> -Fingerprint <build-fingerprint> -Mode status
```

The tool verifies a persistent backup before installing its entry. It preserves the original script and security context and refuses to overwrite a file that changed since the backup. `-Mode restore` restores the saved script after validating both copies. The Android boot job is available on prepared devices independently of the optional entry.

## Steering and display adapters

- The Android 11 steering adapter uses the voice, custom, and surround-camera events exposed by a compatible Qinggan key manager. It consumes a gesture after a successful launch. An unmapped or failed gesture goes to the original callback. Android 9 uses the Android key path through Accessibility for events delivered to the app.
- The window service uses separate Android 9 and 11 adapters to reserve the left panel on central app launches and apply width and DPI to running apps. The virtual display keeps its separate DPI setting.
- The optional navigation feed observes the local OEM instrument cluster service. The virtual display shows the road names and distance supplied by that service. The media option sends title, artist, album, and playback state to a compatible cluster through the OEM media interface.

### Live window profiles

On a prepared head unit, select **Connect Frida**. **Window service** starts by default on supported Android versions. If it was turned off, enable it in **Firmware and OEM profiles**. In **Installed apps**, use the settings icon or touch and hold a tile to open **App settings**. Set the width, density, and optional left inset, then select **Apply**. A profile saved while its app is closed applies when it next opens on the central display.

- A central launch from Controls or the launcher reserves the left strip automatically, including launches from music and radio buttons and apps without a profile. The agent uses the launcher panel's reported width and the Android stable insets. A manual inset can reserve additional space.
- Width is a percentage of the available area after the left and Android system insets. The window aligns to the right. Width and panel spacing require Android freeform windows and a resizable task. Where Android disallows resizing, the service can still apply DPI to that task and reports the size limit.
- The selected app receives its own task configuration. Android can recreate its activity if the app delegates configuration changes to the system. Display-wide density and other apps keep their own configurations.
- **Restore defaults** returns the app to system DPI and the full available width beside the panel. Disabling the window service restores windows managed by the current agent session.
- Profiles survive Android restarts and are included in app settings backups. Older profiles retain their width, DPI, and minimum inset under the shared panel rule. Passenger displays, pinned tasks, split-screen tasks, and tasks shared by multiple apps are outside the live adjustment scope.

The Android SDK version and product name describe candidate firmware branches. OEM key delivery, the passenger display ID, window service signatures, and cluster updates still require testing on the actual head unit.

## Text translation

Select English or Russian in **Firmware and OEM profiles** and reopen the relevant vehicle screens. The built-in dictionaries cover common labels in the launcher, settings, vehicle controls, and Bluetooth phone. A custom JSON dictionary can contain up to 1,000 entries and 48 KB. The optional `products` list limits the dictionary to specified product identifiers.

```json
{
  "schema": 1,
  "enabled": true,
  "products": ["f0506h"],
  "packages": {
    "com.qinggan.app.launcher": {"设置": "Settings"}
  }
}
```

Use the same settings screen to export a dictionary, restore the previous dictionary, or disable translation. For the separate instrument display, see [Instrument cluster localization](CLUSTER.md). Vehicle behavior remains unverified.
