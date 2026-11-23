package org.openvoyah.controls.data.frida

/**
 * Re-arms the boot activation without the PC after the boot supervisor revoked it (`boot-start.sh --recover`, which
 * runs `supervise.sh --recover` once both scripts match the receipt).
 *
 * The supervisor re-arms only while the sealed deployment still verifies: the receipt pins the APKs, the Frida runtime,
 * both scripts and every agent, and the app's key must be the one sealed. A changed APK or runtime, a reinstalled app or
 * a failed integrity check still needs the PC. Changed visuals must be gone first, which a reboot always guarantees.
 * OVC's boot job asks in [Mode.AUTO] after every boot; the owner asks in [Mode.MANUAL] from Settings → About and restart.
 */
internal object ActivationRecovery {
    private const val RESULT = "OVC_RECOVER "
    private val reasonPattern = Regex("[a-z-]{1,24}")
    private val channelsPattern = Regex("[a-z,-]{1,160}")

    enum class Mode(val token: String) {
        /** The boot job: only a revocation of an earlier boot that the supervisor recorded with its cause. */
        AUTO("auto"),
        /** The owner confirmed Restore: also this boot's revocation, once no changed visuals can remain. */
        MANUAL("manual"),
    }

    enum class Outcome(val token: String) {
        /** The markers are gone and the channels that were on are enabled again; the workers can start. */
        RECOVERED("recovered"),
        /** Nothing blocks activation. */
        NONE("none"),
        /** A target may still show visuals changed by the stopped agents, or the revocation is from this boot. */
        REBOOT("reboot"),
        /** User storage is not unlocked yet; ask again later. */
        WAITING("waiting"),
        /** The deployment no longer verifies, or the block was not written by the supervisor: the PC is needed. */
        PC("pc"),
    }

    /** [reason] is the supervisor's short code (for example `lifetime`, `visual`, `deployment`); diagnostics only. */
    data class Result(val outcome: Outcome, val reason: String, val channels: List<String> = emptyList())

    @JvmStatic fun script(base: String, mode: Mode): String {
        require(base.matches(Regex("/[A-Za-z0-9_./-]+")) && !base.contains("..")) { "Unexpected runtime directory" }
        // The reply is read from standard output; a refused digest check prints nothing there and reads as PC.
        return "ovc_recover=\$(/system/bin/sh '$base/boot-start.sh' --recover ${mode.token} 2>/dev/null)\n" +
            "printf '%s\\n' \"\$ovc_recover\""
    }

    @JvmStatic fun parse(output: String): Result {
        val line = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith(RESULT) }
            ?: return Result(Outcome.PC, "unverified")
        val fields = line.removePrefix(RESULT).split(' ').mapNotNull { field ->
            field.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        val outcome = Outcome.entries.firstOrNull { it.token == fields["outcome"] } ?: return Result(Outcome.PC, "unverified")
        val reason = fields["reason"]?.takeIf { it.matches(reasonPattern) } ?: "-"
        val channels = fields["channels"]?.takeIf { it.matches(channelsPattern) }?.split(',')?.filter { it.isNotEmpty() && it != "-" }
            ?: emptyList()
        return Result(outcome, reason, channels)
    }
}
