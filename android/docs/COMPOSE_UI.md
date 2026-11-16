# Compose routes and motion

Normal app screens share `MainActivity` and use Jetpack Compose. The root destination is the app grid: installed apps and Controls shortcuts appear directly, without a Back button, sidebar or page header. Settings, Buttons, voice settings, display, vehicle shortcuts, firmware maintenance, cluster maintenance and updates are destinations in the same host.

| Host | Responsibility |
| --- | --- |
| `MainActivity` | App grid, settings routes, app settings, assignments, permissions and maintenance workflows |
| `QuickVoiceActivity` | Foreground voice popup, recognition state and text entry |
| `SetupActivity` | Installer/first-run handshake, progress, result and recovery actions |
| Private display `Presentation` | Media and navigation content in `DisplayPreviewScreen` |

`AppsActivity`, `VoiceActivity`, `DisplayActivity`, `VehicleSettingsActivity`, `FirmwareActivity`, `ClusterActivity` and `UpdatesActivity` remain compatibility aliases to `MainActivity`. Their component names select the corresponding route so installed shortcuts and saved assignments remain usable. `PanelVoiceActivity` remains an exported alias to the separate voice popup. Setup retains its permission-protected Android entry point.

Launching Controls or using the explicit app-grid entry returns to the root grid. With reviewed panel assignments enabled, a short press on the central lower-left menu button opens this grid by default; holding the button keeps the native launcher action. Detail destinations have Back navigation within the shared host. Android system screens and the foreground voice popup remain separate boundaries.

Android owns runtime permission dialogs, file selection, package-install approval, system settings, keyboard and toasts. The display destination embeds a native `SurfaceView` for the virtual display. Its `Presentation` and app dialog windows host `ComposeView` content.

## State and responsibilities

The existing Gradle modules retain logical MVVM and Clean Architecture boundaries. `core` contains Android-free command rules, button/session logic and ports. App `data` adapters own persistence, document serialization, Frida transport and privileged integration. Presentation ViewModels expose immutable state through StateFlow; Compose collects it using the destination lifecycle and handles local view state. Platform adapters and Android entry points handle permissions, intents, media, input and other operating-system events.

Navigation state belongs to `ControlsNavigationViewModel` and is saved across host recreation. Each animated destination receives its own lifecycle: an outgoing route is capped at `CREATED`, so it cannot consume new UI effects while its last frame exits. Route-owned dialogs and preview resources close when their owner stops or leaves. Confirmed maintenance work has an independent lifetime; navigating away does not cancel an in-progress firmware or cluster transaction. Firmware and cluster maintenance use separate ViewModel keys in the shared host.

## Motion behavior

The shared palette uses neutral graphite surfaces, saturated blue actions, cool secondary text and restrained orange voice accents. Static cached lighting gives the background depth; it does not animate. App icons and labels have no background cards. Controls shortcuts and section icons use unboxed line artwork. Error feedback retains a separate coral role.

- Route navigation uses a small directional slide and crossfade. The app grid opens directly; detail navigation does not create another normal-screen Activity.
- Shared actions, navigation rows and app tiles use a 120 ms press response with a restrained scale change and surface feedback.
- Sections resize over 180 ms when their content changes. Status and result changes use short crossfades; first-run actions expand when available.
- App and command lists animate placement and appearance when their data changes. Assignment dialogs animate transitions between their steps.
- Activity and dialog windows have finite entry/exit transitions. The voice popup also animates recognition-state changes, and the virtual display crossfades changed media/navigation content.
- Compose honors Android's animator duration scale, including zero. Window transitions honor the corresponding system window/transition scales. There are no permanent decorative animation loops.

State collection follows the destination lifecycle. Settings snapshots copy mutable preferences into immutable presentation state. Closing the display route releases its presentation and virtual display; inactive routes must not retain a continuous draw loop.

## Verification scope

Routing tests cover direct app-grid entry, internal Back, compatibility aliases, dialogs and host recreation. Document and permission returns are guarded by route lifecycle; the voice model also tests document-return sequencing. Compose checks inspect the actual app content tree and save real screenshots on explicitly isolated emulators. Android-owned permission and installation windows are separate from app content.

Build/lint checks, screenshots and frame measurements answer different questions. Screenshots establish layout; interaction tests establish routing; bounded idle, hidden and animation-disabled measurements establish whether rendering settles on the tested emulator. They do not certify the frame rate or OEM behavior on a vehicle. Validation records apply to their listed revisions and completed checks; a source migration alone does not establish a new test result.
