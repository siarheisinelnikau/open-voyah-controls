# Compose screens and motion

All app-owned Android screen and dialog content uses Jetpack Compose. This includes the home Buttons and Settings pages and the content rendered into the private virtual display. Native Android hosts remain at the OS boundary.

| Entry point | App-owned content |
| --- | --- |
| `MainActivity` | Quick access, Buttons, Settings, assignment and timing dialogs |
| `AppsActivity` | Installed apps, per-app settings, permissions and local APK installation state |
| `VoiceActivity` | Voice settings, command assignments, contacts and optional online interpretation |
| `QuickVoiceActivity` | Foreground voice popup, recognition state and text entry |
| `SetupActivity` | First-run progress, result and recovery actions |
| `FirmwareActivity` | Compatibility, profile and translation controls |
| `VehicleSettingsActivity` | Factory settings shortcuts and unavailable-state guidance |
| `ClusterActivity` | Package inspection, backup and restoration workflow |
| `DisplayActivity` | Display preferences, app defaults and preview controls |
| `UpdatesActivity` | Release checks, startup preference, download and installation recovery |
| Private display `Presentation` | Media and navigation content in `DisplayPreviewScreen` |

`PanelVoiceActivity` is an exported activity alias for `QuickVoiceActivity`, not a separate renderer. Android continues to own permission dialogs, file selection, package installation, its settings screens, keyboard and toasts. `DisplayActivity` embeds a native `SurfaceView` because Android's virtual display needs its surface. The presentation and `ComponentDialog` windows host `ComposeView`; their content is Compose. These exceptions do not contain legacy app-owned form layouts.

## Motion behavior

- Home navigation uses a small directional slide and crossfade, with an animated selection highlight.
- Shared actions, navigation rows and app tiles use a 120 ms press response with a restrained scale change and surface feedback.
- Sections resize over 180 ms when their content changes. Status and result changes use short crossfades; first-run actions expand when available.
- App and command lists animate placement and appearance when their data changes. Assignment dialogs animate transitions between their steps.
- Activity and dialog windows have finite entry/exit transitions. The voice popup also animates recognition-state changes, and the virtual display crossfades changed media/navigation content.
- Compose honors Android's animator duration scale, including zero. Window transitions honor the corresponding system window/transition scales. There are no permanent decorative animation loops.

State collection follows the screen lifecycle. Home snapshots copy mutable settings into immutable presentation state. Closing the display page releases its presentation and virtual display; hidden pages must not retain a continuous draw loop.

## Verification scope

`ComposeScreensTest` opens all ten activity routes on an explicitly isolated emulator and rejects legacy `TextView`/widget content under each app content root. It waits for a usable virtual display and walks the actual accessibility tree through home tabs, steering and timing dialogs, Back, and activity recreation. The test saves real screenshots. Android 9 needs interactive-window enumeration after a native dialog closes; the test uses that OS capability rather than cached text lookup.

Build/lint checks, screenshots and frame measurements answer different questions. Screenshots establish layout; interaction tests establish routing; bounded idle, hidden and animation-disabled measurements establish whether rendering settles on the tested emulator. They do not certify the frame rate or OEM behavior on a vehicle. See the corresponding validation record for exact versions and completed checks.
