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
| 1 | `screen_up_home_icon`, `screen_up_icon_1`–`screen_up_icon_4` | Passenger buttons 1–5 |

Short presses run the assignment on the selected display. Long presses, empty assignments, and failed launches use the factory action. The center Home button keeps its factory behavior. Launcher button assignments, voice control, and translation recover independently.

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
