package org.openvoyah.controls.data.frida

import java.io.IOException

/**
 * Root scripts that choose the keyboard of the central display and read back what Android actually bound.
 *
 * This firmware's input method service ignores `default_input_method` and selects a fixed keyboard by language, so
 * `ime set` alone is undone at once. The window agent in system_server (`hooks/keyboard-policy.js`) selects the
 * keyboard stored in [CHOICE_KEY] instead while that keyboard is installed and enabled. These scripts store the
 * choice, make Android re-read its keyboard settings by clearing `default_input_method`, and confirm the result from
 * `mCurMethodId` of the service. A choice that is not confirmed is removed again, so the firmware keyboard stays.
 *
 * No script disables a keyboard: the factory keyboards stay installed and enabled in every case.
 */
internal object KeyboardSelection {
    const val CHOICE_KEY = "open_voyah_controls_input_method"
    const val FALLBACK_KEY = "open_voyah_controls_input_method_fallback"
    /** The OEM keyboards. The passenger display's own input method service binds the second one. */
    val FACTORY_PACKAGES = setOf("com.qinggan.app.qgime", "com.qinggan.app.qgime.second")
    private const val RESULT = "OVC_KEYBOARD"
    private val idPattern = Regex(
        "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+/\\.?[A-Za-z][A-Za-z0-9_$]*(?:\\.[A-Za-z][A-Za-z0-9_$]*)*",
    )
    private val fallbackPattern = Regex("(crashed|unavailable) (\\S+)")
    /** Android binds the keyboard within milliseconds; the poll allows for a slow keyboard process to start. */
    private const val POLLS = 12

    enum class Outcome(val token: String) {
        /** Android bound the chosen keyboard. */
        APPLIED("applied"),
        /** Android did not bind the chosen keyboard in time; the choice was removed again. */
        NOT_CONFIRMED("not_confirmed"),
        /** Android does not list the keyboard as installed and enabled, so it was not chosen. */
        NOT_ENABLED("not_enabled"),
        /** The choice was removed and the firmware selected its own keyboard. */
        FACTORY("factory"),
        /** Nothing was changed; the script only read the state. */
        READ("read"),
    }

    data class Snapshot(
        val outcome: Outcome,
        /** The stored choice, or null when the firmware chooses. */
        val choice: String?,
        /** The keyboard Android's service bound for the central display, or null when it bound none. */
        val active: String?,
        /** The keyboard of the passenger display's own service, when the firmware has one. */
        val passenger: String?,
        /** Why the agent dropped an earlier choice. */
        val fallback: Fallback?,
    )

    data class Fallback(val reason: Reason, val id: String)

    enum class Reason { CRASHED, UNAVAILABLE }

    fun validId(id: String): Boolean = id.matches(idPattern)

    fun packageOf(id: String): String = id.substringBefore('/')

    @JvmStatic fun readScript(): String = buildString {
        append(prelude())
        appendLine("echo '$RESULT result=${Outcome.READ.token}'")
        append(report())
    }

    @JvmStatic fun selectScript(id: String): String {
        require(validId(id) && packageOf(id) !in FACTORY_PACKAGES) { "Unexpected keyboard" }
        return buildString {
            append(prelude())
            appendLine("ovc_id='$id'")
            appendLine("ime enable \"\$ovc_id\" >/dev/null 2>&1")
            // Android writes the enabled list itself; only a keyboard it lists can be bound.
            appendLine("case \":\$(ovc_secure enabled_input_methods):\" in")
            appendLine("    *\":\$ovc_id:\"*|*\":\$ovc_id;\"*) ;;")
            appendLine("    *) echo '$RESULT result=${Outcome.NOT_ENABLED.token}'; ovc_report; exit 0;;")
            appendLine("esac")
            appendLine("settings put secure $CHOICE_KEY \"\$ovc_id\" || exit 1")
            appendLine("settings delete secure $FALLBACK_KEY >/dev/null 2>&1")
            appendLine("ovc_reselect")
            appendLine("ovc_poll=0")
            appendLine("while [ \"\$(ovc_bound input_method)\" != \"\$ovc_id\" ]; do")
            appendLine("    ovc_poll=\$((ovc_poll+1))")
            appendLine("    if [ \$ovc_poll -ge $POLLS ]; then")
            // Not confirmed: the agent is not running or refused the keyboard. Remove the choice, so the firmware
            // keyboard is selected now and after every later change.
            appendLine("        settings delete secure $CHOICE_KEY >/dev/null 2>&1")
            appendLine("        ovc_reselect")
            appendLine("        sleep 1")
            appendLine("        echo '$RESULT result=${Outcome.NOT_CONFIRMED.token}'; ovc_report; exit 0")
            appendLine("    fi")
            appendLine("    sleep 0.25")
            appendLine("done")
            appendLine("echo '$RESULT result=${Outcome.APPLIED.token}'")
            append(report())
        }
    }

