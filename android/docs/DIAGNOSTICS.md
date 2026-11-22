# Diagnostics and logging

Controls records what it does in one vocabulary, in three places. This page describes what is written, where to read it and what must never be written.

## One vocabulary

Every entry is a single line of `key=value` tokens that starts with the event name:

```text
event=bridge_connect outcome=failed elapsed_ms=812 error=IOException cause=SocketTimeoutException at=FridaBridge.command:412
```

- `event` names the operation. `outcome` is its result; a measured operation also carries `elapsed_ms`.
- A value is one bounded token. Text with spaces is quoted, control characters are removed and anything longer than 96 characters is cut, so a value cannot split or forge an entry.
- A failure is described by its exception type, the types of its causes and the first frame inside the app. **The exception message is never written.** Transport, parser and package-manager messages can quote private data, and a JSON syntax error quotes the document it failed on.

Application code writes entries through `ControlsLog` (`app/src/main/kotlin/org/openvoyah/controls/diagnostics`). `ControlsLog.span(...)` measures an operation and reports `failed` unless the code recorded another outcome, so an exception leaves an accurate entry without its own `catch`.

## Where entries go

| Destination | Contents | How to read it |
| --- | --- | --- |
| Logcat, tag `OpenVoyahControls` | Every entry. Debug entries and stack traces only in debuggable builds. | `adb logcat -s OpenVoyahControls` |
| On-device journal | INFO, WARN and ERROR entries with a local timestamp and level. | **Settings → System → Diagnostics → Diagnostic log**, or `adb shell run-as org.openvoyah.controls cat files/diagnostics/journal.log` on a debuggable build |
| Agent logs under the deployment directory | Lines printed by the injected JavaScript agents and the supervisor. | **Buttons → Check Frida status** shows the latest error lines of each worker |

The journal exists because Logcat is rarely within reach when something fails in a car: a boot-time connection problem or a refused update should still be readable later, on the head unit itself.

- It lives in app-private storage as two files of at most 192 KB each; the older one is dropped when the newer one is full.
- A burst is cut off at 120 entries per minute and summarized as `event=journal_limited dropped=<n>`, so a faulty source cannot wear the flash or push older evidence out. Anything that can fire per callback is therefore written at DEBUG or only when a state changes.
- An uncaught exception is recorded as `event=uncaught_exception` and flushed before Android ends the process. Each process start writes `event=process_start` with the app version, Android SDK level and product.
- **Diagnostic log** shows the newest entries first, can filter to warnings and errors, and can copy the log or save it to a file. Nothing is sent anywhere by the app. **Clear log** removes both files.

## Agent diagnostics

The JavaScript agents run inside OEM processes and report through `console.log`, which the injector writes to the worker's `hook.log`:

```text
OVC_WINDOW_ERROR stage=install kind=completion_signature cause=NoSuchMethodError
OVC_PANEL_ERROR stage=click kind=SecurityException slot=navbar01 repeated=10
```

- `stage` is the step that failed and `kind` is an outcome code, a Java exception class or a JavaScript error type. As in the app, no message text is printed.
- These hooks sit on hot paths. A problem is printed when it first happens and again at 10, 100, 1000… occurrences, and each agent tracks at most 32 distinct problems, so a failing callback cannot flood the log.
- Readiness and result markers (`OVC_READY`, `OVC_WINDOW_READY protocol=3 …`, `OVC_WINDOW_RESULT <id> <json>` and the others) are a contract with the supervisor and the app. Their text is covered by tests on both sides; change them only together.

## First-run check

`SetupActivity` shows the installer's first-run check as one row per step instead of a single waiting line: local root channel, firmware review, agent deployment, one row for each agent this deployment started (launcher, the translation channels, app windows, cluster navigation, CAN keys), offline speech model, microphone access and the button service. Each row says whether it waits, runs (with its own elapsed seconds), is done (with its time), needs attention, was skipped or failed, and why.

