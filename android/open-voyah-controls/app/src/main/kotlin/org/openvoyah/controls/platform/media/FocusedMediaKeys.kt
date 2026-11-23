package org.openvoyah.controls.platform.media

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import org.openvoyah.controls.MediaAccessService

/** Gives unassigned media keys to the focused application on the default (central) display. */
class FocusedMediaKeys(private val service: AccessibilityService) {
    private data class Press(val downTime: Long, val target: MediaSession.Token?)

    private val media = service.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(service, MediaAccessService::class.java)
    private val owned = mutableMapOf<Pair<Int, Int>, Press>()

    fun claim(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0 || event.keyCode !in mediaKeys) return false
        val target = focusedSession(event.keyCode) ?: return false
        owned[event.deviceId to event.keyCode] = Press(event.downTime, target.sessionToken)
        return true
    }

    fun finishOwnedEvent(event: KeyEvent, enabled: Boolean): Boolean {
        val key = event.deviceId to event.keyCode
        val press = owned[key] ?: return false
        if (press.downTime != event.downTime) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) owned.remove(key)
            return false
        }
        if (event.action == KeyEvent.ACTION_UP) {
            owned.remove(key)
            if (enabled && !event.isCanceled && press.target != null) {
                val target = focusedSession(event.keyCode, press.target)
                if (target != null) runCatching { target.send(event.keyCode) }
            }
        }
        return true
    }

    fun cancelPending() {
        owned.replaceAll { _, press -> press.copy(target = null) }
    }

    private fun focusedSession(key: Int, token: MediaSession.Token? = null): MediaController? = runCatching {
        val pkg = focusedPackage() ?: return null
        media?.getActiveSessions(listener)?.firstOrNull {
            it.packageName == pkg && (token == null || token == it.sessionToken) &&
                it.playbackState?.commandFor(key) != null
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun focusedPackage(): String? {
        val windows = service.windows
        try {
            val focused = windows.firstOrNull { it.isFocused && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val root = focused?.root ?: return null
            return try { root.packageName?.toString() } finally { root.recycle() }
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    private fun MediaController.send(key: Int) {
        when (playbackState?.commandFor(key)) {
            PlaybackState.ACTION_PLAY -> transportControls.play()
            PlaybackState.ACTION_PAUSE -> transportControls.pause()
            PlaybackState.ACTION_STOP -> transportControls.stop()
            PlaybackState.ACTION_SKIP_TO_NEXT -> transportControls.skipToNext()
            PlaybackState.ACTION_SKIP_TO_PREVIOUS -> transportControls.skipToPrevious()
            PlaybackState.ACTION_FAST_FORWARD -> transportControls.fastForward()
            PlaybackState.ACTION_REWIND -> transportControls.rewind()
            PlaybackState.ACTION_PLAY_PAUSE -> {
                val now = SystemClock.uptimeMillis()
                if (dispatchMediaButtonEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))) {
                    dispatchMediaButtonEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
                }
            }
        }
    }

    private companion object {
        val mediaKeys = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        )

        fun PlaybackState.commandFor(key: Int): Long? {
            val action = when (key) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> PlaybackState.ACTION_PLAY
                KeyEvent.KEYCODE_MEDIA_PAUSE -> PlaybackState.ACTION_PAUSE
                KeyEvent.KEYCODE_MEDIA_STOP -> PlaybackState.ACTION_STOP
                KeyEvent.KEYCODE_MEDIA_NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PlaybackState.ACTION_FAST_FORWARD
                KeyEvent.KEYCODE_MEDIA_REWIND -> PlaybackState.ACTION_REWIND
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> when (state) {
                    PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
                    PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> PlaybackState.ACTION_PAUSE
                    else -> PlaybackState.ACTION_PLAY
                }
                else -> return null
            }
            if (actions and action != 0L) return action
            return PlaybackState.ACTION_PLAY_PAUSE.takeIf {
                key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && actions and it != 0L
            }
        }
    }
}
