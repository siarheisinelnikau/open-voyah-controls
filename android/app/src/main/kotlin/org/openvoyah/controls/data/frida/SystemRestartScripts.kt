package org.openvoyah.controls.data.frida

/**
 * Root scripts behind Settings → "Restart multimedia" and "Restart the system". They run only through
 * [FridaBridge.restartMultimedia] and [FridaBridge.rebootSystem], each gated by its own reviewed operation.
 */
internal object SystemRestartScripts {
    /**
     * The OEM media center. It is a persistent system app, so Android starts it again by itself; the launcher, the
     * passenger screen, the cluster media card and the music app reconnect to its media browser services.
     */
    const val MEDIA_CENTER = "com.qinggan.media"

    /**
     * What "multimedia" means on this head unit: the media center and the factory players. Car services (CAN,
     * vehicle, cluster), the launcher, the audio policy and Android's own audio and media servers are never listed,
     * because their restart could interrupt chimes, warnings or anything else the driver relies on.
     */
    val MEDIA_PACKAGES = listOf(MEDIA_CENTER, "com.qinggan.app.music", "com.qinggan.app.radio", "com.qinggan.app.video")

    /** How long the script waits for Android to bring the media center back. */
    const val MEDIA_CENTER_WAIT_SECONDS = 12

    /** Printed once the reboot is scheduled, so the caller knows the request left the root channel. */
    const val REBOOT_SCHEDULED = "OVC_REBOOT_SCHEDULED"

    /**
     * `svc power reboot` asks PowerManager for an orderly shutdown, the same path as the OEM's own restart. It runs
     * detached a second later, so the root session answers before Android starts shutting down.
     */
    const val REBOOT = "command -v svc >/dev/null || exit 1\n" +
        "setsid sh -c 'sleep 1; svc power reboot open-voyah-controls' </dev/null >/dev/null 2>&1 &\n" +
        "printf '%s\\n' $REBOOT_SCHEDULED\n"

    private val packageName = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")

    fun mediaRestart(packages: List<String> = MEDIA_PACKAGES): String {
        require(packages.isNotEmpty() && packages.all(packageName::matches) && MEDIA_CENTER in packages)
        return buildString {
            // Main and secondary processes of one package ("pkg" and "pkg:name"); the ps header never matches.
            appendLine("ovc_pids() { ps -A -o PID,NAME | while read ovc_pid ovc_name; do case \"\$ovc_name\" in " +
                "\"\$1\"|\"\$1\":*) printf '%s ' \"\$ovc_pid\";; esac; done; }")
            appendLine("ovc_center_before=\$(ovc_pids $MEDIA_CENTER)")
            for (pkg in packages) {
                appendLine("ovc_list=\$(ovc_pids $pkg)")
                appendLine("if [ -n \"\$ovc_list\" ]; then kill -9 \$ovc_list && printf 'OVC_MEDIA\\t%s\\tstopped\\n' $pkg " +
                    "|| printf 'OVC_MEDIA\\t%s\\tfailed\\n' $pkg; else printf 'OVC_MEDIA\\t%s\\tidle\\n' $pkg; fi")
            }
            appendLine("ovc_i=0")
            appendLine("while [ \$ovc_i -lt $MEDIA_CENTER_WAIT_SECONDS ]; do")
            appendLine("  sleep 1; ovc_i=\$((ovc_i+1))")
            appendLine("  ovc_center=\$(ovc_pids $MEDIA_CENTER)")
            appendLine("  if [ -n \"\$ovc_center\" ] && [ \"\$ovc_center\" != \"\$ovc_center_before\" ]; then " +
                "printf 'OVC_MEDIA_CENTER\\tback\\t%s\\n' \"\$ovc_i\"; exit 0; fi")
            appendLine("done")
            appendLine("printf 'OVC_MEDIA_CENTER\\tmissing\\n'")
        }
    }

    data class MediaRestart(val stopped: List<String>, val failed: List<String>, val centerBack: Boolean, val seconds: Int?) {
        val successful: Boolean get() = failed.isEmpty() && centerBack
    }

    fun parseMediaRestart(output: String): MediaRestart {
        val stopped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var back = false
        var seconds: Int? = null
        for (line in output.lineSequence()) {
            val columns = line.trimEnd('\r').split('\t')
            when {
                columns.size == 3 && columns[0] == "OVC_MEDIA" && columns[1] in MEDIA_PACKAGES -> when (columns[2]) {
                    "stopped" -> stopped += columns[1]
                    "failed" -> failed += columns[1]
                }
                columns.size == 3 && columns[0] == "OVC_MEDIA_CENTER" && columns[1] == "back" -> {
                    back = true
                    seconds = columns[2].toIntOrNull()
                }
            }
        }
        return MediaRestart(stopped, failed, back, seconds)
    }
}
