# Left area of the driver display

## Music

Open **Settings → Home screen and apps → Factory home screen → Left area of the driver display**. Enable **Voyah Music and Voyah Radio on the instrument cluster** and **Large player view**. Start either companion player. The large view shows the source, title, artwork, artist when supplied, and progress when the session publishes a track duration.

The left area is an Android media surface on the display in front of the driver. The original controls and steering media keys continue to receive their existing input. The visual layer adds no touch input. Turning the setting off returns to the stock card. Losing the supported media source, title, or visible card also returns to the stock view.

The existing cluster-card channel must be permitted and its OEM instrument-card package verified for the exact firmware. The large view also checks Android 11, the active `Cluster-Media-Display`, the attached package-owned music window, and a usable measured viewport. It occupies that view's bounds. These checks leave navigation, factory instrumentation and QNX functions in their existing processes. Its Android drawing overlay is removed on orderly stop. A short drawing lease fades stale content after an unexpected loss of the worker.

The agent fixture exercises the display, viewport, media and restoration rules. The left media viewport and layer composition need confirmation on the intended head unit. The workflow uses the existing media sessions and the measured media view.

## Compact navigation card

Open **Settings → Home screen and apps → Factory home screen → Left area of the driver display** and turn on **Compact navigation card**. In **Settings → Windows and screens → Virtual display and cluster**, enable **Navigation feed**. The compact card is off by default and has its own switch.

During live guidance, the card shows the next road and maneuver distance when supplied. With just a distance it shows the distance. With only the current road it labels that road accordingly. The source is the existing OEM cluster `updateNaviInfo` feed with guide state `GUIDING`. Availability depends on the navigation app delivering live guidance to this OEM feed.

The drawing layer uses the same visible OEM music view as **Large player view**. The 288 dp wide rounded card is centered in that view. Its height is 112 dp at normal text scale and grows to 142 dp at 150%. The view must be fully visible in `Cluster-Media-Display` and measure 320–1280 dp wide and 160–800 dp tall. Android 11, a matching exact-firmware record, APK pins for both OEM packages and permission for both agent channels are required.

While guidance is fresh it takes visual precedence over music. Media focus, progress and steering-button routing continue through the original card. When guidance ends, the source stops updating for five seconds, the switch is turned off, or the worker stops, the layer gives way to the current player view or the stock card. A factory radio or empty view without the measured music host keeps its original layout. The display swipe continues to use the separate navigation display.

### Verification

[Off (English)](screenshots/driver-navigation-setting-off.png) · [On (English)](screenshots/driver-navigation-setting-on-en.png) · [On (Russian)](screenshots/driver-navigation-setting-on-ru.png) · [On (Kazakh)](screenshots/driver-navigation-setting-on-kk.png)

```powershell
npm.cmd test
python tools/test_cluster_transaction.py
.\gradlew.bat --offline :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb -s <emulator-serial> shell am instrument -w -e class "org.openvoyah.controls.ComposeScreensTest#testCompactDriverNavigationSwitchAndCapture,org.openvoyah.controls.SettingsStoreTest,org.openvoyah.controls.DesktopRouteCardTest" org.openvoyah.controls.test/android.test.InstrumentationTestRunner
```

The agent fixture checks the 574 × 464 px host at density 1, including a centered 288 × 112 px card at `(143, 176)`. It checks disabled guidance, stale and simulated routes, clipped and lost hosts, media focus changes, and orderly restore. The settings screenshots are from the real app on an Android 13 x86_64 emulator at 1920 × 720 and 240 dpi. On the exact head unit, confirm the visible music host and composition for each OEM theme and source, actual `GUIDING` callback cadence and route fields, fallback after route loss and both agent stops, and coexistence with gauges, warnings, steering media keys and the navigation-display swipe.

## Yandex Navigator window

On Android 11, open **Settings → Home screen and apps → Factory home screen → Left area of the driver display**. With the stock music card visible on the driver display and Yandex Navigator closed on other screens, select **Show Yandex Navigator on the driver display**. Use **Restore media card** to close it. The window shows the app's own Android Activity and accepts no touch from Controls.

The existing authorized cluster-card worker owns the host view. It uses the visible stock music view's measured rectangle on the active `Cluster-Media-Display` and requires Android to permit the OEM process to create a private trusted display and start Navigator on it. The app keeps the stock card visible while awaiting a Navigator task on that display and a frame from the surface. An existing Navigator task is left in place.

For the first check on the head unit, compare the stock card and Navigator with the same theme. Verify that the entire map stays inside the left area and that **Restore media card** releases the child display. Repeat with Navigator already open on the central display, then test display loss and sleep/wake. The mode closes when it detects a changed or lost media surface, a lost Navigator task, or sleep, and requires a new explicit request after waking. If Android refuses the launch, record the status and inspect the existing cluster worker log.
