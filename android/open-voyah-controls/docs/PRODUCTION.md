# Head unit qualification and recovery

Use a separate release and qualification record for each reviewed head unit build. The Android API level and product family identify candidate adapters. The full build fingerprint, ABI, OEM package certificates, and APK hashes identify a reviewed build. The shipped catalogs start empty until device evidence is available.

For VOYAH FREE 318 H97D, preview the cockpit layout at the central display's published 1920 × 720 resolution. Use the installed firmware's Android display density and system bar insets when available. The current emulator preview uses a 240 dpi Android setting. Confirm the layout, reachable shortcuts and reserved content area on the actual head unit. [FREE 318 central display specifications](https://myroncars.com/en/new-cars/voyah/free/318-4wd-discovery-edition)

## Prepare a signed release

Set the following variables in the local release environment. Keep the keystore and its passwords in the release secret store. The certificate fingerprint is public and must agree with the one pinned in the Windows installer.

| Variable | Value |
| --- | --- |
| `OPEN_VOYAH_RELEASE_KEYSTORE` | Path to the release keystore |
| `OPEN_VOYAH_RELEASE_STORE_PASSWORD` | Keystore password |
| `OPEN_VOYAH_RELEASE_KEY_ALIAS` | Private signing key alias |
| `OPEN_VOYAH_RELEASE_KEY_PASSWORD` | Signing key password |
| `OPEN_VOYAH_RELEASE_CERT_SHA256` | SHA-256 of the DER signing certificate, as 64 hexadecimal characters |

Build with `./gradlew :app:assembleRelease`. Gradle opens the keystore, verifies the certificate pin and private key, and uses that key to sign the release APK. Inspect the finished APK with the SDK's `apksigner verify --print-certs`. Build the Windows installer with the same Android certificate pin and use the team's independently provisioned Windows signing certificate for the executable. Verify the signed executable and retain the complete portable SDK tool directory.

Android requires a compatible signing certificate for an in-place update. To replace a debug installation, export the app settings and roll back the head unit from the PC. Use **Remove old app for signer migration** after verifying the export. This separate action removes the old package and its retained data. Install the signed release and import the settings. The export contains preferences and assignments. Android permission grants and privileged deployment state are device-managed.

The debug fixture switch `-Povc.emulatorFixture=true` is reserved for isolated Android 9 and 11 emulators. A normal debug APK is suitable for exercising Android UI, app launches, and local settings. Use a separately named fixture APK for privileged integration tests.

## Review a firmware candidate

Run the read-only evidence tool with an explicitly selected ADB device and a local Android SDK that includes Build Tools and Platform-Tools.

```powershell
./tools/read-firmware-evidence.ps1 -Serial <ADB-serial> -SdkRoot <Android-SDK>
```

The tool verifies the selected device identity across the read and reports the active OEM package hashes and signing certificates. The output includes candidate rows for review. It omits the device serial and removes its temporary APK copies. An unavailable OEM component needs a separate check on that firmware.

Use the narrower cockpit scope when reviewing the light feedback or factory seat handoff.

```powershell
./tools/read-firmware-evidence.ps1 -Scope Cockpit -Serial <ADB-serial> -SdkRoot <Android-SDK> -EventWindowSeconds 45
```

This scope records the installed Controls, CAN service, factory vehicle and factory vehicle settings packages. It includes the Android product and the optional factory product string. The `android_automotive` section records the Android API check, the advertised Automotive feature, the shared library declaration, `car_service` visibility to the selected shell and three effective permission checks for the verified Controls APK and foreground Android user. The official Android Automotive 13 emulator shows a running car service with no `android.car` shared-library declaration in Package Manager. `not_visible` describes what the shell can see, and `unknown` means the check could not be established. The reader events and independent lamp observations establish property availability for the app and data delivery.

The `android_automotive.headlight_properties` section reads the switch, headlamp and high-beam configurations independently through `car_service`, when visible to the selected shell. Each result includes the exact property ID, observed access and change mode when available. **Compatible** means the returned configuration matches the Controls reader's requirements. **Incompatible** means a configuration was identified outside those requirements. **Unavailable** means the shell returned no usable configuration for that property. **Not probed** means the service was unavailable to the diagnostic check. Compare these results with the Controls permissions and reader events to establish whether the app receives observations.

When `car_service` is visible, `android_automotive.seat_memory` reports the driver's seat area and the inclusive index ranges advertised for Save and Restore. The report reads the driver area from `INFO_DRIVER_SEAT` and the ranges from the two properties' configurations. Each field is `null` when the selected shell lacks a usable response. Compare the reported driver area with the ranges for that area and review the Controls permission separately. Qualification on the vehicle must establish the factory preset order and whether the positions include both exterior mirrors. [Driver seat location](https://developer.android.com/reference/android/car/VehiclePropertyIds#INFO_DRIVER_SEAT) · [Seat memory properties](https://developer.android.com/reference/android/car/VehiclePropertyIds#SEAT_MEMORY_SELECT)

The optional window captures selector feedback, Android Automotive reader outcomes and lamp observations, screen-power events, driver-memory guidance and explicit factory launch requests from the active Controls process. Controls package lookup and process ownership use the foreground Android user, including headless-system-user Automotive builds. The report discards events when its identity checks detect a user or process change. Verify the physical seat and mirror positions on the selected slot and observe a full sleep and wake cycle at the head unit.

Approve only packages whose actual classes, entry points, behavior, and recovery have been checked for this fingerprint. Record separately the evidence for privileged window management, profile operations, and each physical command. A package being present or an acknowledgment from a system service does not certify an actuator outcome.

Qualify a separate recovery procedure before approving OEM account or cluster writes. PC handler rollback covers the Controls deployment and its boot entry. Account records and instrument cluster files have their own backup and restore operations.

The optional `can-steering-terminal` and `can-steering-edges` operations run only with a pinned `com.qinggan.canbus.service` APK. Only one protocol runs at a time (see [Firmware: steering adapters](FIRMWARE.md#steering-and-display-adapters)); operation rows for both, or for a firmware without the service pin, are accepted as metadata. Qualification must establish the full physical-key path, duplicate delivery, sleep/wake and native fallback. The edge route owns an entire press only when both assignments pass preflight; late failures and combinations after capture are canceled without physical replay. A callback signature or service hash alone is insufficient. See [agent behavior and edge ownership](FRIDA_AGENTS.md).

The read-only evidence tool includes the CAN service and `com.qinggan.app.login`; an overseas login pin permits interface and bounded account-file observation, not account writes. It also records the declared tracking-provider owner and relevant manifest components. Any extra owner package is diagnostic evidence only and has no firmware catalog row. The portable account observation collects file metadata without reading or hashing the account record. The observed `/private/configs/token/accountInfo` file is outside the login package directory. It is only part of the account state: do not treat an app-data archive or this file alone as a complete rollback. No overseas switching/restore approval is shipped. See [account recovery boundaries](ACCOUNT_RECOVERY.md).

## Allowed actions and what still guards the root channel

Review used to gate features: without a row in `verified-operations.tsv` or `verified-commands.json` for the exact firmware an operation or vehicle command was refused. Now every operation, agent channel and vehicle voice command is **allowed by default**, and only the owner narrows it in **Settings → System → Allowed actions** (Unverified or Forbidden; see [Settings: allowed actions](SETTINGS.md#allowed-actions)). The review files keep their format and are optional metadata:

- `verified-operations.tsv` and `verified-commands.json` rows mark an action **Verified on this firmware** in Settings and pick the CAN protocol when both are allowed. A row for an operation this version does not know, a repeated row, both CAN protocols or a CAN row without the service pin no longer stops activation, on Android or in the Windows installer.
- The launcher agent accepts any vehicle command on a supported Voyah product (Android 9/11, arm64, a product listed in `Firmware.java`); the app does not send forbidden ones.

What stays, whatever the owner chooses:

- **Firmware identity and APK pins.** `verified-firmware.tsv` still names the exact fingerprint, SDK, ABI and product, and the signer and APK hash of every OEM package an agent hooks. Every root command re-checks them, so the root channel only ever targets this unit and these APKs. A package without a pin (for example the CAN service or the cluster on a firmware that lists neither) keeps its agent off; the action then answers that the APK pin is missing, not that it is forbidden.
- **Root channel and release checks.** The authenticated local broker, the signer of this app, Enforcing SELinux (or a Permissive kernel command line on a local-vehicle build) and user 0.
- **Supervisor integrity.** The sealed deployment hashes in `trust.tsv`, the signer and version floor of Controls, the injector hash, the recovery markers (`recovery.blocked`, `recovery.visual-pending`, `update.pending`) and the update pause.
- **Vehicle safety.** The Park and zero-speed check before every Park-only voice write (`MOTION_COMMANDS` lists the exceptions), the Park preflight before a T-Box restart, a system reboot and Driver A/B/C, request expiry, replay and rate limits in the agent.
- **Confirmations.** The in-app confirmations before a system reboot, a multimedia restart and a cluster write; an Unverified action says so there.

## Prepare the first launch

1. Select the intended head unit and the APK, then **Install**. It supports owner user 0, Android 9 or 11 on ARM64, firmware that permits `adb root`, SELinux Enforcing (or Permissive only when the firmware's own kernel command line sets `androidboot.selinux=permissive`), and a root-owned BusyBox with standalone loopback Telnet and `flock` support. The firmware must already invoke `/system/etc/init.logcat.sh` with root privileges. No Android `su` executable is used.
2. The installer verifies the APK and pinned runtime, requests ADB root, saves and verifies the original startup script and metadata on the PC, then prepares a writable system mount. If verified boot prevents the remount, it requests `disable-verity` and one restart. Identity is checked again after every reconnection; an unsupported firmware stops setup.
3. The installer preserves app data during a same-signer update, stages the injector, and installs its root-private startup scripts and per-installation key. Telnet listens only on `127.0.0.1:2323`. Client and server prove knowledge of the key using fresh nonces before any shell command. The key is available only to root and the installed app UID and is excluded from app backup.
4. The V3 boot entry starts the local channel independently of OEM handlers and retains the original logcat script. After microphone permission and Accessibility setup, a protected foreground activity verifies access from the app UID, loads the selected offline model, and starts reviewed Frida handlers. It waits for live workers, fresh readiness markers and the required protocol for every enabled channel. A command acknowledgment is never physical-action verification. When the activity answers before the agents report ready, the installer waits up to three minutes for them instead of repeating the check.
5. The installer then restarts the head unit, waits for the same hardware and build to boot, requests ADB root again and waits up to five minutes for the boot entry, an active root channel and, on reviewed firmware, a ready launcher agent. Only this result is reported as done.
6. An unreviewed build can complete local setup, but OEM actions remain unavailable. Missing permission, model failure, root failure and handler timeout produce distinct outcomes. The installer does not report vehicle readiness for these outcomes. Keep the PC backup. A cold boot and sleep/wake cycle still need qualification on the exact device.

The installer starts the access listener from its ADB root session before the Android first-run check. Cold-boot qualification separately records the OEM init service and its SELinux domain, then verifies authenticated access and the enabled handlers while SELinux remains Enforcing. A UID 0 result from the prepared session does not establish these boot-time permissions. **Collect logs** includes the available SELinux state, shell identity, labelled processes and startup-file metadata to support that review.

Setup stages a complete access generation before replacing an earlier one. A failed command or lost transport is not replayed automatically. The Windows recovery path restores recognized V1/V2/V3 entries without the APK, stops the owned listener and removes only Accessibility access added by setup. Other applications and accessibility services are preserved. A full rollback may still require a separate reboot for already changed OEM visuals.

Cold speech preparation requires 128 MiB of free internal storage before copying either pinned model. A completed model can reopen without this extraction reserve. A low-space failure leaves settings and existing models intact. Free space and retry setup. Repeating **Install** while its Android result window is already open starts a fresh request; checks are serialized and an older result cannot satisfy the new request.

### Updates and owner builds

An update of Controls applies without a restart. The receipt pins Controls by its signing certificate and a version floor, so the running supervisor keeps the activation when Android replaces the APK with one of the same signer that is not older, and the new app redeploys the agents that changed (see [Frida agents](FRIDA_AGENTS.md#target-crashes-restarts-and-revocation)). Any way of installing works: `adb install -r`, an update from inside Controls, or **Install** in the Windows installer, which sees `OVC_LIVE_UPDATE=yes` and `OVC_SEALED_VERSION` in the boot status, installs over the running agents and waits for them to report ready. Restarts remain only for what really needs one: a first setup (boot entry in `/system`, a system partition to prepare) and RRO overlays in `/vendor`.

The full cycle still runs when the deployment does not accept a live update: a receipt sealed by an older version of Controls (no `signer` row; the first update from it, once), a version below the floor, or an active block. Then the installer rolls back the running deployment, restarts the head unit when the rollback reports `OVC_ROLLBACK_VISUALS=reboot-required`, installs the new APK on the now inactive unit, rearms, repeats the access, boot entry and first-run steps and confirms the agents after a second restart. If the head unit revokes the activation during a live update after all, the installer continues with this cycle by itself.

An owner's own build (for example `-Povc.localVehicle=...`, which may be debuggable) installs in the same way once its signing certificate is trusted on the PC: either it matches `%LOCALAPPDATA%\OpenVoyahControlsInstaller\local-vehicle\signer.sha256`, or the installer shows the certificate's SHA-256 and the owner confirms it (stored in `trusted-signers.txt` beside it). The `verified-firmware.tsv` and `verified-operations.tsv` of that folder count as a reviewed profile for the exact unit, so rearm and the agent check run as for a release. Release APKs keep the pinned certificate. Android cannot update an installed app across certificates; the installer says so before any change and points to the signer migration in Maintenance.

Other apps (the YouTube web app, the owner's own apps) are installed on the installer's **Other apps** page. It refuses the Controls package, OEM packages and split APKs before the first install and never touches the Controls APK, runtime, deployment files or boot entry, so boot activation stays.

## Recovery without the PC

After the supervisor revokes activation (`recovery.blocked`, `recovery.visual-pending`, every `enabled` flag removed), Controls can re-arm it on the head unit itself, through the authenticated root channel, with `boot-start.sh --recover auto|manual`. `boot-start.sh` first checks both scripts against the receipt, then `supervise.sh --recover` decides:

| Mode | Who asks | Re-arms when |
| --- | --- | --- |
| `auto` | Controls' boot job after every boot (`bridge_reconnect`, `path=recover`) | The markers are from an earlier boot (the reboot restored the original visuals), `recovery.reason` was written by the supervisor for that revocation, no visual session or injector of this project is live, and the deployment verifies. |
| `manual` | The owner: Settings → System → About and restart → **Restore Open Voyah Controls**, behind a confirmation | The same, but also for a revocation of the current boot or one without a recorded reason, once no visual session is `uncertain` and no injector of this project is live. A `current` session of a target that kept running is adopted by the next attachment; an `uncertain` one needs a restart. Otherwise Controls asks for a system restart and re-arms by itself after the boot. |

"Verifies" is `check_runtime`, the same check a boot runs: the receipt's device row, the signer pin of Controls, the APK pins of every hooked OEM package, the Frida runtime hash, both scripts, every agent, the app's uid and root-channel key against the sealed lifetime, user 0, and no legacy deployment. On success it retires changed visual sessions, resets the retry and restart budgets, enables the channels listed in `recovery.reason` (without a list, every channel whose agent the receipt seals) and removes the markers; Controls then starts the workers as after a normal boot. The journal shows `activation_recovery mode=<auto|manual> outcome=recovered reason=<cause> channels=<…>`, and the main `worker.log` `Activation re-armed without the PC`.

Replies, as `OVC_RECOVER outcome=<o> reason=<r>`: `recovered`; `none` (nothing blocked); `reboot` with `visual` or `same-boot`; `waiting` with `locked` (user storage not unlocked yet; the boot job retries); `pc` with `deployment`, `lifetime`, `unrecorded`, `update`, `main-agent`, `marker` or `boot-id`.

What still needs the PC (**Install** in the Windows installer, which runs `rollback` → restart → `rearm`, access, boot entry, first-run check → restart by itself):

- a Controls APK with another signer or below the sealed version floor, any APK change under a receipt without the `signer` row, a changed Frida runtime, script or agent outside a deployment by the app: the receipt no longer verifies (`pc:deployment`). A same-signer update of Controls is not on this list: it never revokes;
- a reinstalled app, cleared app data or a new root-channel key (`pc:lifetime`);
- an OEM APK replaced by a firmware update, another fingerprint, or a receipt that fails its schema (`pc:deployment`);
- a block the supervisor did not record, such as a PC `rollback` or the app's own unconfirmed visual restore, which the boot job leaves alone (`pc:unrecorded`; Restore in Controls may lift the latter);
- a runtime update in progress (`pc:update`), a marker that is a symlink or has the wrong owner (`pc:marker`), or a missing main launcher agent (`pc:main-agent`);
- a revocation written by a supervisor older than this one (no `recovery.reason`): the boot job leaves it (`pc:unrecorded`), Restore in Controls lifts it once the deployment verifies.

A PC `rollback` also disables the root channel, so Controls cannot undo it.

While a revocation of the current boot waits for the next restart, Controls says so in red in Settings → System → About and restart (with **Restart the system** beside it) and above the connection status in Settings → System → Connection and Settings → Buttons → Factory panel: Open Voyah Controls is paused for safety and restores itself after the next system restart. The check reads the recovery markers through the root channel when those pages open and after **Restore**.

## Validate rollback before driving

Use the PC recovery path before removing the APK. The PC must be able to stop our handlers and disable their startup even if the app is absent. Verify the restored OEM boot file against the original backup on the same firmware. If the device has moved to a different build or the current file is unrecognized, retain the backup and use the reviewed recovery procedure for that build.

| Test on the reviewed head unit | Acceptance evidence |
| --- | --- |
| Restart, cold boot, sleep and wake, package replacement | The expected handlers come up once, use the current package identities, and report capability-specific readiness. |
| Stop or remove Controls, then reboot | No Controls handlers attach again. Stock actions and visuals work. The PC reports the boot file and backup status. |
| Lost connection and interrupted staging | Retrying uses a verified device and consistent artifacts. A stale lock does not prevent recovery. Repeated process crashes cause the affected handler to pause. |
| Target crash and revocation | Killing the launcher after a healthy minute brings the launcher agent back within about 10 s with `Target restarted; attaching again`. After a forced revocation with unchanged files, the next boot re-arms by itself (`activation_recovery mode=auto outcome=recovered`); after an `adb install` it reports `pc:deployment`. |
| Central and passenger buttons | A tap opens the assigned app on the intended screen. A hold calls the stock action once. Missing or failed targets return to stock behavior. Home, climate, and surround view remain accessible. |
| Installed apps and permissions | Native installation confirms the selected package. For an app installed on top of the firmware by any installer, a runtime grant or revocation and a special access change match Android's recorded state before and after reboot. **Close app** leaves no process of the app. Firmware and maker apps, the launcher and the keyboard offer neither switches nor **Close app**, and an app with an enabled accessibility or notification service offers no **Close app**. |
| Windows and DPI | Running and newly launched apps leave the expected left strip on the central screen. Display defaults, per-app choices, and recovery preserve the intended scope. |
| Physical actions | Test the approved command individually on a parked vehicle. Compare a reported result with fresh vehicle telemetry. Check silence, incorrect recognition, loss of feedback, and interruption during motion before enabling the command. |

Run failure cases on an isolated bench first. Record the exact firmware and artifact versions, which checks passed, and any operation that remains disabled.
