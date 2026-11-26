# Passenger workspace

Open **Settings → Home screen and apps → Desktops → Passenger workspace** on the central display. The workspace opens in a separate task on the compatible passenger display. Choose applications for its six app slots and two dock slots. **Workspace settings** lets you resize the tiles, choose the clock or date widgets, and opt in to opening the workspace at the next Controls startup. The normal launchers, desks and dock keep their own layouts. The passenger desktop settings are included in settings archives.

The workspace is supported by the Android 11 window service on the designated public built-in passenger display. The service checks the display type and owner and measures the top status area. Android checks whether it allows the activity on that display immediately before launch. If the passenger display is off, absent or unconfirmed, Controls stays where it is. The workspace also exits if its task is moved off that display. Autostart runs from a foreground Controls startup and never starts an activity from a background boot receiver.

The agent fixtures verify the display check, measured status-bar inset, and read-only behavior. Confirm placement, drawable bounds and app switching on a head unit with the actual passenger display before adding it to the verified firmware list.
