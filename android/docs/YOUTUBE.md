# YouTube on the head unit

The head unit has no Google Play and no Google services, so the official YouTube app cannot run there. The `youtube-web` module builds a small separate app, **YouTube** (`org.openvoyah.youtube`), that shows YouTube's touch site (`m.youtube.com`) in the system WebView.

## Why a separate WebView app

- **Window and DPI profiles.** The window service keys width and DPI profiles by package (`hooks/window-profiles.js`). An activity inside Controls would share the Controls profile. A shortcut that opens a browser would place the browser's package, not YouTube, and would share the browser's profile.
- **The app grid.** Controls lists every launcher app. The wrapper is an ordinary app installed on top of the firmware, so it gets its tile, **App settings** with width, DPI, display, **Hide**, permissions and **Close app**, pins and manual order with no code for it in Controls.
- **No Google services needed.** The page needs only the WebView. The Chromium-based browsers on the unit cannot be embedded, and a browser has no app mode a launcher could address by package.
- **Touch layout.** The touch site adapts to a wide window and works signed out. The TV layout (`youtube.com/tv`) needs a TV user agent and is built for a remote, not a touch screen.

## Behaviour

- **Back** leaves full screen first, then goes back through the page history, then moves the app to the background. Its page and history stay for the next launch from the grid.
- **Audio focus** comes from the WebView itself: its media session requests focus for a playing video. It pauses the video for a call or a navigation prompt and resumes it afterwards, and stops when another media source starts. The wrapper does not request focus of its own; a second request takes focus from the WebView and stops the video. Verified on WebView 124 with a ringing timer.
- **Media session.** The wrapper publishes the player as an Android media session with the video title, position, play, pause, next and previous, so media keys and media-session readers such as the Controls media card can follow and control it. The WebView has no Media Session API for the page, so the title comes from the document title.
- **Leaving the window** pauses the video, as the official app does without Premium. Nothing plays while the window is hidden.
- **Full screen** fills the app window and hides the system bars.
- **DPI profiles.** The WebView takes its pixel ratio from the display and ignores the density a DPI profile gives the window. The wrapper therefore lays the page out at 1/scale of the window and draws it scale times larger, where scale is the profile's DPI over the display's. With a 240 DPI profile on the 160 DPI central screen, a 1920 px window gets a 1280 CSS px page at 1.5×, the layout YouTube picks for a 240 DPI screen. Text stays sharp and touches land where they are drawn. Without a profile the page uses the display's own scale, which is small on a 160 DPI screen, so give YouTube a DPI profile such as 240 in **App settings**.
- **Links.** YouTube and the Google sign-in pages stay in the wrapper. Other sites open in the head unit's browser. Play Store, `intent:` and YouTube-app links are ignored.
- **Signing in** goes through Google's own pages in the wrapper. The user agent drops the WebView markers (`; wv`, `Version/4.0`) so the site treats the wrapper as the Chrome build it runs on. Google can still refuse to sign in from an embedded view; YouTube then works signed out.
- **Connection loss** shows a retry screen instead of the WebView's error page. A crashed WebView renderer is replaced with a fresh page instead of closing the app.
- **Driving.** Controls has no policy that stops video while the vehicle moves, and the wrapper adds none. Whether the firmware restricts video while driving has not been recorded yet.

## Build and install

From `android`, with the same environment as the Controls build:

```powershell
.\gradlew.bat :youtube-web:assembleDebug :youtube-web:testDebugUnitTest :youtube-web:lintDebug
```

The APK is written to `youtube-web/build/outputs/apk/debug/youtube-web-debug.apk` and is signed with the local debug key. Install it with **Install local APK** in Controls or over ADB:

```powershell
adb install -r youtube-web\build\outputs\apk\debug\youtube-web-debug.apk
```

Installing or updating this package does not touch the Controls APK, its runtime or its deployment files, so boot activation stays in place. Later updates must be signed with the same key; otherwise Android requires an uninstall, which also removes the sign-in and watch history.

## Requirements and limits

- A system WebView recent enough for YouTube. `adb shell dumpsys webviewupdate` reports the provider and version.
- The wrapper shows YouTube's own site, including its advertising and its prompts to install the app.
- A debug build exposes its pages to Chrome DevTools over ADB (`webview_devtools_remote_<pid>`), which helps diagnose layout and playback on the unit.
