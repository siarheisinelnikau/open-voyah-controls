package org.openvoyah.controls.platform.media

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import org.openvoyah.controls.MediaAccessService
import org.openvoyah.controls.diagnostics.ControlsLog

internal class ComfortMediaTransport(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(app, MediaAccessService::class.java)
    private val handler = Handler(Looper.getMainLooper())

    internal data class Receipt(
        val token: MediaSession.Token,
        val track: MediaSessionReading.Track,
        val pausedAt: Long,
        val position: Long,
        val recordedAt: Long,
    )

    internal data class PauseAttempt(val requested: Boolean = false, val receipt: Receipt? = null)

    fun hasAccess(): Boolean = app.getSystemService(NotificationManager::class.java)
        ?.isNotificationListenerAccessGranted(listener) == true

    suspend fun pause(stillAllowed: () -> Boolean): PauseAttempt {
        var requested = false
        try {
            val player = activePlaying() ?: return PauseAttempt()
            val state = player.playbackState ?: return PauseAttempt()
            if (state.actions and PlaybackState.ACTION_PAUSE == 0L) return PauseAttempt()
            val track = track(player, state)
            val receipt = withTimeoutOrNull(1_500L) {
                callbackFlow<Receipt> {
                    var requestedAt = Long.MAX_VALUE
                    val callback = object : MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            val at = SystemClock.elapsedRealtime()
                            if (track != null && state != null && state.state == PlaybackState.STATE_PAUSED &&
                                state.position >= 0 && state.lastPositionUpdateTime in requestedAt..at &&
                                track(player, state) == track) {
                                trySend(Receipt(player.sessionToken, track, state.lastPositionUpdateTime, state.position, at))
                            }
                        }

                        override fun onSessionDestroyed() { close() }
                    }
                    player.registerCallback(callback, handler)
                    try {
                        val current = activePlaying()
                        if (stillAllowed() && current != null && current.sessionToken == player.sessionToken &&
                            current.playbackState?.let { it.actions and PlaybackState.ACTION_PAUSE != 0L && track(current, it) == track } == true) {
                            requestedAt = SystemClock.elapsedRealtime()
                            requested = true
                            player.transportControls.pause()
                            ControlsLog.info("playback_comfort", "action", "pause", "outcome", "requested")
                            if (track == null) close()
                        } else close()
                        awaitClose()
                    } finally {
                        player.unregisterCallback(callback)
                    }
                }.firstOrNull()
            }
            return PauseAttempt(requested, receipt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            ControlsLog.warn("playback_comfort", failure, "action", "pause", "outcome", "unavailable")
            return PauseAttempt(requested)
        }
    }

    fun remainsPaused(receipt: Receipt): Boolean = runCatching { resumable(receipt) != null }.getOrDefault(false)

    fun resume(receipt: Receipt, stillAllowed: () -> Boolean) {
        try {
            val player = resumable(receipt) ?: return
            if (!stillAllowed()) return
            player.transportControls.play()
            ControlsLog.info("playback_comfort", "action", "resume", "outcome", "requested")
        } catch (failure: RuntimeException) {
            ControlsLog.warn("playback_comfort", failure, "action", "resume", "outcome", "unavailable")
        }
    }

    private fun resumable(receipt: Receipt): MediaController? {
        if (SystemClock.elapsedRealtime() - receipt.recordedAt !in 0L..MAX_SLEEP_MILLIS) return null
        val all = sessions()
        if (all.any { it.isLocalMusic() && MediaSessionReading.isPlaying(it.playbackState) }) return null
        val player = all.singleOrNull { it.sessionToken == receipt.token }?.takeIf { it.isLocalMusic() } ?: return null
        val state = player.playbackState ?: return null
        return player.takeIf { state.state == PlaybackState.STATE_PAUSED && state.actions and PlaybackState.ACTION_PLAY != 0L &&
            state.lastPositionUpdateTime == receipt.pausedAt && state.position == receipt.position && track(it, state) == receipt.track }
    }

    private fun activePlaying(): MediaController? = sessions().singleOrNull {
        it.isLocalMusic() && MediaSessionReading.isPlaying(it.playbackState)
    }

    private fun sessions(): List<MediaController> = if (hasAccess()) manager?.getActiveSessions(listener).orEmpty() else emptyList()

    private fun MediaController.isLocalMusic(): Boolean = playbackInfo?.let {
        it.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL &&
            it.audioAttributes?.usage == AudioAttributes.USAGE_MEDIA
    } == true

    private fun track(player: MediaController, state: PlaybackState): MediaSessionReading.Track? {
        val metadata = player.metadata ?: return null
        return MediaSessionReading.track(MediaSessionReading.media(metadata, state), metadata, state)
    }

    private companion object {
        const val MAX_SLEEP_MILLIS = 12 * 60 * 60 * 1_000L
    }
}
