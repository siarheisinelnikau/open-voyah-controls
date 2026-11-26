# Open two apps together

On the central display, open the Controls app grid and choose **App pairs → Create pair**. Choose a left app, a right app, and their proportions. Give the pair a name or use the app names. Turn on **Show in the dock** to also open it from the Controls dock. Each pair has its own tile on the main app desktop. Open **App pairs** again to edit or remove a saved layout.

The window service must be enabled, and both apps must support resizing. The current implementation uses the caption-free Android 11 layout already used by the Controls window service. It checks for the live worker on a reviewed firmware before dispatch. It keeps the measured left panel and status area clear and adjusts the divider to accommodate each app's minimum size. If the two minimum sizes do not fit, the pair cannot be placed. A layout is confirmed only after both current tasks have the expected bounds. If placement fails during the change, the service restores both original window placements and verifies restoration. Launching a pair does not force-stop either app.

Under an app's window settings, **Split-screen scale (DPI)** applies to that app in every saved pair. **Same as app scale** follows the ordinary app scale, including the display default when the app inherits it. A specific DPI or **Display DPI** overrides that scale in pairs. Apply updates the scale in an open pair while keeping both panes in place.

When a pair includes the app embedded on the factory Home screen, Controls waits for the launcher to release that embedded window before it starts the pair. The app then opens on the central display with its pair settings.

The selected app activities, proportions, names and dock choices are included in settings archives. Older archives that omit app pairs preserve pairs already saved on the head unit. Recent task IDs, window geometry and capability evidence are specific to the running session.

The agent fixtures cover measured layout, minimum app sizes, partial or duplicate starts, placement confirmation, service release and restoration after a placement failure. End-to-end placement and visual coexistence require a supported Android 11 head unit with two resizable apps. The firmware name in the vehicle's About page alone does not establish this support.
