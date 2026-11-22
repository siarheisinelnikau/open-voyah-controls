# Firmware, profiles, and buttons

The app identifies the candidate vehicle family using Android's `Build.PRODUCT`. Production adapters also require the reviewed build fingerprint, SDK, ABI, and OEM package evidence. Individual voice and profile operations require their own approval. See [Head unit qualification and recovery](PRODUCTION.md).

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

## Firmware coverage

The following is a code-coverage matrix, not a list of qualified vehicles. Every row still needs an exact firmware record and device verification. The shipped production catalogs are empty.

| Family | Candidate paths in code | Current limits |
| --- | --- | --- |
| Free before restyling (`f0506h`) | Standard launcher, Free voice mappings, guest profiles, QG callbacks or separately reviewed CAN terminal / down-up events on Android 9 | CAN requires its own exact APK pin and one qualified event protocol. Down/up capture requires both tap and hold targets. Accessibility sees only delivered Android keys. |
| Free Rest / BY (`f6802h`, `f6805h`) | Standard launcher, Rest voice branches, guest profiles, QG callbacks or separately reviewed CAN terminal / down-up events on Android 11 | CAN event delivery and its native actions need separate qualification. BY is not separately qualified. |
| Dreamer (`f0505h`) | Standard launcher, Dreamer voice mappings, guest profiles | Energy and drive mappings are separate from Free; each command needs its own OEM API and telemetry evidence. |
| Dreamer Rest / Mi / Mi V2 (`f6804h`, `f6803f`, `f6806`) | Product identification and explicit voice branches | Guest profile switching is unavailable. Shared model branches do not prove CAN values, navbar classes or account compatibility. |
| Free Rest Oversea (`f6811`) | Separate central/passenger navbar adapter, product identification, candidate Rest voice branch and read-only driver-interface probe | Navbar routing has fixture tests only. Driver A/B/C use a separate login process; account writes remain unavailable without a verified complete backup and restoration contract. |

The standard navbar adapter targets `com.qinggan.mainlauncher.navigation.NavigationBar`. Product `f6811` instead selects `com.qinggan.launcher.navigation.NavigationBarMain` and `NavigationBarSecond`. Both classes must expose the observed click and theme signatures before either is modified. The Android 9/11 steering adapter requires both observed `KeyManager$5` callbacks and matching `QGKeyEvent` signatures. Voice, custom and camera keys use named constants. Unsupported signatures keep stock behavior. Runtime CAN enums are addressed by observed names, never guessed ordinals; command availability is still controlled by the exact reviewed command catalog.

## Driver seat and mirror memory

**Driver 1/2/3** recall the saved driver seat and mirror memory slots. The command IDs `GetFromMemoryCellOne`, `GetFromMemoryCellTwo` and `GetFromMemoryCellThree` send `DMS_GET_MEMORY_REQ` with values 1, 2 and 3 respectively. The inspected OEM memory data includes driver seat positions and left/right mirror positions. Controls sends a recall request through the vehicle service; it does not save new memory positions or call the account adapter for these commands.

Each recall requires approval for the exact firmware, a fresh **Parking** gear reading and zero vehicle speed. A service acknowledgment does not verify the final seat or mirror positions. See the [driver-memory guide](DRIVER_MEMORY.md) for voice phrases and use.

The factory path differs on the inspected Free Rest unit (`f6802h`, Android 11, `com.qinggan.app.vehicle` 1.0, APK SHA-256 `c4d83fdd…0a93ec`). `SeatMemoryManager.sendCmd01()`–`sendCmd03()` read the slot's saved positions for the current OEM account from the app's own preferences (`seatPosition0N` plus the account ID). If that record can be restored, they write `DRIVE_SEAT_{BACK,SLIDE,HEIGHT,ANGLE}_POSITION` and `{LEFT,RIGHT}_{VERTICAL,HORIZONTAL}_POSITION` (slot-suffixed names for slots 2 and 3) through `CanBusManager.setVehicleAndAirConditionBundleState(null, bundle)`, then call `setVehicleState(DMS_GET_MEMORY_REQ, N)`. Other processes reach the same code through the app's service `com.qinggan.app.vehiclebase.vehicle.fragments.air_and_seat.SeatService` with the string extra `seatAction` set to `sendSeat01`–`sendSeat03`. The service is not exported and the app runs as the system UID. The launcher on that unit has `VehicleState.DMS_GET_MEMORY_REQ`, so the voice plan resolves there, but Controls uses neither the position bundle nor the service. Its recall therefore depends on the positions the seat module already holds for the slot.

