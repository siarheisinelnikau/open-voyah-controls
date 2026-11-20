package org.openvoyah.controls.platform.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.test.AndroidTestCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.domain.settings.launcherMediaVolumeTarget

class LauncherMediaVolumeEffectTest : AndroidTestCase() {
    fun testOptInAndEntryGuardControlTheMediaVolumeWrite() = runBlocking {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val app = context.applicationContext
        val audio = checkNotNull(app.getSystemService(AudioManager::class.java))
        val stream = AudioManager.STREAM_MUSIC
        check(!audio.isVolumeFixed && !audio.isStreamMute(stream))
        val previousVolume = audio.getStreamVolume(stream)
        val minimum = audio.getStreamMinVolume(stream)
        val maximum = audio.getStreamMaxVolume(stream)
        val target = checkNotNull(launcherMediaVolumeTarget(minimum, maximum, maximum, percent = 40))
        check(target > minimum + 1)

        val store = app.getSharedPreferences(AppearancePreferences.STORE, Context.MODE_PRIVATE)
        val enabledKey = "limit_media_volume_on_launch"
        val percentKey = "launch_media_volume_percent"
        val previousEnabled = store.all[enabledKey] as? Boolean
        val previousPercent = store.all[percentKey] as? Int
        val preferences = AppearancePreferences(app)
        try {
            audio.setStreamVolume(stream, maximum, 0)
            awaitVolume(audio, stream, maximum)
            preferences.setLaunchMediaVolumePercent(40)
            preferences.setLimitMediaVolumeOnLaunch(false)
            limitLauncherMediaVolume(app, preferences) { error("A disabled ceiling must finish before foreground checks") }
            assertEquals(maximum, audio.getStreamVolume(stream))

            preferences.setLimitMediaVolumeOnLaunch(true)
            var entryChecks = 0
            limitLauncherMediaVolume(app, preferences) { ++entryChecks == 1 }
            assertEquals(2, entryChecks)
            assertEquals(maximum, audio.getStreamVolume(stream))

            limitLauncherMediaVolume(app, preferences) { true }
            awaitVolume(audio, stream, target)

            val quieterVolume = target - 1
            audio.setStreamVolume(stream, quieterVolume, 0)
            awaitVolume(audio, stream, quieterVolume)
            limitLauncherMediaVolume(app, preferences) { true }
            assertEquals(quieterVolume, audio.getStreamVolume(stream))
        } finally {
            try {
                audio.setStreamVolume(stream, previousVolume, 0)
            } finally {
                val editor = store.edit().remove(enabledKey).remove(percentKey)
                previousEnabled?.let { editor.putBoolean(enabledKey, it) }
                previousPercent?.let { editor.putInt(percentKey, it) }
                assertTrue(editor.commit())
            }
        }
    }

    private suspend fun awaitVolume(audio: AudioManager, stream: Int, expected: Int) = withTimeout(2_000) {
        while (audio.getStreamVolume(stream) != expected) delay(20)
    }
}
