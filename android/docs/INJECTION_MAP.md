# Injection targets and controls

This describes the implemented adapters, not qualified head units. The production firmware and operation catalogs are currently empty: a model name alone does not enable these hooks. Release activation requires the exact reviewed fingerprint, SDK, ABI and package hashes, with additional operation approval for physical/profile changes. The installer and first-run checks can establish local access without enabling an unreviewed OEM adapter.

## Target processes

| Package / process | Agent and purpose | Activation |
| --- | --- | --- |
| `com.qinggan.app.launcher` | Main bundle: launcher buttons/icons, QG steering callbacks, fixed voice-command API dispatch, cluster media publishing and launcher text translations | Main reviewed connection. Individual features also check their settings and capabilities. |
| `android` / `system_server` | `window.bundle.js`: central panel inset, per-app width and DPI; separately reviewed passenger density; the owner's keyboard for the central display | Optional window worker; Android 9/11 signatures and framework review required. It modifies window-service behavior, not the target app's APK. |
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

`panel-state.js` and `panel-icons.js` apply the assigned activity icon and pressed feedback, refresh after theme changes, and restore original appearance/long-click state when an assignment is cleared. The standard `updateSelectedApp(package, activity)` callback also highlights the assigned activity on its own display by checking its button, so the launcher's own `QGRadioGroup` selection slider (`dock_app_selected`) marks it. The replacement background holds only the icon and the pressed ripple, never a plate of its own, which would stack a second rounded square on the slider. An app can name a plate-free panel glyph with the application meta-data `org.openvoyah.controls.PANEL_ICON`; Open Voyah Controls names `ic_controls_mark`, the winged mark on a transparent background, so its entries show only the mark while its launcher icon keeps the dark plate for the app grid. Per-app icon choices made in Controls (**Apps → app settings → Icon**: a custom picture, or the launcher icon without its white background, experimental; Yandex Navigator `ru.yandex.yandexnavi` starts without it until the driver turns that off) reach the panel as finished pictures: Controls renders a 112 px PNG for every panel app the driver changed, writes them to Settings.Global `org.openvoyah.controls.panelIcons` (`{"schema":1,"icons":{package: base64 PNG}}`) before the panel configuration, and names that document with the configuration's `customIcons` version. The agent decodes each picture once per version and draws it ahead of every other icon. A plate-free icon's ripple is masked to the button's rounded bounds, because a ripple without a mask shows only where the icon paints. If no such picture or glyph can be read, the entry uses the launcher icon. This is an in-memory change; it does not replace launcher APK resources.

### Lowered-screen menu

