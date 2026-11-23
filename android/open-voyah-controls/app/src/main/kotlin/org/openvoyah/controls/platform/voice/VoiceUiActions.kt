package org.openvoyah.controls.platform.voice

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import java.lang.ref.WeakReference
import java.util.Locale
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.display.DisplayEventBus
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.seatmemory.SeatMemorySlot
import org.openvoyah.controls.platform.phone.openPhoneDialer
import org.openvoyah.controls.domain.voice.NaturalAction
import org.openvoyah.controls.domain.voice.VoiceOutcome
import org.openvoyah.controls.domain.voice.VoiceResult
import org.openvoyah.controls.presentation.navigation.ControlsNavigation

/** Android UI actions run on the foreground screen. Frida has its own gateway. */
internal class VoiceUiActions(
    activity: Activity,
    selectedApp: (String) -> String,
    split: () -> Boolean,
    events: DisplayEventBus,
) {
    private val screen = WeakReference(activity)
    private val launches = LaunchVoiceActions(selectedApp, split, events)
    private val media = MediaVoiceActions(activity.applicationContext)

    fun supports(id: String): Boolean = launches.supports(id) || media.supports(id)

    fun runLocal(id: String, phrase: String): VoiceResult {
        val activity = screen.get()?.takeUnless { it.isDestroyed } ?: return VoiceResult(UiText.get(R.string.ui_148), failed = true)
        return try {
            if (launches.supports(id)) {
                val launch = launches.execute(activity, id)
                if (launch.requested()) requested(launch.message())
                else VoiceResult(launch.message(), failed = true, outcome = VoiceOutcome.APP_ASSIGNMENT_REQUIRED)
            } else requested(media.execute(id, phrase))
        } catch (failure: SecurityException) {
            VoiceResult(UiText.get(R.string.ui_204), failed = true, outcome = VoiceOutcome.BLOCKED)
        } catch (failure: LaunchVoiceActions.AssignmentRequired) {
            VoiceResult(UiText.get(R.string.ui_205) + failure.message.orEmpty(), failed = true,
                outcome = VoiceOutcome.APP_ASSIGNMENT_REQUIRED)
        } catch (failure: Exception) {
            VoiceResult(UiText.get(R.string.ui_205) + failure.message.orEmpty(), failed = true)
        }
    }

    fun runNatural(action: NaturalAction): VoiceResult {
        val activity = screen.get()?.takeUnless { it.isDestroyed || it.isFinishing }
            ?: return VoiceResult(UiText.get(R.string.ui_148), failed = true)
        return try {
            requested(when (action) {
                NaturalAction.VOLUME_UP -> media.execute("VolumeUp", "")
                NaturalAction.VOLUME_DOWN -> media.execute("VolumeDown", "")
                NaturalAction.RESTART_MUSIC -> launches.restart(activity, "Music")
                NaturalAction.RESTART_NAVIGATION -> launches.restart(activity, "Navigator")
            })
        } catch (failure: LaunchVoiceActions.AssignmentRequired) {
            VoiceResult(failure.message.orEmpty(), failed = true, outcome = VoiceOutcome.APP_ASSIGNMENT_REQUIRED)
        } catch (failure: SecurityException) {
            VoiceResult(failure.message ?: UiText.get(R.string.ui_85), failed = true, outcome = VoiceOutcome.BLOCKED)
        } catch (failure: Exception) {
            VoiceResult(failure.message ?: UiText.get(R.string.ui_85), failed = true)
        }
    }

    fun dial(phone: String): VoiceResult {
        val activity = screen.get()?.takeUnless { it.isDestroyed } ?: return VoiceResult(UiText.get(R.string.ui_148), failed = true)
        return try {
            openPhoneDialer(activity, phone)
            requested(UiText.get(R.string.ui_201))
        } catch (failure: SecurityException) {
            VoiceResult(UiText.get(R.string.ui_202) + failure.message.orEmpty(), failed = true, outcome = VoiceOutcome.BLOCKED)
        } catch (failure: Exception) {
            VoiceResult(UiText.get(R.string.ui_202) + failure.message.orEmpty(), failed = true)
        }
    }

    fun openDriverMemory(slot: SeatMemorySlot): VoiceResult {
        val activity = screen.get() as? ComponentActivity
        if (activity == null || activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus() ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return VoiceResult(UiText.get(R.string.ui_148), failed = true)
        }
        return try {
            activity.startActivity(ControlsNavigation.driverMemoryIntent(activity, slot))
            ControlsLog.info("driver_memory_guidance", "slot", slot.number, "outcome", "requested")
            requested(UiText.get(R.string.seat_memory_voice_opening).format(Locale.ROOT, slot.number))
        } catch (failure: RuntimeException) {
            ControlsLog.warn("driver_memory_guidance", "slot", slot.number, "outcome", "failed", "type", failure)
            VoiceResult(UiText.get(R.string.seat_memory_voice_open_failed), failed = true)
        }
    }

    private fun requested(message: String) = VoiceResult(message, outcome = VoiceOutcome.LOCAL_REQUESTED)
}
