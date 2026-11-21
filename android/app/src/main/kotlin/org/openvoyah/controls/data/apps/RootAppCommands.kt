package org.openvoyah.controls.data.apps

import org.openvoyah.controls.domain.apps.AppOpMode

/**
 * The shell commands Controls sends through its local root shell to manage another app. Every value is validated
 * before it is placed in a command, and the commands start with a root check so a fixture shell cannot pass for root.
 */
internal object RootAppCommands {
    /** Printed by [forceStop] once Android lists no process of the package. */
    const val STOPPED = "OVC_APP_STOPPED"
    /** Printed by [forceStop] when a process of the package is still listed after the wait. */
    const val RUNNING = "OVC_APP_RUNNING"
    /** Starts each operation's answer in [readAppOps] output. */
    const val APP_OP = "OVC_APP_OP"

    private val qualifiedName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val appOpName = Regex("android:[a-z_]+")
    private const val ROOT = "test \"\$(id -u)\" = 0"

    fun permission(user: Int, packageName: String, permission: String, granted: Boolean): String {
        require(user >= 0 && qualifiedName.matches(packageName) && qualifiedName.matches(permission))
        val verb = if (granted) "grant" else "revoke"
        return "$ROOT && pm $verb --user $user $packageName $permission"
    }

    /**
     * Sets the package mode and the UID mode together, because Android lets a UID mode override the package mode.
     * The UID mode is addressed by the numeric [uid]: Android 9's `appops` has no `--uid` option, and 9 and 11 both
     * read a number in the package position as a UID.
     */
    fun appOp(user: Int, packageName: String, uid: Int, operation: String, mode: AppOpMode): String {
        require(user >= 0 && uid > 0 && qualifiedName.matches(packageName) && appOpName.matches(operation))
        require(mode == AppOpMode.ALLOWED || mode == AppOpMode.IGNORED || mode == AppOpMode.ERRORED)
        return "$ROOT && appops set --user $user $packageName $operation ${mode.shell} && appops set $uid $operation ${mode.shell}"
    }

    /** Prints each operation's `appops get` answer after an [APP_OP] marker line naming the operation. */
    fun readAppOps(user: Int, packageName: String, operations: List<String>): String {
        require(user >= 0 && qualifiedName.matches(packageName) && operations.isNotEmpty() && operations.all(appOpName::matches))
        return buildString {
            appendLine("$ROOT || exit 1")
            for (operation in operations) {
                appendLine("echo '$APP_OP $operation'")
                appendLine("appops get --user $user $packageName $operation || exit 1")
            }
        }
    }

    /**
     * The raw mode of each operation in [readAppOps] output: a UID mode wins over the package mode, as in Android's
     * own check, and an operation without either reports its default mode. Operations missing from the output are
     * left out, so the caller treats them as unknown.
     */
    fun appOpModes(output: String, operations: List<String>): Map<String, AppOpMode> {
        val blocks = mutableMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("$APP_OP ")) {
                val operation = line.removePrefix("$APP_OP ")
                current = if (operation in operations) mutableListOf<String>().also { blocks[operation] = it } else null
            } else if (line.isNotEmpty()) current?.add(line)
        }
        return blocks.mapNotNull { (operation, lines) ->
            val name = operation.removePrefix("android:").uppercase(java.util.Locale.ROOT)
            fun modeAfter(prefix: String): AppOpMode? = lines.firstNotNullOfOrNull { line ->
                line.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.substringBefore(';')?.trim()?.let(::mode)
            }
            val mode = modeAfter("Uid mode: $name: ") ?: modeAfter("$name: ") ?: modeAfter("Default mode: ")
                ?: AppOpMode.DEFAULT.takeIf { lines.any { it == "No operations." } }
            mode?.let { operation to it }
        }.toMap()
    }

    private fun mode(shell: String): AppOpMode? = AppOpMode.entries.firstOrNull { it.shell == shell }

    /**
     * Force-stops the package and waits up to two seconds for its processes to leave the process list, matching
     * the package's main process and its named `package:name` processes.
     */
    fun forceStop(user: Int, packageName: String): String {
        require(user >= 0 && qualifiedName.matches(packageName))
        return """
            $ROOT || exit 1
            am force-stop --user $user $packageName || exit 1
            ovc_try=0
            while :; do
                ovc_left=0
                # An empty list means ps could not list processes; that proves nothing, so the check fails.
                ovc_names=${'$'}(ps -A -o NAME=) && test -n "${'$'}ovc_names" || exit 1
                for ovc_name in ${'$'}ovc_names; do
                    case "${'$'}ovc_name" in $packageName|$packageName:*) ovc_left=1; break ;; esac
                done
                test "${'$'}ovc_left" = 0 && { echo $STOPPED; exit 0; }
                ovc_try=${'$'}((ovc_try + 1))
                test "${'$'}ovc_try" -ge 10 && { echo $RUNNING; exit 0; }
                sleep 0.2
            done
        """.trimIndent() + "\n"
    }

    /** True once the package left, false when it is still running, null when the output is not one of ours. */
    fun stopped(output: String): Boolean? {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        return when {
            lines.lastOrNull() == STOPPED -> true
            lines.lastOrNull() == RUNNING -> false
            else -> null
        }
    }
}
