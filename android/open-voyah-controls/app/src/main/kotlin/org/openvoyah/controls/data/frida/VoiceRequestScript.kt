package org.openvoyah.controls.data.frida

/**
 * A vehicle voice request in one root round trip: root check, live agent, publish and the agent's reply. Each of these
 * used to be a separate root connection with its own command guard, which cost several seconds per command.
 */
internal object VoiceRequestScript {
    const val NO_ROOT = "OVC_VOICE_NO_ROOT"
    const val NOT_READY = "OVC_VOICE_NOT_READY"
    const val PUBLISHED = "OVC_VOICE_PUBLISHED"
    private const val RESULT = "OVC_VOICE_RESULT "

    /** The agent reads the request every 250 ms; the reply is looked for every 0.1 s, for about 12 s in all. */
    const val POLL_SECONDS = "0.1"
    const val POLLS = 80

    sealed interface Outcome {
        /** Nothing was published. */
        data object NoRoot : Outcome
        /** Nothing was published: the voice agent is not live. */
        data object NotReady : Outcome
        /** Published; [line] is the agent's `OVC_VOICE_RESULT` line for this request. */
        data class Reply(val line: String) : Outcome
        /** The output holds no reply line; the outcome is unknown. */
        data object NoReply : Outcome
    }

    /**
     * [probe] defines the shell functions [ready] uses; [publish] writes the request. [token] is the request id the
     * agent echoes; it is hexadecimal, so it needs no quoting inside the pattern.
     */
    fun build(probe: String, ready: String, publish: String, hookLog: String, token: String): String {
        require(token.matches(Regex("[a-f0-9]{32}"))) { "Invalid voice request id" }
        require(!hookLog.contains('\'')) { "Invalid hook log path" }
        return buildString {
            append(probe)
            if (probe.isNotEmpty() && !probe.endsWith("\n")) append('\n')
            append("[ \"\$(id -u)\" = 0 ] || { echo $NO_ROOT; exit 0; }\n")
            append("if $ready; then :; else echo $NOT_READY; exit 0; fi\n")
            // A lost response can be ambiguous even when publishing failed locally. Nothing here ever resends.
            append("$publish || exit 1\n")
            append("echo $PUBLISHED\n")
            append("ovc_i=0\n")
            append("while [ \$ovc_i -lt $POLLS ]; do\n")
            append("  ovc_r=\$(grep 'OVC_VOICE_RESULT.*$token' '$hookLog' 2>/dev/null | tail -n 1)\n")
            append("  if [ -n \"\$ovc_r\" ]; then printf '%s\\n' \"\$ovc_r\"; exit 0; fi\n")
            append("  sleep $POLL_SECONDS; ovc_i=\$((ovc_i+1))\n")
            append("done\n")
            append("exit 1")
        }
    }

    fun outcome(output: String): Outcome {
        val lines = output.lineSequence().map { it.trim() }.toList()
        return when {
            NO_ROOT in lines -> Outcome.NoRoot
            NOT_READY in lines -> Outcome.NotReady
            else -> lines.lastOrNull { it.startsWith(RESULT) }?.let { Outcome.Reply(it) } ?: Outcome.NoReply
        }
    }
}
