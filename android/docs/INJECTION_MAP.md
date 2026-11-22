# Injection targets and controls

This describes the implemented adapters, not qualified head units. The production firmware and operation catalogs are currently empty: a model name alone does not enable these hooks. Release activation requires the exact reviewed fingerprint, SDK, ABI and package hashes, with additional operation approval for physical/profile changes. The installer and first-run checks can establish local access without enabling an unreviewed OEM adapter.

## Target processes

| Package / process | Agent and purpose | Activation |
| --- | --- | --- |
| `com.qinggan.app.launcher` | Main bundle: launcher buttons/icons, QG steering callbacks, fixed voice-command API dispatch, cluster media publishing and launcher text translations | Main reviewed connection. Individual features also check their settings and capabilities. |
| `android` / `system_server` | `window.bundle.js`: central panel inset, per-app width and DPI; separately reviewed passenger density | Optional window worker; Android 9/11 signatures and framework review required. It modifies window-service behavior, not the target app's APK. |
| `com.qinggan.app.setting` | `localization.bundle.js`: exact dictionary replacements in settings text | Translation enabled, matching product/package dictionary and package review. |
| `com.qinggan.app.vehicle` | Same text adapter for vehicle screens | Same translation requirements. This worker does not replace vehicle button listeners. |
| `com.qinggan.bluetoothphone` | Same text adapter for Bluetooth/phone screens | Same translation requirements. Calls remain under the OEM application. |
| `com.qinggan.app.hiboard` | Same text adapter for the pull-down panels (top and vehicle panels) | Same translation requirements. Panel gestures and tiles stay with the OEM process. |
| `com.qinggan.cluster` | `navigation.bundle.js`: observe cluster navigation callbacks and forward bounded route data to Controls | Optional navigation feed and cluster package review. No cluster screen or button replacement. |
| `com.qinggan.canbus.service` | `can.bundle.js`: intercept the observed service-side key callback for voice/custom/camera | Exact CAN package review plus one selected terminal-event or down/up protocol. It does not create a second CAN subscriber. |
| `com.qinggan.app.login` | `oversea-profiles.bundle.js`: bounded one-shot inspection of driver API signatures and account-state availability | Oversea only, explicit profile request, reviewed login package. No account selection or restoration. |

`FridaBridge.connectLocked`, `stageLocalizationWorkers`, `stageOptionalHook` and `app/src/main/assets/hooks/supervise.sh` choose the persistent targets. `OverseaProfiles.probeCommand` owns the separate login probe. The main bundle entry is `hooks/entry.js`.

Installed third-party apps are launched through Android intents. They are not injection targets. Controls itself does not need production self-injection. The JS files are local Frida agents, not web pages or downloaded remote code. See [the agent guide](FRIDA_AGENTS.md) for the root boundary and module responsibilities.

## Standard launcher panel

Entry: `hooks/launcher-buttons.js`, class `com.qinggan.mainlauncher.navigation.NavigationBar`, `onClick(View)` and both `View.performLongClick` signatures. Ownership checks restrict changes to the identified launcher views. Android display 0 is central and display 1 is passenger in this adapter; that mapping still requires device qualification.

| Screen | OEM view / resource | Assignment key | Meaning in the inspected layout |
| --- | --- | --- | --- |
| Central | `mScreenUpItemView1` / `screen_up_icon_1` | `navbar01` | Navigation |
| Central | `mScreenUpItemView2` / `screen_up_icon_2` | `navbar02` | Music |
| Central | `mScreenUpHomeView` / `screen_up_home_icon` | `mainHome` | Home |
| Central | `mScreenUpAllAppView` / `screen_up_all_app_icon` | `mainApps` | All apps / menu |
| Passenger | `mScreenUpHomeView` / `screen_up_home_icon` | `navbar11` | Passenger position 1 / Home |
| Passenger | `mScreenUpItemView1` / `screen_up_icon_1` | `navbar12` | Passenger position 2 |
| Passenger | `mScreenUpItemView2` / `screen_up_icon_2` | `navbar13` | Passenger position 3 |
| Passenger | `mScreenUpItemView3` / `screen_up_icon_3` | `navbar14` | Passenger position 4 / Radio |
| Passenger | `mScreenUpItemView4` / `screen_up_icon_4` | `navbar15` | Passenger position 5 |

The code deliberately keeps generic names where a stable semantic label has not been established. Central Home remains native until assigned. With reviewed panel assignments enabled, central All apps / menu defaults to the Controls app grid; existing saved assignments remain intact. Selecting the OEM action explicitly saves a native override. Missing optional views leave the other supported positions usable. Climate controls and the vehicle settings button retain their factory behavior.

For an assigned and available target, a tap opens the selected app or supported Controls action on that button's display. A hold invokes the original OEM click once. An unassigned/disabled button or a failed replacement launch calls the original click once. Unsupported signatures or view ownership leave the original path in place.