- The check belongs to the app process, not to the page. The page is excluded from the recents, and Android drops such a task once it is behind home and another task starts; in boot 207 (2026-11-22) the startup intro's task did this during a provisioning probe, the check was cancelled with the page and the installer timed out although setup had succeeded. The check now runs to its end and writes its answer to the installer even if the page is covered, closed or dropped, and a page opened again for the same request shows the same run. While a setup page exists or a check runs, the startup intro does not start (`setup_active`, see [What the JavaScript agents do](FRIDA_AGENTS.md#startup-intro)).
- The rows read real state. Deployment reports each stage of `FridaBridge.connect` through `DeploymentProgress`: checking Frida, stopping the previous agents, each agent file by name and position, sealing and starting the supervisors. Agent rows come from `FridaBridge.setupReadiness()`, polled once a second: one `OVC_AGENT <channel> <phase> <detail>` line per channel, built from the supervisor's own files (worker and injector processes, `target.pid`, the fresh `ready` markers). `AgentReadiness` turns them into rows.
- An agent row shows the latest `OVC_*_ERROR` line of its `hook.log` while the supervisor keeps retrying, and the last `OVC_WORKER_*` line of `worker.log` when the supervisor stopped (for example `Retry limit reached for this boot`). A supervisor that is not running 20 s after the deployment fails its row at once instead of waiting for the limit.
- A translation agent whose app is not running yet (Settings, Vehicle, Phone and HiBoard start only when opened) is marked **Needs attention** and does not hold the check back; its worker attaches when the app opens.
- Every step has a limit (root 35 s, deployment 120 s, agents 75 s, speech model 150 s, button service 5 s) and all of them stay inside 225 s, because the installer and `provision.ps1` wait 240 s for the report. A step that runs out of time fails with the seconds it waited, or says that the installer's wait ran out. A changed package or a recovery marker (root exit 77 or 78) fails the agent rows at once with the localized reason. Steps that could not run after a failure are marked as skipped.
- The speech model is prepared while the agents deploy: one is app CPU and storage work, the other waits on the root shell.
- When the check ends, the page keeps the summary and offers **Retry the check**, **Open the diagnostic log** and **Copy the details** after a failure. The copied text holds the step names, times, localized reasons and agent error codes; it never holds the root key, scripts or their output.

The journal records the result as before, with the total time and the first failed row:

```text
event=first_run_check outcome=failed phase=hooks microphone=true buttons=true reported=true elapsed_ms=96210 failed_step=agent_locale_setting
event=first_run_check phase=agents outcome=failed elapsed_ms=21034 waiting=locale_setting
event=bridge_connect outcome=connected elapsed_ms=18412 uploaded=2 reused=6 copied=0 sent_kib=1424
```

`bridge_connect` counts the agent files it sent, kept and copied. A file the head unit already holds with the same SHA-256, a single link, root owner and the expected mode is not sent again: the seal checks exactly these facts, and sending several megabytes of base64 through the Telnet PTY took most of a connection's 45 to 70 s. The four translation channels run the same bundle, so it is sent once and copied on the device for the others. Every sent or copied file is checked against its SHA-256 before it replaces the previous one.

## What must never be written

- The root channel key, its nonce, proof and response values, and any script sent to or output returned from the root shell. Scripts embed the panel configuration, dictionaries, window profiles and trust receipts.
- Recognized or typed voice phrases, contact names and phone numbers, the online voice key and any `Authorization` header.
- Media titles, navigation road names and anything else that reveals where the car is or what is playing.
- OEM account fields and seat-profile documents.
- Paths or content URIs of documents the user picked, and the self-update token beyond a short prefix.
- An exception message, or a `JSONObject`, `Intent`, `Uri` or data class passed as a value: its text is kept up to the token limit, which is enough to leak a phone number. Pass an enum, a count, a status code, a fixed reason token, or the exception itself.

## Adding an entry

1. Name the event after the operation (`update_download`, not `download_error`) and put the result in `outcome`.
2. Use `ControlsLog.span` for anything that touches the root channel, the network, the package installer or a vehicle service, so its duration and failure are always recorded.
3. Pick the level by what the driver would want to find later: INFO for the result of an operation, WARN for a failure or a degraded result, ERROR for a state that needs attention, DEBUG for anything periodic.
4. In an agent, create one reporter per marker with `createReporter('OVC_<AREA>_ERROR')` and call `report(stage, error, fields)`.
