package org.openvoyah.controls.platform.voice

import android.app.Activity
import android.content.Intent
import android.net.Uri
import java.lang.ref.WeakReference
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.display.DisplayEventBus
import org.openvoyah.controls.domain.voice.NaturalAction
import org.openvoyah.controls.domain.voice.VoiceResult

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
            VoiceResult(if (launches.supports(id)) launches.execute(activity, id) else media.execute(id, phrase))
        } catch (failure: SecurityException) {
            VoiceResult(UiText.get(R.string.ui_204), failed = true)
        } catch (failure: Exception) {
            VoiceResult(UiText.get(R.string.ui_205) + failure.message.orEmpty(), failed = true)
        }
    }

    fun runNatural(action: NaturalAction): VoiceResult {
        val activity = screen.get()?.takeUnless { it.isDestroyed || it.isFinishing }
            ?: return VoiceResult(UiText.get(R.string.ui_148), failed = true)
        return try {
            VoiceResult(when (action) {
                NaturalAction.VOLUME_UP -> media.execute("VolumeUp", "")
                NaturalAction.VOLUME_DOWN -> media.execute("VolumeDown", "")
                NaturalAction.RESTART_MUSIC -> launches.restart(activity, "Music")
                NaturalAction.RESTART_NAVIGATION -> launches.restart(activity, "Navigator")
            })
        } catch (failure: Exception) {
            VoiceResult(failure.message ?: UiText.get(R.string.ui_85), failed = true)
        }
    }

    fun dial(phone: String): VoiceResult {
        val activity = screen.get()?.takeUnless { it.isDestroyed } ?: return VoiceResult(UiText.get(R.string.ui_148), failed = true)
        return try {
            activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)))
            VoiceResult(UiText.get(R.string.ui_201))
        } catch (failure: Exception) {
            VoiceResult(UiText.get(R.string.ui_202) + failure.message.orEmpty(), failed = true)
        }
    }
}
