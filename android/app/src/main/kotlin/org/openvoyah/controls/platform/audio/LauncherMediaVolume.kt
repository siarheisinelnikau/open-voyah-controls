package org.openvoyah.controls.platform.audio

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.domain.settings.launcherMediaVolumeTarget

private const val TAG = "OpenVoyahControls"

/** Applies one opted-in ceiling for a launcher entry while its window is still foreground. */
internal suspend fun limitLauncherMediaVolume(
    context: Context,
    preferences: AppearancePreferences,
    isForegroundLauncherEntry: () -> Boolean,
) {
    val percent = withContext(Dispatchers.IO) {
        val settings = preferences.load()
        settings.launchMediaVolumePercent.takeIf { settings.limitMediaVolumeOnLaunch }
    } ?: return

    withContext(Dispatchers.Main.immediate) {
        if (!isForegroundLauncherEntry()) return@withContext
        try {
            val audio = context.getSystemService(AudioManager::class.java) ?: return@withContext
            val stream = AudioManager.STREAM_MUSIC
            if (audio.isVolumeFixed || audio.isStreamMute(stream)) return@withContext
            val target = launcherMediaVolumeTarget(audio.getStreamMinVolume(stream), audio.getStreamMaxVolume(stream),
                audio.getStreamVolume(stream), percent) ?: return@withContext

            if (isForegroundLauncherEntry()) {
                audio.setStreamVolume(stream, target, 0)
                Log.i(TAG, "event=launcher_media_volume outcome=requested")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            Log.w(TAG, "event=launcher_media_volume outcome=unavailable failure=${failure.javaClass.simpleName}")
        }
    }
}
