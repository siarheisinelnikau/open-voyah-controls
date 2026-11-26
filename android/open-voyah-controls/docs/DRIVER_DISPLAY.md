# Left area of the driver display

## Music

Open **Settings → Home screen and apps → Factory home screen → Left area of the driver display**. Enable **Voyah Music and Voyah Radio on the instrument cluster** and **Large player view**. Start either companion player. The large view shows the source, title, artwork, artist when supplied, and progress when the session publishes a track duration.

The left area is an Android media surface on the display in front of the driver. The original controls and steering media keys continue to receive their existing input. The visual layer adds no touch input. Turning the setting off returns to the stock card. Losing the supported media source, title, or visible card also returns to the stock view.

The existing cluster-card channel must be permitted and its OEM instrument-card package verified for the exact firmware. The large view also checks Android 11, the active `Cluster-Media-Display`, the attached package-owned music window, and a usable measured viewport. It occupies that view's bounds. These checks leave navigation, factory instrumentation and QNX functions in their existing processes. Its Android drawing overlay is removed on orderly stop. A short drawing lease fades stale content after an unexpected loss of the worker.

The agent fixture exercises the display, viewport, media and restoration rules. The stock and large Voyah Music views were checked on H97C while parked. The workflow uses the existing media sessions and the measured media view.

## Compact navigation card

Open **Settings → Home screen and apps → Factory home screen → Left area of the driver display** and turn on **Compact navigation card**. For Yandex Navigator, allow Android notification access in **Settings → Sound and media → Media access** and enable background guidance notifications in Navigator. The card shows the turn arrow, distance and street while Navigator publishes an automotive maneuver notification. Notification layouts and fields were inspected in Navigator 31.1.5. An ongoing notice that only says navigation is running does not provide a maneuver.

The OEM navigation feed remains available through **Settings → Windows and screens → Virtual display and cluster → Navigation feed**. During live OEM guidance, the card shows the next road and maneuver distance when supplied. With just a distance it shows the distance. With only the current road it labels that road accordingly.

The card is centered in the visible OEM media area. At normal text scale the OEM feed uses a 288 × 112 dp card. With a Yandex arrow the card uses up to 380 dp of width and allows two street-name lines where there is enough height. Both the compact navigation card and the large player view are off by default and can be enabled independently. The view must be fully visible in `Cluster-Media-Display` and measure 320–1280 dp wide and 160–800 dp tall. Android 11 and the reviewed instrument-card APK and agent are required. The OEM navigation feed also requires the reviewed cluster APK and its allowed agent.

While guidance is fresh it takes visual precedence over music. Media focus, progress and steering-button routing continue through the original card. When guidance ends, the switch is turned off or the drawing lease expires, the layer gives way to the current player view or the stock card. OEM observations expire after five seconds. Yandex maneuvers clear when their notification is removed and expire after thirty seconds without a notification update. Yandex maneuvers can use the measured OEM media panel while no music view is present. The display swipe continues to use the separate navigation display.

### Verification

[Off (English)](screenshots/driver-navigation-setting-off.png) · [On (English)](screenshots/driver-navigation-setting-on-en.png) · [On (Russian)](screenshots/driver-navigation-setting-on-ru.png) · [On (Kazakh)](screenshots/driver-navigation-setting-on-kk.png)

```powershell
npm.cmd test
python tools/test_cluster_transaction.py
.\gradlew.bat --offline :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb -s <emulator-serial> shell am instrument -w -e class "org.openvoyah.controls.ComposeScreensTest#testCompactDriverNavigationSwitchAndCapture,org.openvoyah.controls.SettingsStoreTest,org.openvoyah.controls.DesktopRouteCardTest" org.openvoyah.controls.test/android.test.InstrumentationTestRunner
```

The agent fixture checks the 574 × 464 px host at density 1, including a centered 288 × 112 px card at `(143, 176)`. It checks disabled guidance, stale and simulated routes, clipped and lost hosts, media focus changes, and orderly restore. The settings screenshots are from the real app on an Android 13 x86_64 emulator at 1920 × 720 and 240 dpi. On H97C with Navigator 31.1.5, a real route was started while parked. The cluster showed an arrow and distance, with a street when Navigator supplied it. Turning the compact card off during guidance and ending the route both restored the player. Remaining hardware checks cover additional OEM themes and sources, OEM `GUIDING` callback cadence and route fields, fallback after route loss and both agent stops, and coexistence with gauges, warnings, steering media keys and the navigation-display swipe.

## Yandex Navigator window

On Android 11, open **Settings → Home screen and apps → Factory home screen → Left area of the driver display**. With the stock music card visible on the driver display and Yandex Navigator closed on other screens, select **Show Yandex Navigator on the driver display**. Use **Restore media card** to close it. The window shows the app's own Android Activity and accepts no touch from Controls.

The existing authorized cluster-card worker owns the host view. It uses the visible stock music view's measured rectangle on the active `Cluster-Media-Display` and requires Android to permit the OEM process to create a private trusted display and start Navigator on it. The app keeps the stock card visible while awaiting a Navigator task on that display and a frame from the surface. An existing Navigator task is left in place.

For the first check on the head unit, compare the stock card and Navigator with the same theme. Verify that the entire map stays inside the left area and that **Restore media card** releases the child display. Repeat with Navigator already open on the central display, then test display loss and sleep/wake. The mode closes when it detects a changed or lost media surface, a lost Navigator task, or sleep, and requires a new explicit request after waking. If Android refuses the launch, record the status and inspect the existing cluster worker log.
