# Task manager

Open **Task manager** in the main Controls app desktop. It shows up to 20 eligible Android tasks on the central display. **Pin to desktop** saves the app's current launcher entry among the desktop pins. **Close task** asks Android to remove the selected task after confirmation. Refresh reads the running list again.

The task manager needs the current window service and the Android 11 task-removal method. It lists standard tasks for the active Android user. Factory and framework tasks, Controls itself, tasks from other users, and apps on other displays are protected. The worker checks the task ID and base component again just before dispatch. A missing or changed task is reported without dispatch. An unconfirmed close is shown as such. The app saves only the chosen desktop pins. The existing window-service diagnostic log carries snapshot and close receipts.

Agent fixtures verify the allowed task list, rejection of stale IDs and protected apps, and task removal on the fake framework. Review the list and close a disposable test app on an authorized compatible head unit to verify the Android interaction. See [Android 11 ActivityTaskManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-mainline-release/services/core/java/com/android/server/wm/ActivityTaskManagerService.java).
