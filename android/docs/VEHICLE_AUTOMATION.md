# Vehicle automation and H97D qualification

Open Voyah Controls keeps vehicle observations, saved preferences, routine selection and physical command dispatch separate. A saved rule is configuration. A cached getter, an earlier confirmation, an accepted request and a verified vehicle state are different results.

The primary target is VOYAH FREE 318 H97D. The stock vehicle app identifies an H97D branch as `f6808`; that string alone does not qualify an installed head unit. Record its Android product, optional factory product, complete build fingerprint, SDK, ABI, OEM package versions, signing certificates and APK hashes. The production firmware and command catalogs remain closed until the exact build and each requested capability have device evidence. [Firmware qualification](PRODUCTION.md#review-a-firmware-candidate)

## Capability boundaries

| Feature | Reusable project interface | Evidence still required on H97D |
| --- | --- | --- |
| Headlights on Drive/Park transitions | Existing factory light-switch observation, separate lamp readings, last-confirmation history, native command catalog and Park/zero-speed gate | Fresh gear transitions with an identified source; matching light feedback before and after a stock action; the exact H97D lamp setter and values; sleep/wake behavior. An Auto selector does not establish whether the dipped beams are on. |
| Summer/winter climate | Bounded front temperature and blower commands, separately reviewed climate/seat commands, saved seasonal preferences, and one shared reservation between an automatic and a manual apply | Outside-temperature units and freshness; start-of-trip or readiness events; the installed climate/seat capability and final-state feedback for every selected action. A phone weather value or an old cached outside-temperature getter is insufficient. |
| Driving-mode memory | Named native driving-mode commands and confirmed observation storage | Actual selected-mode feedback, exact mode names/values and setter for the installed build; startup/reset timing; confirmed post-restore state; distinctions between driving mode, energy mode and regeneration. A requested mode must not overwrite confirmed history. |
| Factory driving assistance | Public factory vehicle settings entry and manufacturer guidance | Installed ADAS equipment and software identity, including the stock `ASC`/`SDB` configuration classification; supported menus; account, learning and service status shown by that firmware; the factory availability result. Opening settings does not activate assistance. |
| If–then rules | Existing saved routines and validated references to their IDs | Gear and individually identified door callbacks, correct open/closed interpretation, freshness, disconnect/sleep invalidation and reconnection behavior. Unknown or historical values must not create a trigger. |

An automatic seasonal apply and a manual confirmation share one reservation. A repeated identical group is reported as recently applied and is not sent twice, even if the automatic pass released the transport between the two attempts.

An emulator can exercise forms, persistence, import/export, condition evaluation, cancellation and synthetic event ordering. It cannot qualify CAN delivery, actuator effects, actual equipment or a vehicle's sleep cycle. Synthetic results must remain identified as fixture evidence.

The [H97D driving-operation manual](https://m.dsmp.voyah.com.cn/userguide/h97d/h97D-37.html) places driving and power-mode selection in **Vehicle → Driving preferences**. It describes READY entry after pressing the brake and READY exit through the parked driver-exit sequence. These user-facing steps do not establish the callback, timestamp or reset ordering needed for automatic restoration. Retain the preceding confirmed mode until an observation has been classified for the new trip; do not replace it with an unexplained startup default.

## Read-only status

**Vehicle routines** shows the admitted gear, speed, outside temperature and door readings together with their receipt time. A missing, expired or unqualified value shows **Waiting** and remains listed under **Not yet qualified**; saved preferences and raw diagnostic callbacks are never substituted for current vehicle telemetry. This summary is display-only and performs no vehicle request.

## Runtime ownership

Enabled automatic features use one process-owned runtime. The existing Controls Accessibility service holds its background lease, so leaving the app does not stop observation or reset a pending condition. Without that service, a resumed Controls screen provides a foreground lease. Diagnostics states which owner is available. No additional Android service or permission is introduced. Disabled features do not start a vehicle reader just because the Accessibility service is connected.

Screens share telemetry, light history and the automatic controller with the background owner. Moving between foreground and background preserves the current edge baseline. Screen-off and source disconnection revoke live signal windows; waking seeds the next session without replaying a missed action. Manual mode restoration keeps its explicit confirmation, which expires when the UI leaves. It does not stop unrelated background monitoring.

An automatic saved routine uses the existing application runner. When the app is closed, its Activity receives a single-use, process-local token; it rereads the selected rule, the exact saved routine receipt and the live signal conditions before execution. Unknown, expired, replaced or already claimed tokens do nothing. Process death discards a pending token. An attempted or ambiguous action consumes its edge and is not automatically retried.

## Read-only collection on the vehicle

Use one explicitly selected authorized head unit and the existing evidence collector. The following reads metadata, advertised interfaces and a bounded Controls event window; it does not enable a firmware capability.

```powershell
./tools/read-firmware-evidence.ps1 -Scope Cockpit -Serial <ADB-serial> -SdkRoot <Android-SDK> -EventWindowSeconds 45
```

1. Record the installed Controls version and the full firmware/package evidence before interpreting a callback. Keep OEM APKs and private account data outside the source tree and settings archives.
2. Open the original vehicle settings from Controls. Record whether Android offers the public route and whether the intended factory page actually appears. An unavailable shortcut is a handoff limitation, not evidence that the vehicle lacks the feature.
3. Copy the lighting-source report with the factory selector position and instrument indicators noted at the same time. Preserve earlier confirmations and their original times when the live source disappears. [Lighting evidence](HEADLIGHT_STATUS.md)
4. Collect gear, speed, temperature, readiness, actual driving mode and each door only through reviewed read interfaces. Record a result of unavailable or unverified when admission, permissions, source identity, units or callback semantics are missing. Do not synthesize state from an app setting or command acknowledgment.
5. Compare observations before and after changes made through the original controls. Record connection loss and a normal sleep/wake cycle. A reconnect or initial cache delivery must establish a baseline instead of replaying an action.
6. Review the exact physical command separately. Preserve the existing per-command firmware approval, confirmation and Park/zero-speed checks. An ambiguous result remains unresolved; it is never retried automatically.

If the stock service supplies no timestamp or sequence number, the Controls receipt time identifies arrival only. A fresh subscription cannot prove that an OEM-internal queue contains a new controller sample. Capture the behavior on the exact firmware before promoting it to actionable telemetry.

The automation report adds a schema-1 `vehicle_automation` section alongside the existing lighting report. It records capture time, session/start time, firmware identity, typed signals, native-reader status, runtime ownership, the outcome of the cached `getGearStatus` read, bounded raw callbacks, missing signals and action admission. `missing_signals[].required_evidence` describes the next observation needed; an absent read is not replaced with a guessed value. `h97d_service_admitted=false` remains explicit until the exact service is reviewed. The PC cockpit event window also captures typed observation, automatic-runtime, dispatch and climate outcomes from the verified Controls process, without exporting arbitrary log payloads or routine names.

The admitted source adapter decodes actual `DRIVING_MODE_SET_FB` values 1–6. It ignores `DRIVING_MODE_SET`, which the inspected setter publishes before sending the request. Door positions receive separate timestamps only when that position changes after an initial baseline; a lock-only `DoorStatus` callback never refreshes their age. Outside temperature comes from the 82-word `AirCondition` callback 4, field 36, rather than ambient callback 14. The inspected producer computes raw byte / 2 − 40 °C and uses −9999 for invalid input. A cabin/fan callback with unchanged outside temperature does not refresh the field.

The headlight trigger keeps the last observed gear as its edge baseline across an age timeout within the same session and source. The current gear reading still expires. A new action needs fresh gear and the existing lighting and standstill checks. An invalid reading, disconnect or source change clears the baseline. Seasonal climate rechecks the original session and fresh conditions before publishing each setpoint. If the session ends between commands, the remainder stops and any completed commands are reported as a partial result.

The exact inspected CAN APK routes its three existing admitted products (`f6802h`, `f6805h`, `f6811`) to the same `DongfengH97CCanBusComponentImpl` producer through factory IDs `0x86`, `0x3459` and `0x345a`. The inspected door, outside-temperature and mode producers contain no additional H97X/H97Y branch. This establishes the adapter's source coverage; it does not admit H97D or qualify physical actions.

Speed callback 16 cannot establish standstill: the inspected producer emits integer zero both for a valid stop and for invalid input. It therefore remains diagnostic and cannot open a stationary-action gate. H97D requires an independently observable validity signal and confirmed units, together with its exact signed CAN service identity. READY trip boundaries, controller freshness and sleep/wake behavior still require vehicle evidence; these source decoders do not grant an H97D action approval.

## Factory Apollo/NOA procedure

The [FREE 318 H97D driving-assistance manual](https://m.dsmp.voyah.com.cn/userguide/h97d/h97D-40.html) describes **Vehicle → Driving assistance** and requires TLC to be enabled before NOA. NOA needs a supported navigation route, working lane-centering/steering assistance, usable GPS/navigation, healthy unobstructed sensors and functioning turn indicators. The manual distinguishes gray standby from blue active indication. It leaves activation to the original controls and the driver.

The public H97D manual does not provide an Apollo activation API, a minimum firmware version, a package/hash whitelist or an account/examination enrollment sequence for this particular installed build. Instructions for Huawei ADS on other VOYAH models do not establish an H97D procedure. The separately inspected factory settings APK contains QR-code flows for training/examination and service renewal, with a matching phone/vehicle account requirement. Those resources establish a candidate factory workflow; the branch actually selected on the target H97D remains unverified. Complete the training, examination and entitlement steps requested by the installed factory software, and obtain the exact manufacturer's requirements for that build before adding an integration.

The inspected stock controller checks a logged-in, non-guest account, completed learning, the TLC prerequisite and a factory confirmation when the driver enables NOA. Subscription and expiry are separate factory states. The same APK distinguishes N1, N2 Baidu and N2 Hikvision equipment through `VehicleConfigHelper.readOnlineConfigItem(ASC/SDB)`; its Baidu classification checks both items on the matching platform. Record those actual read results and the selected factory page before choosing an adapter. A FREE 318 product label does not establish its ADAS supplier or eligibility. Do not replace this classification with a guessed model check.

Controls can open the publicly offered factory settings activity. It must report that as a requested handoff, with no claim that Apollo is enabled or active. Qualification needs the factory menu result and installed ADAS version/equipment evidence. The stock handoff performs no account impersonation, entitlement changes, hidden activity guesses or automatic Pilot input. A separately authorized [activation candidate](APOLLO_CANDIDATE.md) is prepared for isolated fixtures; its production transport remains absent until the exact firmware, controller effects and complete independent rollback are qualified.

The inspected stock `com.qinggan.app.vehiclesetting` manifest offers the package-scoped action `com.qinggan.app.vehiclesetting.dirveassistance` on its exported `CarSettingActivity`. The unusual spelling is the OEM interface name. The stock vehicle app also uses this route when directing the driver to NOA settings. Resolve it through the same enabled, exported, system-app and permission checks as other factory shortcuts; do not infer that an identically named action is present on every firmware. A launcher fallback can still open the original vehicle menu when no dedicated route is available.

The inspected factory settings APK is version 1.0, code 1, with SHA-256 `94477c6d17d7ccac9f6a4027e1defef835d5f59faae319b3baa0ef8be3acc423`. This identifies local source evidence, not an approved H97D firmware. The corresponding manufacturer interfaces still need validation against the signed APK installed in the vehicle.

## Settings and logs

Keep automation settings in validated, bounded local storage and include the supported configuration in the normal settings export. Runtime telemetry, command confirmation evidence and private credentials are not portable configuration. Import must preserve the existing archive rollback behavior and must not turn an imported observation into a fresh sample.

Diagnostic events should identify the feature, source, outcome, reason and monotonic observation time. Log denied admission, waiting for fresh feedback, suppressed duplicate edges, invalid conditions, persistence failures and ambiguous dispatch distinctly. Avoid per-frame logs, polling an unchanged screen, account contents, network credentials and uncontrolled payload dumps.

Physical H97D compatibility remains unverified until the missing evidence in the table is reviewed. Do not add a production approval based on a successful emulator test, a matching product name or a screenshot.