## Separate OEM account integration

Account **A/B/C** use a separate adapter. Guest account switching on Free, Free Rest, Free Rest BY and Dreamer requires the reviewed build, operation and OEM handler. Its recovery snapshot covers the original account record, metadata and four property values. Seat-provider rows and other vehicle settings are outside that snapshot. The profile adapter separately writes the current seat/mirror slots to the OEM tracking provider before switching.

On Oversea product `f6811`, the read-only probe checks `LoginUtil.chooseUserA/B/C(Context):boolean` in `com.qinggan.app.login` and a bounded classification of `/private/configs/token/accountInfo`. It never invokes account selection or identifies an active A/B/C slot. Overseas switching and restoration remain unavailable because the complete account-state and restoration contract is missing. See [OEM account-record recovery limits](ACCOUNT_RECOVERY.md).

## Launcher buttons

| Display | Launcher resource | Assignment |
| --- | --- | --- |
| 0 | `screen_up_icon_1`, `screen_up_icon_2` | Navigation, music |
| 0 | `screen_up_home_icon`, `screen_up_all_app_icon` | Home, All apps / menu; native unless explicitly assigned |
| 1 | `screen_up_home_icon`, `screen_up_icon_1`–`screen_up_icon_4` | Passenger buttons 1–5. Position 4 is radio on the inspected layout. |

The supported Qinggan `NavigationBar` loads the icon of the assigned Android activity. The icon refreshes after assignment and launcher theme changes. Clearing the assignment or disabling panel assignments restores the original appearance and hold flag. A tap opens the assigned activity on the button's display. **Controls settings** opens the Settings page directly. On the standard branch that reports the full foreground component, the assigned icon highlights when that activity is active on the same display. A hold, an empty assignment, or a failed launch uses the original action. Central Home and All apps change only after an explicit assignment; absent optional views do not disable the other positions. Climate and vehicle controls keep their factory behavior.

The overseas layout exposes central navigation, media, Home and All apps, plus passenger Home, Seat and All apps. Home and All apps keep their native behavior unless explicitly assigned. A hold still invokes the original click once. Theme changes and recreated bars refresh assigned icons; restoration is acknowledged only after both bars report the same request. Foreground highlighting is not implemented for this layout. Missing classes, signatures or view ownership never enable a replacement blindly.

After an APK or firmware update, use **Connection status** to verify the reviewed build and the active adapter versions before using the assigned controls.

### Absolute front climate

The H97 climate adapter verifies the OEM `AppConfig.isH97()` result and reads `AC_POWER_SWITCH` before issuing a confirmed request. It uses `setAirConditionStateEx(AirConditionState, float)` for left and right temperatures and `setAirConditionState(AirConditionState, int)` for the front blower. Absolute temperature requests use whole degrees from 16 to 30. Relative temperature requests on H97 respect the stock range of 15.5–30.5 °C. Fan requests use levels 1 through 8. These paths have been checked against the local stock launcher and vehicle settings APKs and exercised with mocked CAN calls.

## Startup and restore

**Prepare and install** in the Windows installer prepares the root-run logcat boot entry and authenticated local Telnet. On a reviewed head unit, the first-run check and **Connect Frida** use this channel to stage the verified agents and enable recovery through the Android boot job. No `su` executable is needed. The supervisor waits for `sys.boot_completed` and three samples of the same `system_server` PID, then watches both the system process and the target PID. Connection status reports each installed adapter.

The integrated V3 entry is installed before connecting the reviewed OEM handlers, avoiding a startup dependency cycle. The older optional worker-only entry remains available for maintenance. Use an explicit ADB serial and full `ro.build.fingerprint`; start with `-Mode status`. The initial ADB-prepared channel does not prove the OEM service's SELinux permissions at cold boot. See the prerequisites and separate boot qualification in [first-launch preparation](PRODUCTION.md#prepare-the-first-launch).