    @JvmStatic fun factoryScript(): String = buildString {
        append(prelude())
        appendLine("settings delete secure $CHOICE_KEY >/dev/null 2>&1")
        appendLine("settings delete secure $FALLBACK_KEY >/dev/null 2>&1")
        appendLine("ovc_reselect")
        appendLine("sleep 1")
        appendLine("echo '$RESULT result=${Outcome.FACTORY.token}'")
        append(report())
    }

    /**
     * Removes a choice whose keyboard is no longer installed and records why, as the agent does at boot. The
     * agent already ignores such a choice; this keeps the stored state honest while the agent is not running.
     */
    @JvmStatic fun releaseScript(id: String): String {
        require(validId(id)) { "Unexpected keyboard" }
        return buildString {
            append(prelude())
            appendLine("if [ \"\$(ovc_secure $CHOICE_KEY)\" = '$id' ]; then")
            appendLine("    settings delete secure $CHOICE_KEY >/dev/null 2>&1")
            appendLine("    settings put secure $FALLBACK_KEY 'unavailable $id' || exit 1")
            appendLine("    ovc_reselect")
            appendLine("    sleep 1")
            appendLine("fi")
            appendLine("echo '$RESULT result=${Outcome.FACTORY.token}'")
            append(report())
        }
    }

    @JvmStatic @Throws(IOException::class)
    fun parse(output: String): Snapshot {
        val values = output.lineSequence().map(String::trim).filter { it.startsWith("$RESULT ") }
            .map { it.removePrefix("$RESULT ") }.mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }.toMap()
        val outcome = Outcome.entries.firstOrNull { it.token == values["result"] }
            ?: throw IOException("The keyboard script did not report a result")
        fun id(key: String) = values[key]?.takeIf(::validId)
        val fallback = values["fallback"]?.let(fallbackPattern::matchEntire)?.let { match ->
            val reason = if (match.groupValues[1] == "crashed") Reason.CRASHED else Reason.UNAVAILABLE
            match.groupValues[2].takeIf(::validId)?.let { Fallback(reason, it) }
        }
        return Snapshot(outcome, id("choice"), id("active"), id("passenger"), fallback)
    }

    private fun prelude() = buildString {
        appendLine("ovc_secure() { ovc_value=\$(settings get secure \"\$1\" 2>/dev/null) || ovc_value=; " +
            "[ \"\$ovc_value\" = null ] && ovc_value=; printf '%s' \"\$ovc_value\"; }")
        appendLine("ovc_bound() { dumpsys \"\$1\" 2>/dev/null | grep -m 1 'mCurMethodId=' | sed 's/^.*mCurMethodId=//' | " +
            "tr -d '\\r '; }")
        // Changing the selected keyboard makes Android re-read its keyboard settings: the agent then selects the
        // stored choice, otherwise the firmware its own keyboard. Only a changed value notifies Android, so an
        // absent value becomes empty and any other value is removed.
        appendLine("ovc_reselect() { if [ \"\$(settings get secure default_input_method 2>/dev/null)\" = null ]; then " +
            "settings put secure default_input_method ''; else settings delete secure default_input_method; fi >/dev/null 2>&1; }")
        appendLine("ovc_report() {")
        appendLine("    echo \"$RESULT choice=\$(ovc_secure $CHOICE_KEY)\"")
        appendLine("    echo \"$RESULT active=\$(ovc_bound input_method)\"")
        appendLine("    echo \"$RESULT passenger=\$(ovc_bound input_method_ext)\"")
        appendLine("    echo \"$RESULT fallback=\$(ovc_secure $FALLBACK_KEY)\"")
        appendLine("}")
    }

    private fun report() = "ovc_report\n"
}
