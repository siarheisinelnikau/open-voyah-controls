# Window workspace support

The app-pair, passenger workspace, and task manager paths use the existing window service. The service measures the central side panel and status bar at placement. Android's own insets take precedence where they extend farther. These features have agent-fixture coverage. Their actual visual placement and Android task lifecycle still need to be checked on the corresponding signed head-unit build. [App pairs](APP_PAIRS.md) · [Passenger workspace](PASSENGER_WORKSPACE.md) · [Task manager](WORKSPACE_TASKS.md)

| Capability | Firmware evidence needed |
| --- | --- |
| Full width with the central rail hidden | A reversible driver-display launcher window visibility contract, including focus changes, Home, foreground app exit and wake, with the top status bar and vehicle controls still reachable. On the OVC reference unit the OEM lift event changes the menu child and leaves its 145 × 720 window in place. The 100% application profile uses all space beside that window. |
| Live embedded app sessions and transfer | A permitted host for a trusted Android virtual display on the exact firmware, with confirmed task ownership across view and surface changes, reparenting, release and return from either physical display. The current swipe moves eligible ordinary tasks between their supported displays. |
| Yandex Navigator inside the cluster media card | The cluster-card worker uses the visible music view as its host on Android 11. Verify that the pinned OEM process may create the trusted child display and launch the Navigator Activity, and confirm composition and release with the active theme. [Driver display](DRIVER_DISPLAY.md#yandex-navigator-window) |

The [compact navigation card](DRIVER_DISPLAY.md#compact-navigation-card) draws guidance inside the cluster media view through the existing card agent. Its OEM route source and viewport still need head-unit qualification.

See the [launcher window map](HOME_SCREEN_MAP.md#windows-on-the-central-display) and [instrument cluster documentation](CLUSTER.md#screens-and-the-media-card).