`panel-state.js` and `panel-icons.js` apply the assigned activity icon and pressed feedback, refresh after theme changes, and restore original appearance/long-click state when an assignment is cleared. The standard `updateSelectedApp(package, activity)` callback also highlights the assigned activity on its own display by checking its button, so the launcher's own `QGRadioGroup` selection slider (`dock_app_selected`) marks it. The replacement background holds only the icon and the pressed ripple, never a plate of its own, which would stack a second rounded square on the slider. An app can name a plate-free panel glyph with the application meta-data `org.openvoyah.controls.PANEL_ICON`; Open Voyah Controls names `ic_controls_mark`, the winged mark on a transparent background, so its entries show only the mark while its launcher icon keeps the dark plate for the app grid. The panel configuration can also list packages in `foregroundIcons`: their entries draw only the foreground layer of their adaptive launcher icon, without its background plate, with the icon's visible part enlarged 1.3 times. Controls lists Yandex Navigator (`ru.yandex.yandexnavi`) unless the driver turns off **Yandex Navigator panel icon without the white background** under the panel assignments. A plate-free icon's ripple is masked to the button's rounded bounds, because a ripple without a mask shows only where the icon paints. If the glyph cannot be read, or a listed launcher icon is not adaptive, the entry uses the launcher icon. This is an in-memory change; it does not replace launcher APK resources.

## Oversea launcher (`Build.PRODUCT = f6811`)

Entry: `hooks/navbar-oversea.js`. This branch has separate classes and exposes different controls.

| Class / display | OEM view | Assignment key | Control |
| --- | --- | --- | --- |
| `NavigationBarMain` / central | `mScreenUpItemView1` | `navbar01` | Navigation |
| `NavigationBarMain` / central | `mScreenUpItemView2` | `navbar02` | Music/media |
| `NavigationBarMain` / central | `mScreenUpHomeView` | `mainHome` | Home |
| `NavigationBarMain` / central | `mScreenUpAllAppView` | `mainApps` | All apps / menu |
| `NavigationBarSecond` / passenger | `mScreenUpHomeView` | `navbar11` | Home |
| `NavigationBarSecond` / passenger | `mScreenUpSeatView` | `navbar12` | Seat |
| `NavigationBarSecond` / passenger | `mScreenUpAllAppView` | `passengerApps` | All apps / menu |

Both classes are under `com.qinggan.launcher.navigation`. Home, Seat and All apps change only after an explicit assignment; their native policies remain otherwise. Tap, hold and failed-launch fallback follow the same panel rule. Recreated bars and theme changes refresh assigned icons; both bars must confirm restoration. This branch has no foreground-app highlight adapter and no separately identified radio slot. It does not change passenger screen-lift policy.

## Steering wheel and Android hardware keys

The OEM assignment keys are `voiceShort`/`voiceLong`, `customShort`/`customLong` and `cameraShort`/`cameraLong`.

| Route | Entry point | Consumption and fallback |
| --- | --- | --- |
| QG callback in launcher | `steering-qg.js`: `KeyManager$5.onEvent(QGKeyEvent,String,int)` and `onKeyEvent(QGKeyEvent)`; Android 9 and 11 | Uses the named IVOKA, user-defined and AVM key constants and OEM short/long events. A shared down-time deduplicates both callbacks. The H97 parcel drops the down-time, so there the same key and gesture within 100 ms of the previous attempt ending count as one event. Only a successful assigned action consumes the event; unsupported, canceled, unassigned or failed events use the original callback. |
| Direct CAN terminal events | `can-steering.js` + `can-keys.js`: `BaseCanBusComponent.onCarKeyChanged(int,int)` | Named `IVOKA`, `SWS_KEY_USER_DEFINED`, `SWS_KEY_PHOTO` constants. Down/up remain native; a successful assigned short/long terminal event is consumed once. Changed settings, chords or failed targets do not launch a replacement. |
| Direct CAN down/up | `can-steering.js` + `can-edges.js`, same service callback | Requires both tap and hold targets to be launchable before owning the down event. Otherwise the whole gesture remains native. Once captured, a late failure/chord/change cancels further custom action; a consumed physical down is never replayed. This limitation needs explicit device qualification. |
| Android-delivered hardware keys | `ButtonAccessibilityService` + `core/ButtonEngine` | No Frida injection. Only keys delivered to Accessibility can be assigned. Home/Power are excluded. Missing tap target or hold-only mapping leaves the whole gesture native; a tap-only assignment also runs on release after a hold. |

Only one reviewed OEM steering route is selected. The launcher adapter delegates when direct CAN is selected. Hardware/vehicle delivery still needs testing to establish that no independent OEM consumer duplicates an action. The camera key can be assigned to an app; this does not alter the camera application itself.

## Other screens and actions

The factory home screen (cards, the all-apps page, the lowered-screen layout and the pull-down panels) is not an injection target yet; its structure and candidate hook points are in [the home screen map](HOME_SCREEN_MAP.md).

- Voice capture, microphone permission, local speech recognition and the voice popup belong to the ordinary Controls APK. Agents dispatch only the fixed allowed command set through observed OEM APIs. An accepted API call is not proof of a physical state change.
- Window width/DPI is applied in `system_server`, with separate Android 9 (`com.android.server.am`) and 11 (`com.android.server.wm`) paths. Native placement is retained for unsupported, private, pinned or shared tasks.
- Translation hooks `TextView.setText`, `setHint` and `TypedArray.getText` inside the selected OEM process. Only configured exact strings change. It does not turn OEM screens into Compose.
- Cluster media uses the OEM manager from the launcher; navigation observation attaches to the cluster service. Cluster file installation/restoration is a separate reviewed root transport workflow.
- Oversea A/B/C account selection remains unavailable without complete backup/restore evidence. Numeric Driver 1/2/3 commands address seat memory and are not account slots.

All **Controls-owned** screens and dialogs are Compose; the native OEM screens keep their own rendering. See [Compose UI coverage](COMPOSE_UI.md) and [firmware limits](FIRMWARE.md) for the remaining distinctions.
