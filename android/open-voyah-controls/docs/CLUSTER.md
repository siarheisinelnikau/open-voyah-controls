# Instrument cluster localization

The app installs a Russian translation package for the QNX instrument cluster. The firmware identifier must match exactly.

| Supported firmware identifier |
| --- |
| `H97CSA8155-DAILY-20260104134234-USR` |
| `H97CSA8155-DAILY-20260530180446-USR` |

A package replaces the `gauge`, `light`, and `point` binaries and the `point_normal.kzb` theme. The app checks each file's size and SHA-256 against the [profile catalog](../app/src/main/assets/cluster/profiles.json) before allowing it to be installed.

## Prepare a package

Place the required local firmware files under the names listed in the profile catalog. From the Android directory, run:

```powershell
python tools/build_cluster_packs.py path/to/local/cluster-files
```

The tool writes the packages and their checksums to `dist/`.

## Install and recover

Open **Firmware and OEM profiles → Instrument cluster localization**.

1. Check the exact firmware and recovery status.
2. Import the ZIP for that firmware.
3. Save a backup. The app retains one copy on the cluster and another in Android storage.
4. Export the backup ZIP and save it outside the vehicle.
5. Start installation while parked with continuous power.

The installer verifies both backups before writing, stages all four files before the first replacement, and records progress in a recovery journal. If the operation is interrupted, check the status and use **Restore saved cluster files**. Recovery preserves the saved copy. An exported backup ZIP is intended for manual recovery if the cluster copy is lost.

The Android side requires a reviewed head unit and the existing owner-authorized local privilege broker. The cluster connection and each write require separate qualification for that firmware. Check the journal after a failed or interrupted connection. Reboot is a separate operator decision. See [Head unit qualification and recovery](PRODUCTION.md).

## Screens and the media card

The cluster is the head unit's display 2 (`local:4`, 1920 × 720). `com.qinggan.cluster` keeps `ClusterActivity` on it and draws the gauges' companion content into three virtual displays of its own, which its fragments show as surfaces: `Cluster-Media-Display`, `Cluster-Navi-Display` and `Cluster-Black-Display` (Android displays 5, 6 and 4 on the reviewed unit; virtual, so `screencap -d`, which takes physical display ids on Android 11, cannot capture them). The gauges themselves are QNX content: a capture of display 2 shows only the Android layers, such as the media card, not the gauges. Read it with `adb shell screencap -p -d 4` (SurfaceFlinger id) and the activities with `dumpsys activity activities`.

- **Media display.** `com.qinggan.instrumentcard/.ScreenActivity` (category `qg.car.cluster.MEDIA`) draws the media card at the left of the cluster. It follows media audio focus and can show Voyah Music and Voyah Radio. The optional compact Controls navigation card draws route guidance inside the measured music view. See [Left area of the driver display](DRIVER_DISPLAY.md).
- **Navigation display.** Baidu's `InstrumentClusterActivity`, or an app the window swipe moved there (Yandex Navigator on the car).
- **No track for the QNX side.** Controls had a switch, **Show current track on the instrument cluster**, that sent the playing session to `InstrumentClusterManager.updateMediaSrcInfo` as `MediaSrcType.Other`. It never showed anything and was removed (2026-11-25). Every Voyah model (`f6801f` … `f6811`) runs `VoyahInstrumentCluster`, whose `updateMediaSrcInfo` forwards only FM, AM, network radio and Ximalaya sources while the factory radio (`com.qinggan.app.radio`) holds the focus flag, and USB, SD, A2DP and network music sources while the factory music app (`com.qinggan.app.music`) holds it; any other type is dropped, a third-party app never sets the flag (it keeps the factory app heard last, none after a boot), and `GeneralInstrumentCluster` forwards nothing. No factory app of this firmware calls that service API at all: the media center, the music, radio and launcher apps send their media to the CAN service (`CanBusManager.updateMediaSrcInfo`), and the cluster's media page is the Android card above. Sending another type would have been an untested write to the QNX cluster with a focus flag Controls cannot set, and the switch also needed the cluster package pinned (`navigationReviewed`), which the owner's unit does not pin. Archives keep `display.clusterMedia` as `false` for older versions and accept it on import.

## Local checks

Run `python tools/test_cluster_transaction.py` to exercise the shell transaction in a temporary filesystem. The Android `ClusterTest` covers the catalog, ZIP validation, checksums, and transport. Tests that import real packages require the two local ZIPs in the app-specific files directory of an isolated emulator.