```powershell
.\tools\configure-boot.ps1 -Serial <ADB-serial> -DeviceSerial <hardware-serial> -Fingerprint <build-fingerprint> -Mode status
```

Export the verified original and its metadata to the PC before installing the entry. The tool preserves the original script's metadata and security context and validates the known generation before replacement. Use PC rollback before uninstalling the APK. The Android boot job is available on reviewed devices independently of the optional entry.

## Steering and display adapters

- The Android 9/11 QG steering adapter uses voice, custom and camera events exposed by a compatible key manager. It consumes a gesture after a successful launch and deduplicates the two callbacks by their shared down-time. The H97 `QGKeyEvent` parcel carries only the code, action, flags, data and display, so the launcher reads a zero down-time; a callback for the same key and gesture within 100 ms of the previous attempt ending then counts as the same event. An unmapped or failed gesture goes to the original callback. Static OEM constant classes are explicitly initialized before reading fields, including on Android 9.
- The separate CAN adapter hooks `BaseCanBusComponent.onCarKeyChanged(int,int):void` in `com.qinggan.canbus.service`, before the OEM observer fan-out. It uses the named `IVOKA`, `SWS_KEY_USER_DEFINED` and `SWS_KEY_PHOTO` constants. `SWS_KEY_AVM` is not treated as an alias for `SWS_KEY_PHOTO`. A deployment selects exactly one reviewed protocol; the Windows importer and Android loader reject conflicting protocol approvals.
- `can-steering-terminal` handles `SHORT_PRESSED` / `LONG_PRESSED` after a fresh key-down. `KEYDOWN` and `KEYUP` reach the original callback. This mode requires evidence that the assigned native action is driven by terminal events, with no competing action on an edge. Failed or unassigned terminal actions retain native delivery; chords, changed assignments and stale presses do not dispatch replacements.
- `can-steering-edges` owns a full down/up gesture only when both assignments are valid and launchable at down. A tap runs on release; a hold runs once at the configured threshold, with release as a fallback if the timer was late. An unassigned, disabled or unavailable target preserves native delivery from down onward. Repeats and bounce do not launch twice. A changed assignment, mixed terminal event, late target failure or chord after capture cancels further actions and retains ownership until release. These cases cannot reconstruct a native gesture after consuming its down. A chord already held before the assigned down remains fully native. Lost releases expire the action, but ownership remains until an up arrives; no guessed release is sent.
- The CAN route replaces the QG consumer for that reviewed deployment, preventing two Controls agents from executing one gesture. Its own package hash, worker, heartbeat and protocol-2 readiness marker are checked, and PC rollback stops the owned CAN worker. No CAN frame or synthetic physical key is written or replayed. Real Frida tests on an isolated API-28 application with independently authored API fixtures verify down/up tap, timed hold, both chord orders, late failure, missing assignments, unavailable targets and disabled state. The account reader also runs against isolated files through a private fixture path mapping; this does not establish OEM file access or account restoration. These are emulator checks, not OEM hardware tests. See [Frida agents](FRIDA_AGENTS.md) for the JavaScript modules and startup chain.
- Accessibility preserves the complete original gesture when the tap is unassigned or an assigned target is unavailable at key-down. New hold-only mappings are refused; existing ones are preserved as data and pass through. Once a key-down has been consumed, a later target removal or launch denial cannot restore the original physical gesture and never triggers a synthetic replay.
- The window service uses separate Android 9 and 11 adapters to reserve the left panel on central app launches and apply width and DPI to running apps. **Displays → App DPI defaults** sets the central app scale. The passenger override also requires separate review of the public built-in display. The virtual preview keeps its own DPI setting.
- The optional navigation feed observes the local OEM instrument cluster service. The virtual display shows the road names and distance supplied by that service. The media option sends title, artist, album, and playback state to a compatible cluster through the OEM media interface.

### Live window profiles

