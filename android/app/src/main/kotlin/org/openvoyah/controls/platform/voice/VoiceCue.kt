package org.openvoyah.controls.platform.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.annotation.RawRes
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openvoyah.controls.R
import org.openvoyah.controls.core.voice.VoiceAudioOptions
import org.openvoyah.controls.core.voice.VoiceAudioOptions.ReadyCueStyle

internal suspend fun playReadyVoiceCue(
    context: Context, options: VoiceAudioOptions, speech: OfflineVoiceFeedback?, voiceName: String, readyPrompt: String = "",
    hasForeground: () -> Boolean,
): ReadyCueStyle? {
    if (!options.readyCue() || options.readyCueGain() <= 0f) return null
    if (options.readyCueStyle() == ReadyCueStyle.SPOKEN && speech != null) {
        val played = withContext(Dispatchers.IO) {
            val file = try { File.createTempFile("ready-", ".wav", context.cacheDir) }
            catch (_: IOException) { return@withContext false }
            try {
                val prepared = withTimeoutOrNull(1_200L) { speech.synthesizeReadyPrompt(file, voiceName, readyPrompt) } == true
                val playbackLimit = if (readyPrompt.isEmpty()) 1_500L else 5_000L
                prepared && playCue(options.readyCueGain(), AudioAttributes.CONTENT_TYPE_SPEECH, playbackLimit, hasForeground) {
                    it.setDataSource(file.absolutePath)
                }
            } finally {
                file.delete()
            }
        }
        if (played) return ReadyCueStyle.SPOKEN
    }
    return if (playVoiceCue(context, R.raw.voice_ready, options.readyCueGain(), hasForeground)) ReadyCueStyle.TONE else null
}

/** The caller waits for the local cue and releases playback before moving to its next audio stage. */
internal suspend fun playVoiceCue(context: Context, @RawRes sound: Int, volume: Float, hasForeground: () -> Boolean): Boolean =
    playCue(volume, AudioAttributes.CONTENT_TYPE_SONIFICATION, 500L, hasForeground) { player ->
        val resource = context.resources.openRawResourceFd(sound) ?: throw IOException("Cue has no local file descriptor")
        resource.use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
    }

private suspend fun playCue(
    volume: Float, contentType: Int, timeoutMs: Long, hasForeground: () -> Boolean, setSource: (MediaPlayer) -> Unit,
): Boolean = withContext(Dispatchers.Main.immediate) {
    if (volume <= 0f || !hasForeground()) return@withContext false
    var player: MediaPlayer? = null
    var started = false
    val completed = try {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { wait ->
                fun complete(success: Boolean) { if (wait.isActive) wait.resume(success) }
                try {
                    val cue = MediaPlayer().also { player = it }
                    cue.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(contentType).build())
                    setSource(cue)
                    cue.setOnErrorListener { _, what, extra ->
                        Log.i("OpenVoyahControls", "Voice cue audio error: $what/$extra")
                        complete(false)
                        true
                    }
                    cue.setOnCompletionListener { complete(true) }
                    cue.setOnPreparedListener { prepared ->
                        if (wait.isActive && hasForeground()) {
                            try {
                                prepared.setVolume(volume, volume)
                                prepared.start()
                                started = true
                            } catch (_: RuntimeException) { complete(false) }
                        } else complete(false)
                    }
                    cue.prepareAsync()
                } catch (_: IOException) {
                    Log.i("OpenVoyahControls", "Voice cue audio unavailable")
                    complete(false)
                } catch (_: RuntimeException) {
                    Log.i("OpenVoyahControls", "Voice cue audio unavailable")
                    complete(false)
                }
            }
        }
    } finally {
        try { player?.release() }
        catch (_: RuntimeException) { Log.i("OpenVoyahControls", "Could not release voice cue audio") }
    }
    if (completed == null) Log.i("OpenVoyahControls", "Voice cue audio timed out")
    // Leave a short silent gap for the acoustic tail after Android closes the output.
    if (started) delay(45L)
    completed == true
}
