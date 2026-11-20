package org.openvoyah.controls.platform.media

import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import org.openvoyah.controls.core.display.PlaybackProgress
import org.openvoyah.controls.domain.display.PreviewMediaAction
import org.openvoyah.controls.domain.display.PreviewSeekStep
import org.openvoyah.controls.domain.display.relativePosition

internal fun previewTransportActions(state: Int, actions: Long, seekable: Boolean): Set<PreviewMediaAction> = buildSet {
    if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) add(PreviewMediaAction.PREVIOUS)
    when (state) {
        PlaybackState.STATE_PLAYING ->
            if (actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L) add(PreviewMediaAction.PAUSE)
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING ->
            if (actions and PlaybackState.ACTION_PAUSE != 0L) add(PreviewMediaAction.PAUSE)
        PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_PAUSED ->
            if (actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L) add(PreviewMediaAction.PLAY)
    }
    if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) add(PreviewMediaAction.NEXT)
    if (seekable && actions and PlaybackState.ACTION_SEEK_TO != 0L) {
        add(PreviewMediaAction.SEEK_BACK)
        add(PreviewMediaAction.SEEK_FORWARD)
    }
}

internal fun MediaController.dispatchPreviewAction(
    action: PreviewMediaAction,
    state: PlaybackState,
    progress: PlaybackProgress?,
    seekStep: PreviewSeekStep,
): Boolean {
    if (action !in previewTransportActions(state.state, state.actions, progress != null)) return false
    when (action) {
        PreviewMediaAction.PLAY -> {
            if (state.actions and PlaybackState.ACTION_PLAY == 0L) return dispatchPlayPause()
            transportControls.play()
        }
        PreviewMediaAction.PAUSE -> {
            if (state.actions and PlaybackState.ACTION_PAUSE == 0L) return dispatchPlayPause()
            transportControls.pause()
        }
        PreviewMediaAction.PREVIOUS -> transportControls.skipToPrevious()
        PreviewMediaAction.NEXT -> transportControls.skipToNext()
        PreviewMediaAction.SEEK_BACK, PreviewMediaAction.SEEK_FORWARD -> {
            val target = progress?.relativePosition(action, SystemClock.elapsedRealtime(), seekStep) ?: return false
            transportControls.seekTo(target)
        }
    }
    return true
}

private fun MediaController.dispatchPlayPause(): Boolean {
    val key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    val sent = dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
    if (sent) dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    return sent
}
