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

## Keep setup steps separate

1. Select the intended head unit and compare the read-only device details with the reviewed build. Confirm stable power, USB recovery access, an owner-authorized local privilege broker, and SELinux Enforcing.
2. Install the signed APK. Stage the pinned ARM64 runtime in the installer as a separate step. Verify device identity and the staged runtime before activation.
3. Open Controls, choose a narrow set of launcher assignments, and connect the reviewed handlers. Check panel, window, and voice readiness independently.
4. For firmware that already invokes the OEM logcat script, check its state and export the verified original and metadata to the PC. Install the optional entry only after the worker is ready. Keep the portable support scripts and the verified backup together for recovery.
5. After a cold boot and a sleep/wake cycle, verify that the expected version starts once and that the original controls still work.

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