On a prepared head unit, select **Connect Frida**. **Window service** starts by default on supported Android versions. If it was turned off, enable it in **Firmware and OEM profiles**. Touch and hold an installed app tile to open **App settings → Window layout**. Set the width, scale and optional minimum left margin. Use **Apply** with window service enabled or **Save** to store the layout for later. When the app is closed, the profile applies on its next central display launch.

- A central launch from Controls or the launcher reserves the left strip automatically, including launches from music and radio buttons and apps without a profile. The agent uses the launcher panel's reported width for the current display bounds and density. Android stable insets account for system bars, with one exception: a navigation bar that Android reserves but no window provides is ignored. The supported Android 11 unit forces that bar on (`qemu.hw.mainkeys=0`) without showing one, so the window keeps clear of the left panel and the 50 px status bar and reaches the bottom edge. A manual margin can reserve additional space.
- Width is a percentage of the space beside the navigation panel. The window aligns to the right and can grow to the task's minimum width when there is room. Freeform support and a resizable task are required to place a window. On Android 11 a placed window then moves from freeform to multi-window mode with the same bounds, so it shows no caption bar with maximize and close buttons; Android 9 keeps freeform. Home on the central display hides placed windows until they are opened again. Where placement is unavailable, the service reports the limitation and can apply a supported DPI override.
- The selected app receives its own task configuration. Android can recreate its activity if the app delegates configuration changes to the system. Display-wide density and other apps keep their own configurations.
- An app can inherit the display default, use system DPI, or set its own DPI. **Restore defaults** returns it to the display default and the full available width beside the panel. Disabling the window service restores windows managed by the current agent session.
- The screen reports saved profiles separately from changes confirmed by Android. A missing response leaves the saved profile available for the next launch.
- Profiles and display defaults survive Android restarts and are included in app settings backups. Older profiles retain their width, DPI, and minimum inset under the shared panel rule. The reviewed passenger override changes only task density. For a supported adjacent launch in an active multi-window layout, Android selects placement. Pinned, private, and shared tasks keep native layout.

### Moving windows between screens

