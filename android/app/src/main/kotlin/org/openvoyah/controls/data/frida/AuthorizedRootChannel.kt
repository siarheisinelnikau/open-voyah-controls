package org.openvoyah.controls.data.frida

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.root.LoopbackShell

/** Connects to the installer-provisioned root Telnet service started by the OEM init script. */
internal class AuthorizedRootChannel private constructor(private val transport: LoopbackShell) {
    fun execute(script: String): String = try {
        transport.execute(script)
    } catch (error: LoopbackShell.Failed) {
        when (error.status) {
            77 -> throw ProductionRootAccess.Refused(UiText.get(R.string.production_evidence_changed))
            78 -> throw ProductionRootAccess.Refused(UiText.get(R.string.production_recovery_blocked))
            else -> throw error
        }
    }
    companion object {
        fun existing(context: Context): AuthorizedRootChannel {
            try {
                val file = File(context.filesDir, "root-channel.key")
                val stat = Os.lstat(file.path)
                check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() &&
                    (stat.st_mode and 0x1ff) == 0x180 && stat.st_nlink == 1L && file.length() == 65L)
                val key = file.readText().removeSuffix("\n")
                check(key.matches(Regex("[a-f0-9]{64}")))
                return AuthorizedRootChannel(LoopbackShell(LoopbackShell.PRODUCTION_PORT, key))
            } catch (error: Exception) {
                throw IOException(UiText.get(R.string.production_broker_unavailable), error)
            }
        }
    }
}
