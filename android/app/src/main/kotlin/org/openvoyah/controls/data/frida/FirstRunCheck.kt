package org.openvoyah.controls.data.frida

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import org.openvoyah.controls.ButtonAccessibilityService
import org.openvoyah.controls.R
import org.openvoyah.controls.data.settings.SettingsStore
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.data.voice.VoskModels

/** The installer asks the app UID to exercise its own channel and initialize reviewed handlers. */
internal class FirstRunCheck(private val context: Context) {
    suspend fun run(nonce: String, progress: (Int) -> Unit): Int {
        var phase = "root"
        var channel: AuthorizedRootChannel? = null
        var mic = false
        var buttons = false
        var status = "failed"
        var result = R.string.setup_failed
        try {
            progress(R.string.setup_check_root)
            channel = AuthorizedRootChannel.existing(context)
            check(runInterruptible(Dispatchers.IO) { channel.execute("test \"\$(id -u)\" = 0 && printf OVC_ROOT_OK") } == "OVC_ROOT_OK")
            phase = "voice"
            progress(R.string.setup_check_voice)
            runInterruptible(Dispatchers.IO) {
                val models = VoskModels()
                try { models.load(context, VoiceSettings(context).language()) { !Thread.currentThread().isInterrupted } }
                finally { models.release() }
            }
            mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            repeat(10) {
                if (!ButtonAccessibilityService.isConnected()) delay(500)
            }
            buttons = ButtonAccessibilityService.isConnected()
            phase = "firmware"
            val reviewed = try { ReviewedFirmware.load(context); true } catch (_: ProductionRootAccess.Refused) { false }
            if (!reviewed) {
                status = "unreviewed"
                result = R.string.setup_unreviewed
            } else {
                phase = "hooks"
                progress(R.string.setup_check_handlers)
                val bridge = FridaBridge(context)
                runInterruptible(Dispatchers.IO) { bridge.connect(SettingsStore(context).load()) }
                val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
                var ready = false
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (runInterruptible(Dispatchers.IO) { bridge.setupReady() }) { ready = true; break }
                    delay(1_000)
                }
                check(ready)
                status = if (mic && buttons) "ready" else "permissions"
                result = if (mic && buttons) R.string.setup_ready else R.string.setup_permissions
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            result = when (phase) {
                "root" -> R.string.setup_root_failed
                "voice" -> if (error is VoskModels.StorageUnavailable) R.string.setup_space_failed else R.string.setup_voice_failed
                else -> R.string.setup_handlers_failed
            }
        }
        // No key, speech, OEM data or error text is sent to the installer.
        val report = "$nonce\t$status\t$phase\t${if (mic) 1 else 0}\t${if (buttons) 1 else 0}"
        try {
            runInterruptible(Dispatchers.IO) {
                channel?.execute("umask 077\nprintf '%s\\n' '$report' > /data/adb/open-voyah-controls-access/app-probe\n")
                    ?: throw java.io.IOException()
            }
        } catch (_: Exception) { return R.string.setup_root_failed }
        return result
    }
}
