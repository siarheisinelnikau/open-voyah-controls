package org.openvoyah.controls.platform.ui

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import org.openvoyah.controls.domain.routines.RoutineStep
import org.openvoyah.controls.domain.routines.RoutineStepResult

internal class RoutinePlayback(private val context: Context) {
    fun request(step: RoutineStep): RoutineStepResult {
        val key = when (step) {
            RoutineStep.MEDIA_PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            RoutineStep.MEDIA_PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            RoutineStep.MEDIA_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            RoutineStep.MEDIA_PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> error("Expected a playback action")
        }
        val audio = context.getSystemService(AudioManager::class.java) ?: return RoutineStepResult.UNAVAILABLE
        return try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            RoutineStepResult.REQUESTED
        } catch (_: RuntimeException) {
            RoutineStepResult.UNCONFIRMED
        }
    }
}
