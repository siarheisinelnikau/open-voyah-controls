# Head unit qualification and recovery

Use a separate release and qualification record for each reviewed head unit build. The Android API level and product family identify candidate adapters. The full build fingerprint, ABI, OEM package certificates, and APK hashes identify a reviewed build. The shipped catalogs start empty until device evidence is available.

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

Approve only packages whose actual classes, entry points, behavior, and recovery have been checked for this fingerprint. Record separately the evidence for privileged window management, profile operations, and each physical command. A package being present or an acknowledgment from a system service does not certify an actuator outcome.

Qualify a separate recovery procedure before approving OEM account or cluster writes. PC handler rollback covers the Controls deployment and its boot entry. Account records and instrument cluster files have their own backup and restore operations.

The optional `can-steering-terminal` and `can-steering-edges` operations require a pinned `com.qinggan.canbus.service` APK. Both Android and Windows reject a missing service pin or simultaneous selection of both protocols. Qualification must establish the full physical-key path, duplicate delivery, sleep/wake and native fallback. The edge route owns an entire press only when both assignments pass preflight; late failures and combinations after capture are canceled without physical replay. A callback signature or service hash alone is insufficient. See [agent behavior and edge ownership](FRIDA_AGENTS.md).

The read-only evidence tool includes the CAN service and `com.qinggan.app.login`; an overseas login pin permits interface and bounded account-file observation, not account writes. It also records the declared tracking-provider owner and relevant manifest components. Any extra owner package is diagnostic evidence only and has no firmware catalog row. The portable account observation collects file metadata without reading or hashing the account record. The observed `/private/configs/token/accountInfo` file is outside the login package directory. It is only part of the account state: do not treat an app-data archive or this file alone as a complete rollback. No overseas switching/restore approval is shipped. See [account recovery boundaries](ACCOUNT_RECOVERY.md).

## Prepare the first launch

1. Select the intended head unit. **Prepare and install** supports owner user 0, Android 9 or 11 on ARM64, firmware that permits `adb root`, SELinux Enforcing, and a root-owned BusyBox with standalone loopback Telnet and `flock` support. The firmware must already invoke `/system/etc/init.logcat.sh` with root privileges. No Android `su` executable is used.
2. The installer verifies the release APK and pinned runtime, requests ADB root, saves and verifies the original startup script and metadata on the PC, then prepares a writable system mount. If verified boot prevents the remount, it requests `disable-verity` and one restart. Identity is checked again after reconnecting; an unsupported firmware stops setup.
3. The installer preserves app data during a same-signer update, stages the injector, and installs its root-private startup scripts and per-installation key. Telnet listens only on `127.0.0.1:2323`. Client and server prove knowledge of the key using fresh nonces before any shell command. The key is available only to root and the installed app UID and is excluded from app backup.
4. The V3 boot entry starts the local channel independently of OEM handlers and retains the original logcat script. After microphone permission and Accessibility setup, a protected foreground activity verifies access from the app UID, loads the selected offline model, and starts reviewed Frida handlers. It waits for live workers, fresh readiness markers and the required protocol for every enabled channel. A command acknowledgment is never physical-action verification.
5. An unreviewed build can complete local setup, but OEM actions remain unavailable. Missing permission, model failure, root failure and handler timeout produce distinct outcomes. The installer does not report vehicle readiness for these outcomes. Keep the PC backup. A cold boot and sleep/wake cycle still need qualification on the exact device.

The installer starts the access listener from its ADB root session before the Android first-run check. Cold-boot qualification separately records the OEM init service and its SELinux domain, then verifies authenticated access and the enabled handlers while SELinux remains Enforcing. A UID 0 result from the prepared session does not establish these boot-time permissions. **Collect logs** includes the available SELinux state, shell identity, labelled processes and startup-file metadata to support that review.

Setup stages a complete access generation before replacing an earlier one. A failed command or lost transport is not replayed automatically. The Windows recovery path restores recognized V1/V2/V3 entries without the APK, stops the owned listener and removes only Accessibility access added by setup. Other applications and accessibility services are preserved. A full rollback may still require a separate reboot for already changed OEM visuals.

Cold speech preparation requires 128 MiB of free internal storage before copying either pinned model. A completed model can reopen without this extraction reserve. A low-space failure leaves settings and existing models intact. Free space and retry setup. Repeating **Prepare and install** while its Android result window is already open starts a fresh request; checks are serialized and an older result cannot satisfy the new request.

## Validate rollback before driving

Use the PC recovery path before removing the APK. The PC must be able to stop our handlers and disable their startup even if the app is absent. Verify the restored OEM boot file against the original backup on the same firmware. If the device has moved to a different build or the current file is unrecognized, retain the backup and use the reviewed recovery procedure for that build.

| Test on the reviewed head unit | Acceptance evidence |
| --- | --- |
| Restart, cold boot, sleep and wake, package replacement | The expected handlers come up once, use the current package identities, and report capability-specific readiness. |
| Stop or remove Controls, then reboot | No Controls handlers attach again. Stock actions and visuals work. The PC reports the boot file and backup status. |
| Lost connection and interrupted staging | Retrying uses a verified device and consistent artifacts. A stale lock does not prevent recovery. Repeated process crashes cause the affected handler to pause. |
| Central and passenger buttons | A tap opens the assigned app on the intended screen. A hold calls the stock action once. Missing or failed targets return to stock behavior. Home, climate, and surround view remain accessible. |
| Installed apps and permissions | Native installation confirms the selected package. A runtime grant or revocation matches Android's recorded state before and after reboot. An app installed outside Controls uses Android's settings. |
| Windows and DPI | Running and newly launched apps leave the expected left strip on the central screen. Display defaults, per-app choices, and recovery preserve the intended scope. |
| Physical actions | Test the approved command individually on a parked vehicle. Compare a reported result with fresh vehicle telemetry. Check silence, incorrect recognition, loss of feedback, and interruption during motion before enabling the command. |

Run failure cases on an isolated bench first. Record the exact firmware and artifact versions, which checks passed, and any operation that remains disabled.