**Displays → Move windows with a swipe** sends an open app to another screen with a sideways swipe of two or three fingers (the setting chooses which; on by default with three fingers, which a map's own two-finger pan and pinch never use).

| Swipe | Where | Result |
| --- | --- | --- |
| Left over an app | Central screen | The app moves to the instrument cluster's navigation view. |
| Right over an app | Central screen | The app moves to the passenger screen. |
| Left over an app | Passenger screen | The app returns to the central screen. |
| Right over the home screen or the left panel | Central screen | The app on the cluster returns to the central screen. |

**Return windows** in the same dialog brings every moved window back, as do turning the swipe off and turning the window service off. One app at a time uses the cluster: sending a second one there returns the first to the central screen. While the fingers move, the window follows them and fades slightly; a short or slow swipe puts it back. All fingers have to land within 0.4 s; the swipe then needs 22 % of the screen width, or a flick of 6 % at 0.9 px/ms. A mostly vertical start, a pinch or an extra finger leaves the touch to the app.

The cluster has no Android screen of its own for apps. The cluster app (`com.qinggan.cluster`) draws its navigation view from a virtual display named `Cluster-Navi-Display`, which it creates as the system user and where the factory navigator normally runs its cluster activity. The swipe places the app on top of that display, so the cluster shows it whenever its navigation view is selected; the factory navigator's cluster view comes back when the app leaves. The display is found by name, owner package and owner UID on every swipe, never by number. The passenger screen is the reviewed public display `1`, where the launcher's second home runs.

An app keeps its scale on every screen: before it leaves the central screen the window agent makes the task fullscreen and drops only the sizes fixed for its place beside the panel, not its density. A reviewed passenger default density replaces it on the passenger screen. That screen's clock bar (`StatusBar2` of the OEM system UI, 50 px) is a system window that Android gives no inset for, so the agent measures the system windows docked at its top and places the app below them, in the same caption-free multi-window mode as on the central screen. The cluster shows the app fullscreen. Back on the central screen the app is placed beside the panel as on a launch. Controls, the launcher, system UI, the cluster, settings and vehicle apps, permission dialogs and other users' apps never move. Android keeps delivering the swipe to the app under the fingers: the agent watches the display's pointer events and consumes none, so a map may pan a little before the window moves.

The swipe needs the window service on Android 11 and the separately reviewed operation `window-display-move` in the firmware review. A window agent that has the needed framework members says `displayMove=1` in its readiness marker; Android 9 says `displayMove=0`. The operation was written against the supported head unit's `services.jar` (`TaskTapPointerEventListener.onPointerEvent`, `DisplayContent.getTouchableWinAtPointLocked`, `RootWindowContainer.moveStackToDisplay`) and is covered by tests against models of it. On the owner's head unit (2026-11-22) the navigator and a music app moved to the cluster and the passenger screen and back. See [Frida agents](FRIDA_AGENTS.md#moving-windows-between-screens).

### Letting Android reserve the panel strip (experimental)

**Firmware and OEM profiles → Let Android reserve the panel strip** changes who keeps apps clear of the launcher's left panel. Normally the window service moves each managed launch into a window beside the panel. With the option on, Android itself lays out every window of the central display beside it, and apps are told about the strip the way they are told about a system bar. It needs a running window service and is off by default.

**This option has not been verified on a head unit.** It was written against the Android 9 and Android 11 sources and is tested against models of their window managers. Treat the first use as a trial:

- Turning it on or off changes the display configuration, so running apps restart once.
- Every window is affected, including the launcher's home screen and factory apps that already leave room for the panel. Those may show a doubled gap. If they do, turn the option off: the per-app placement is unchanged and takes over again.
- The line under the switch shows what the agent reports: **Starting…**, **Applied, being checked**, **On · N px reserved**, or why it was not applied. **Not available on this firmware (…)** names the precondition that does not hold; trying again will not change it.
- If the system process restarts while the strip is reserved, the agent does not reserve it again on its own, on this boot or later ones. The line then reads **Turned off after the system process restarted**, and switching the option on again is a new attempt.
- A managed app whose profile uses the full width beside the panel is no longer put into a freeform window. A narrower width, a wider left margin or a per-app DPI still work as before.
- The choice stays on this head unit and is not part of a settings export.

The mechanism, its preconditions and the record that prevents a restart loop are described in [The reserved panel strip](FRIDA_AGENTS.md#the-reserved-panel-strip-experimental).

### Keyboard of the central display

The input method service of the supported Android 11 firmware ignores Android's selected keyboard (`default_input_method`). Each time it re-reads its keyboard settings it binds a fixed keyboard chosen by the system language: the OEM keyboard `com.qinggan.app.qgime` for English or Chinese, the AOSP keyboard `com.android.inputmethod.latin` otherwise. `ime set`, the factory **Keyboard** page in Android settings and a direct settings write are therefore undone within milliseconds. When the language check selects the OEM keyboard while that package is disabled, as on the inspected unit, the central display has no keyboard at all.

**Settings → Keyboard** lists the installed keyboards with their names and icons, shows what Android bound for the central display, and offers **Factory keyboard** to return in one tap. The window service carries the part that makes a choice stick: in `system_server` it replaces the firmware's fixed choice with the stored one, but only while that keyboard is installed, enabled in Android's list and not an OEM keyboard. Controls stores the choice in the secure setting `open_voyah_controls_input_method`, makes Android re-read its keyboard settings and reads `mCurMethodId` back from the input method service. A choice Android did not bind within about three seconds is removed again.

- Without a running window service the firmware chooses as before, so the page offers a choice only while the agent has announced its keyboard policy. **Factory keyboard** needs only the root channel.
- A keyboard that is uninstalled or switched off is ignored at once and the firmware keyboard returns. At the next start of the agent, or when the page opens, the stored choice is dropped and the page names the reason.
- A keyboard that disconnects three times within a minute is treated as crashing: the agent drops the choice, records the reason and lets the firmware choose.
- Controls never disables a keyboard. The factory keyboards stay installed and enabled.
- The passenger display has its own input method service (`input_method_ext`), bound to the separate OEM package `com.qinggan.app.qgime.second`. Controls leaves it alone: one keyboard package cannot serve both services at the same time, which is why the OEM ships two copies. The page shows the passenger keyboard for reference.
- The choice is a setting of this head unit and its installed apps. It is not part of a settings export.

The Android SDK version and product name describe candidate firmware branches. OEM key delivery, the passenger display ID, window service signatures, and cluster updates still require testing on the actual head unit.

## Text translation

Select Russian or English in **Firmware and OEM profiles**. The app restarts the reviewed agents, and the factory screens change within a second; reopen a screen that was already open if a label stays unchanged. Translation is off until a language is chosen.

The built-in dictionaries live in `android/hooks/localization/{ru,en}.json` and are bundled into the localization agent, so the published setting only names the chosen one (`"preset": "ru"`). They cover the launcher (home, cards, all-apps page), the pull-down panels (`com.qinggan.app.hiboard`), Settings, Vehicle and the Bluetooth phone: about 2,700 labels, templates and explanations. Keys are the exact texts the overseas firmware shows: its English defaults and the Chinese strings it never translated. The Russian dictionary translates both; the English one only replaces the remaining Chinese. Templates with placeholders (`%1$s connected`) are translated too: the agent also hooks `Resources.getText`, which `getString` uses before the app formats the text, so the template is replaced and the app fills in its values. A template's translation keeps exactly the same format specifiers in the same order (the preset test enforces it). Long factory explanations and dialogs are included (a preset entry may be up to 1,024 characters), and units are written the Russian way ("10 мин", "125 Гц"). Excluded on purpose: brand and product names, codes and identifiers, gear letters, date patterns and debug strings.

The scope follows the strings that the previous tweak tool's resource overlays covered for these packages. Only the resource names were used to pick source strings from the factory APKs; every translation was written for this project.

Each package is translated only by its own agent: the launcher's main agent, and one `localization.bundle.js` worker each for `com.qinggan.app.setting`, `com.qinggan.app.vehicle`, `com.qinggan.bluetoothphone` and `com.qinggan.app.hiboard` (channels `locale-setting`, `locale-vehicle`, `locale-bluetooth`, `locale-hiboard`). A worker is staged only while translation is on and its package is pinned in the reviewed firmware catalog (`verified-firmware.tsv`, or the local-vehicle review for an owner's build). A replacement is a single exact lookup in a per-package table, built once when the setting changes. The hooks are `TextView.setText`, `TextView.setHint`, `TypedArray.getText` and `Resources.getText`; text drawn directly on a canvas is not translated. They exist only while a dictionary applies: with translation off the four app workers are not started, and the launcher's main agent removes its text hooks within a second, so text calls no longer pass through Frida. Turning translation off does not restore labels already replaced on screen; they return to the original as the app redraws them. Like a resource overlay, a translated resource string also changes in code that uses it as data, so dictionary keys are user-visible labels only.

Translation is attached to 64-bit app processes only. On the 97C head unit (`f6802h`) the factory Settings (`com.qinggan.app.setting`) and Bluetooth phone (`com.qinggan.bluetoothphone`) run 32-bit (`armeabi-v7a`), and the Frida agent crashed both with SIGSEGV within seconds of attaching (Android restarted them until the worker's retry limit stopped it). The app therefore stages no worker for a package whose primary ABI is 32-bit, and `supervise.sh` refuses a target whose process is `app_process32` (`Translation is not supported in a 32-bit app process.`). On that firmware those two apps stay in English; their dictionary entries remain for firmwares where the apps run 64-bit.

A custom JSON dictionary can name a preset and add or override entries on top of it, up to 1,000 entries and 48 KB. Without `preset` it replaces only its own entries. The optional `products` list limits the dictionary to specified product identifiers.

```json
{
  "schema": 1,
  "enabled": true,
  "preset": "ru",
  "products": ["f6802h"],
  "packages": {
    "com.qinggan.app.launcher": {"Video": "Кино"}
  }
}
```

Use the same settings screen to export a dictionary, restore the previous dictionary, or disable translation. For the separate instrument display, see [Instrument cluster localization](CLUSTER.md). Vehicle behavior remains unverified.
