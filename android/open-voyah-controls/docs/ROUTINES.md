# Routines

In Controls, open **Settings → Routines and automation → Routines**. Add steps in the order they should run. A routine can send Android media controls and wait for fixed intervals. The page, session theme, saved desktop layout, and optional app launch complete the routine after its steps. Pin a routine or add it to Quick Actions for manual access. The existing startup schedule can run a routine when opening Controls with its launcher shortcut.

Use **Cancel** during a pause to stop the remaining steps. Leaving the foreground window also stops them. Android media key dispatch requests playback from the current media target and does not confirm that the player changed state. If Android throws while sending a key, Controls stops that run without sending the key again.

Settings archives from before steps remain readable. When restoring a legacy archive over an existing routine with the same stable ID, Controls preserves steps if the archive omits the field. An explicit empty step list clears it. Archives cannot define a shell command or a vehicle write.
