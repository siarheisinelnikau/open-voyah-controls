# Compose routes and motion

Normal app screens share `MainActivity` and use Jetpack Compose. The root destination is the app grid: installed apps and Controls shortcuts appear directly, without a Back button, sidebar or page header. Settings, Buttons, voice settings, display, vehicle shortcuts, firmware maintenance, cluster maintenance and updates are destinations in the same host.

| Host | Responsibility |
| --- | --- |
| `MainActivity` | App grid, settings routes, app settings, assignments, permissions and maintenance workflows |
| `LaunchBrandActivity` | One-shot launcher animation in its own task |
| `QuickVoiceActivity` | Foreground voice popup, recognition state and text entry |
| `SetupActivity` | Installer/first-run handshake, progress, result and recovery actions |
| Private display `Presentation` | Media and navigation content in `DisplayPreviewScreen` |

Normal screens use explicit routes in `MainActivity`. The voice popup and installer handshake require a privileged permission for external callers. The application can open its own screens directly.

Tap the Android launcher icon to open Controls Settings. With reviewed panel assignments enabled, a short press on the central lower-left menu button opens the root app grid. The first tile, **Open Voyah Controls**, opens Settings on every desktop. Holding the panel button keeps the native launcher action. When extra desktops are enabled, fresh grid entries open the saved default desktop. Detail destinations have Back navigation within the shared host.

Android owns runtime permission dialogs, file selection, package-install approval, system settings, keyboard and toasts. The display destination embeds a native `SurfaceView` for the virtual display. Its `Presentation` and app dialog windows host `ComposeView` content.

## State and responsibilities

The existing Gradle modules retain logical MVVM and Clean Architecture boundaries. `core` contains Android-free command rules, button/session logic and ports. App `data` adapters own persistence, document serialization, Frida transport and privileged integration. Presentation ViewModels expose immutable state through StateFlow; Compose collects it using the destination lifecycle and handles local view state. Platform adapters and Android entry points handle permissions, intents, media, input and other operating-system events.

`ControlsNavigationViewModel` publishes the route stack, entry revision, and motion direction together. The stack and entry revision survive host recreation. Each animated destination receives its own lifecycle. The outgoing route drops to `CREATED`, leaves the accessibility tree, and stops accepting input during its exit. Route-owned dialogs and preview resources close when their owner stops or leaves. Confirmed maintenance work continues independently of navigation. Firmware and cluster maintenance use separate ViewModel keys in the shared host.

## Motion behavior

The shared palette uses neutral graphite surfaces, saturated blue actions, cool secondary text and restrained orange voice accents. Static cached lighting gives the background depth; it does not animate. App icons and labels have no background cards. Controls shortcuts and section icons use unboxed line artwork. Error feedback retains a separate coral role.

**Settings → Appearance** offers **Dark** and **Daytime** color themes. **High contrast** adjusts text, actions and outlines in either theme. **Text size** scales Compose typography together with Android's font size. **Spacing** adjusts page and card padding while keeping touch targets at their usual size. **Corner style** changes card, button and dialog shapes. **Start screen** selects the app grid, vehicle settings or general settings for the **Open Controls** shortcut shown when you hold the Android launcher icon. The app grid is the default for that shortcut. When a routine selects a theme for the session, dialogs and the voice popup follow it together with the screens, although they are hosted in their own windows. Running text never goes below 14 sp: the cockpit type scale defines every Material role that a screen uses, including field labels and card captions.

- Forward and Back use short, mirrored slides with a crossfade. External entries open their destination directly while Android handles the task transition. **Settings → Appearance → Animate screen transitions** controls in-app navigation and is enabled by default. **Transition speed** selects Short, Standard or Smooth and keeps the chosen speed if transitions are disabled. Navigation to or from the display screen switches immediately because its native preview surface cannot blend into a Compose transition on Android 9 and 11.
- A newly opened page lets its blocks arrive in order: each rises and fades in shortly after its neighbour, and the page settles within about half a second at the chosen **Transition speed**. A page you return to, a page redrawn after a language or theme change and the virtual display appear at once. The cascade follows **Animate screen transitions** and changes only the draw layer, so no text is measured again.
- Shared actions, navigation rows and app tiles use a 120 ms press response with a restrained scale change and surface feedback, and return with a short elastic settle. A row that is unavailable while work is running fades to its disabled strength instead of only ignoring the tap.
- Sections resize over 180 ms when their content changes. Status and result changes use short crossfades; first-run actions expand when available.
- App and command lists animate placement and appearance when their data changes. Assignment dialogs animate transitions between their steps.
- Activity and dialog windows have finite entry/exit transitions. The voice popup also animates recognition-state changes, and the virtual display crossfades changed media/navigation content.
- Compose honors Android's animator duration scale, including zero. Window transitions honor the corresponding system window/transition scales. There are no permanent decorative animation loops.
- During panel startup, the launcher agent checks for a visible assigned central button and the focused factory home screen. It records one launch request per Android boot before opening the animation. The animation starts on a solid background, opens the wings, then reveals the emblem, the app name and its tagline on the cockpit's own backdrop lights. The steel takes the accent chosen in **Appearance** once, with a single expanding ring, before the mark leaves and the task returns to home. The intro always uses the dark roles, because its window starts on the dark cockpit background, and takes the accent, contrast and type choices from Appearance; it has no colors of its own. Disabling screen transitions or the system animator skips the intro.

State collection follows the destination lifecycle. Settings snapshots copy mutable preferences into immutable presentation state. The app grid decodes the selected desktop's saved image on an I/O worker. Playback progress derives from a timed media-session sample and ticks while the visible preview shows a playing track. The main Displays screen owns playback actions. Closing the display route releases its presentation, media-preview listener and virtual display.

Vehicle routines, seasonal climate, conditional rules and factory assistance use separate destinations and immutable ViewModel state. Foreground automation shares one native telemetry subscription and the established headlight monitor/history. Leaving the resumed host cancels observation and pending decisions. Each received snapshot owns finite signal-expiry deadlines; an absent source creates no timer. Settings archives contain configuration, while actual driving-mode history and activation evidence remain local. The Apollo candidate has no production writer or imported approval path. See [vehicle automation qualification](VEHICLE_AUTOMATION.md) and the [alternative candidate](APOLLO_CANDIDATE.md).

## Verification scope

Routing tests cover direct app-grid entry, explicit screen routes, internal Back, dialogs and host recreation. Manifest tests check the launcher and permission boundaries. The launcher test captures each stage of the intro, checks that the charge follows the selected accent and that the animation returns to the previous task. Document and permission returns are guarded by route lifecycle; the voice model also tests document-return sequencing. Compose checks inspect the actual app content tree and save real screenshots on explicitly isolated emulators. Android-owned permission and installation windows are separate from app content.

Build/lint checks, screenshots and frame measurements answer different questions. Screenshots establish layout; interaction tests establish routing; bounded idle, hidden and animation-disabled measurements establish whether rendering settles on the tested emulator. They do not certify the frame rate or OEM behavior on a vehicle. Validation records apply to their listed revisions and completed checks; a source migration alone does not establish a new test result.
