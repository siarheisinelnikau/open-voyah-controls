---
name: head-unit-development
description: Develop the Open Voyah Controls Android head unit app and Windows installer. Use for cockpit UI, firmware integration, button assignments, permissions, voice, and installation changes in this repository.
---

# Head unit development

- Implement features independently. Use OEM and Android interface names where they describe the integration. Keep names, assets, and implementation details of reference products out of tracked files and commit messages.
- Design for a wide dashboard running Android 9 or 11. Use a dark, low-glare palette, large targets and short transitions. Keep inactive screens still. Verify the rendered screens at a head unit aspect ratio and update real screenshots.
- Apply the Android Clean Architecture and MVVM guidance in `android/AGENTS.md`. Keep Android services, package management, Frida and vehicle messages in their adapters. Keep component names and existing settings compatible across APK upgrades.
- Verify OEM API names, values and firmware branches before adding a write. Test against fixtures or an explicit emulator first. For app permissions, use Android's recorded install source and effective grants for the current user.
- A button starts one bounded voice capture. Keep the offline path available. Optional remote interpretation can select from fixed local media and app actions only. Keep network credentials and speech out of logs, backups and repository files.
- Windows tooling uses a complete local SDK tool set. Resolve its executable and matching libraries together. Keep transport selection explicit for device writes.
- Commit changes in verified, coherent steps. Keep build artifacts out of Git and record the checks that actually ran.
