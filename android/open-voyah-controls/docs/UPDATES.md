# App updates

Open **Settings → System → Updates** to see the installed version, check official releases, or change the startup check preference. Startup checks are enabled by default and run in the background on an interactive launch. Disabling them makes no startup request; manual checks remain available. An available update appears on Quick access. Startup network failures do not open a dialog.

**Download and install** is an explicit action. Controls downloads a bounded APK from the canonical repository and verifies the release size, SHA-256, package, current signing certificate, version, Android compatibility, native ABI and bundled runtime metadata. Debug builds cannot install production updates. An update that needs a different device runtime must be prepared with the Windows installer.

Before submitting an installation, Controls saves a durable transaction, pauses reconnection, restores the supported panel and window changes, and verifies that its workers have stopped. An uncertain restoration or remaining worker prevents the installation. The app then stages the exact verified bytes in an Android package session. Android owns the installation confirmation; Controls does not grant itself installation privileges.

The installation journal records the attempt before handing it to Android. After package replacement, a background recovery job independently verifies the installed version, certificate and APK bytes. Settings and the local channel key remain in app data. Controls stays disconnected after a completed or cancelled update; use **Connect** when ready.

## Interrupted work

- A cancelled download removes its private partial file. A later attempt uses a separate file.
- Closing the screen before an installation is submitted cancels the unattempted preparation and verifies cleanup.
- A lost result or process death never automatically resubmits installation or opens a second confirmation. The Updates screen checks the saved transaction and shows unresolved work.
- **Cancel pending update** abandons the owned session and releases the pause only after recovery is verified. An ambiguous result remains visible instead of reporting success.
- The generic **Install local APK** action rejects the Controls package before creating an Android session. Use Updates or the Windows installer for the application itself.

The update policy does not qualify new OEM firmware or physical operations. Release checks and session fixtures can be tested without a vehicle; exact firmware, boot behavior and vehicle effects still need head unit evidence.
