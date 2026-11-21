package org.openvoyah.controls.data.frida

import android.content.Context
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.root.LoopbackShell
import org.openvoyah.controls.diagnostics.ControlsLog

/** Connects to the installer-provisioned root Telnet service started by the OEM init script. */
internal class AuthorizedRootChannel private constructor(private val transport: LoopbackShell) {
    /** [purpose] is a fixed token naming the caller in diagnostics; the script and its output are never logged. */
    fun execute(script: String, purpose: String = "unspecified"): String {
        val started = System.nanoTime()
        try {
            val output = transport.execute(script)
            ControlsLog.debug("root_command", "purpose", purpose, "outcome", "completed", "elapsed_ms", elapsedSince(started))
            return output
        } catch (error: LoopbackShell.Failed) {
            report(purpose, error, error.status, started)
            throw when (error.status) {
                77 -> ProductionRootAccess.Refused(UiText.get(R.string.production_evidence_changed))
                78 -> ProductionRootAccess.Refused(UiText.get(R.string.production_recovery_blocked))
                else -> error
            }
        } catch (error: Exception) {
            report(purpose, error, null, started)
            throw error
        }
    }

    companion object {
        private val commandFailures = RepeatedFailureFilter()
        private val openFailures = RepeatedFailureFilter()

        fun existing(context: Context): AuthorizedRootChannel {
            var reason = "stat_failed"
            try {
                val file = File(context.filesDir, "root-channel.key")
                val stat = try {
                    Os.lstat(file.path)
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.ENOENT) reason = "missing"
                    throw error
                }
                reason = when {
                    !OsConstants.S_ISREG(stat.st_mode) -> "not_regular_file"
                    stat.st_uid != Process.myUid() -> "wrong_owner"
                    (stat.st_mode and 0x1ff) != 0x180 -> "wrong_mode"
                    stat.st_nlink != 1L -> "link_count"
                    file.length() != 65L -> "wrong_length"
                    else -> "unreadable"
                }
                check(reason == "unreadable")
                val key = file.readText().removeSuffix("\n")
                reason = "bad_format"
                check(key.matches(Regex("[a-f0-9]{64}")))
                return AuthorizedRootChannel(LoopbackShell(LoopbackShell.PRODUCTION_PORT, key))
            } catch (error: Exception) {
                // Every refusal reaches the screen as the same "setup incomplete" text; only this entry tells them apart.
                if (openFailures.isRepeat(reason)) ControlsLog.debug("root_channel_open", "reason", reason, "error", error)
                else ControlsLog.warn("root_channel_open", error, "reason", reason)
                throw IOException(UiText.get(R.string.production_broker_unavailable), error)
            }
        }

        private fun report(purpose: String, error: Exception, status: Int?, started: Long) {
            val elapsed = elapsedSince(started)
            val fields = if (status == null) arrayOf<Any?>("purpose", purpose, "outcome", "failed", "elapsed_ms", elapsed)
                else arrayOf<Any?>("purpose", purpose, "outcome", "failed", "exit", status, "elapsed_ms", elapsed)
            // A caller retrying on every media callback would otherwise fill the journal with one failure.
            if (commandFailures.isRepeat("$purpose:${status ?: error.javaClass.name}")) {
                ControlsLog.debug("root_command", *fields, "error", error)
            } else {
                ControlsLog.warn("root_command", error, *fields)
            }
        }

        private fun elapsedSince(started: Long): Long = (System.nanoTime() - started) / 1_000_000L
    }
}