When the motorised display is lowered, each `NavigationBar` hides its normal panel and shows `screen_down_navigation`, a 560 px menu whose only visible button is Home (see [the home screen map](HOME_SCREEN_MAP.md#lowered-screen-layout)). It exists only on H97 launchers. The same assignment keys apply there:

| Screen | OEM view / resource | Assignment key | In the lowered menu |
| --- | --- | --- | --- |
| Central | `mScreenDownHomeView` / `screen_down_home_icon` | `mainHome` | The factory Home button |
| Central | added after Home, mirrors `mScreenUpItemView1` | `navbar01` | Our button, only while assigned |
| Central | added, mirrors `mScreenUpItemView2` | `navbar02` | Our button, only while assigned |
| Central | added, mirrors `mScreenUpAllAppView` | `mainApps` | Our button, only while assigned |
| Passenger | `mScreenDownHomeView` / `screen_down_home_icon` | `navbar11` | The factory Home button |
| Passenger | added, mirror `mScreenUpItemView1` … `mScreenUpItemView4` | `navbar12` … `navbar15` | Our buttons, only while assigned |

**Home.** The lowered Home behaves like the panel buttons above. When assigned, a tap opens the assignment, and a hold or a failed launch runs its own factory click, which opens the launcher home.

**Added buttons.** Every other assigned position gets a button of our own in `screen_down_radio_group`, after Home and in the normal panel's order. Unassigned positions get none, so an unassigned menu looks exactly as the factory one. The hidden, unwired Phone button is left alone.

- **Class and id.** A button is a `NoToggleRadioButton`, so a tap does not move the selection slider by itself. It reuses the id of the normal-panel view it mirrors: that class logs the resource name of its id on every touch, and a generated id would throw there. Ids only need to be unique within the radio group.
- **Look.** It shows the same icon as the normal button (custom pictures, plate-free glyphs and the launcher icon alike). Its content description is the activity's label.
- **Clicks.** Its click listener is the bar. A tap launches the assignment on the bar's display. A hold, or a failed launch, runs the factory click of the mirrored normal view once, so the button never reaches OEM code with an id that code does not expect.
- **Spacing.** The menu's group margins (142 px) are set to 0 while any button of ours is in it; the menu centres the group. Buttons keep the normal panel's 32 px gap where the frame allows, and close up where it does not: five passenger buttons get 19 px.
- **Selection.** The foreground callback marks the assigned button in each radio group: the normal panel's and the lowered menu's. Each group's previous selection is restored before the next OEM update.

**Mode switches and lifetime.** A button stays in the menu as long as its assignment does. The factory switch only toggles the two menus' visibility, so the agent adds no hook for it, and a switch never adds or removes views. Every refresh first removes our buttons, then adds them again once, so assignment changes, theme reloads and a rebuilt normal panel never duplicate them. A bar built while the agent runs inflates the lowered menu after its normal panel, so `initScreenDownViews` refreshes the bar once more.

**Restore.** Clearing or disabling assignments, the release token, the bar's `release()` and detaching remove our buttons, unchecking one that is still checked. They also put the group's margins back and restore Home's icon and hold flag. A button that cannot be removed holds the release token back and is retried.

**Failures.** If the button class is missing, or the menu refuses a button, the lowered menu keeps its own Home and the normal panel stays as assigned. Such a failure is reported once per slot (`OVC_PANEL_ICONS_ERROR stage=lowered`) and is not retried every second.

**State for Controls.** `screen-lift.js` hooks `NavigationBar.doScreenLift(int)`. When the central bar switches, it writes Settings.Global `org.openvoyah.controls.screenLowered` (`1` lowered, `0` not), only when the value changes. At start it publishes `ScreenLiftUtils.getScreenLiftState()`. Disposal deletes the value, so Controls never keeps its lowered grid layout after the agent has gone. Failures are reported as `OVC_PANEL_ERROR stage=screen_lift` and leave the panel alone.

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

Both classes are under `com.qinggan.launcher.navigation`. Home, Seat and All apps change only after an explicit assignment; their native policies remain otherwise. Tap, hold and failed-launch fallback follow the same panel rule. Recreated bars and theme changes refresh assigned icons; both bars must confirm restoration. This branch has no foreground-app highlight adapter and no separately identified radio slot. It does not change passenger screen-lift policy. Buttons in a lowered-screen menu are added only for `NavigationBar` above, not for this branch.

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

The factory home screen's structure and candidate hook points are in [the home screen map](HOME_SCREEN_MAP.md). Two of its cards are targets, each only while its setting is on (both off by default). `home-navi-card.js` covers the Baidu map card with a Yandex Navigator card and takes its taps, see [Home navigation card](FRIDA_AGENTS.md#home-navigation-card). `home-weather-card.js` feeds the weather card with Open-Meteo weather that Controls fetched, see [Home weather card](FRIDA_AGENTS.md#home-weather-card). The other cards, the all-apps page, the lowered-screen cards and the pull-down panels are not targets; the lowered-screen menu is covered in [Lowered-screen menu](#lowered-screen-menu).

- Voice capture, microphone permission, local speech recognition and the voice popup belong to the ordinary Controls APK. Agents dispatch only the fixed allowed command set through observed OEM APIs. An accepted API call is not proof of a physical state change.
- Window width/DPI is applied in `system_server`, with separate Android 9 (`com.android.server.am`) and 11 (`com.android.server.wm`) paths. Native placement is retained for unsupported, private, pinned or shared tasks.
- Translation hooks `TextView.setText`, `setHint` and `TypedArray.getText` inside the selected OEM process. Only configured exact strings change. It does not turn OEM screens into Compose.
- Cluster media uses the OEM manager from the launcher; navigation observation attaches to the cluster service. Cluster file installation/restoration is a separate reviewed root transport workflow.
- Oversea A/B/C account selection remains unavailable without complete backup/restore evidence. Numeric Driver 1/2/3 commands address seat memory and are not account slots.

All **Controls-owned** screens and dialogs are Compose; the native OEM screens keep their own rendering. See [Compose UI coverage](COMPOSE_UI.md) and [firmware limits](FIRMWARE.md) for the remaining distinctions.
