# Playback comfort

Find **Playback in the car** under **Settings → Routines and automation**. Both switches start off. Controls needs Android notification access and a local music player with standard pause and play actions. The Controls accessibility service keeps the automation running while the app is closed.

- **Pause when the driver’s door opens** consumes a fresh closed-to-open transition during a live vehicle session with Parking gear. An already-open door at service connection or wake establishes the starting state. The vehicle read-only adapter checks its production component, signer and APK before adding the door observer.
- **Pause on sleep and resume on wake** follows Android interactivity. Controls resumes at most once for each wake when it can still identify the player, the same track and the unchanged pause response. It discards the in-memory receipt after wake, after a settings or runtime reset, and after 12 hours. A manual stop, seek, track change, replaced player, another local player taking over, revoked access or a missing pause response prevents playback from resuming. The original application’s controls remain active.

Only the switches are settings. Playback receipts are never persisted. The log identifies the action and outcome without naming the player or saving track metadata. Android’s screen-on event is used for media and does not establish vehicle ACC or READY. Delivery of the door and sleep signals and support in particular media players must be checked on each supported head unit.
