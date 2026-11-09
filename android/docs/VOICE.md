# Voice commands

Open **Voice commands** and select **Speak**, type a phrase, or choose a command from the list. The app recognizes 129 commands in English and Russian on the device. The recognition language is separate from the interface language.

- [English catalog](../core/src/main/resources/voice-catalog-en.tsv)
- [Russian catalog](../core/src/main/resources/voice-catalog.tsv)

The microphone is active for one request, up to 12 seconds. Cancel to stop listening. Vehicle actions require confirmation. Some actions depend on the detected vehicle profile and available firmware interfaces.

## Apps and media

To assign an installed app, select a command and choose **App…**. Music, navigation, radio, and camera use your assignments. **Open assistant** uses the Android assistant unless you choose another app for that command. **Controls settings** opens this app's settings.

Playback keys and volume use Android media controls. Ratings require media session access and a supported rating type in the active player.

## Contacts, backup, and recovery

Add local contacts under **Voice settings and recovery**. Voice dialing opens the Android dialer with the selected number after confirmation. Export or import voice settings independently of button assignments. Use **Restore** to return to the previous settings or to restore the bundled speech model.

The app checks vehicle commands before making changes. A lost response does not cause it to replay an action. Compatibility with the vehicle's systems remains unverified. See [Firmware and profiles](FIRMWARE.md).
