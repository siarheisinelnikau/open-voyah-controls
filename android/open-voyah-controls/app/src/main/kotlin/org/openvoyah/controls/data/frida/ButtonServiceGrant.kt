package org.openvoyah.controls.data.frida

import java.io.IOException

/**
 * Root script that puts the button accessibility service back into Android's list of enabled services.
 *
 * Some head-unit firmware rewrites `enabled_accessibility_services` with only its own service early in every boot.
 * That silently disconnects the service setup enabled, so hardware-button assignments stop working, and the same
 * firmware routes the Accessibility settings screen to a stub that only reports that no app can handle the action.
 *
 * After boot the service comes back only when setup's marker says setup added it. An explicit request from the Buttons
 * screen adds it and writes the same marker, so a rollback removes this grant just as it removes setup's.
 */
internal object ButtonServiceGrant {
    const val ACCESS = "/data/adb/open-voyah-controls-access"
    private const val RESULT = "OVC_BUTTON_SERVICE"
    private val namePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

    enum class Outcome(val token: String) {
        /** The service was already in the list; nothing was written. */
        PRESENT("present"),
        /** The service was added back to the list. */
        RESTORED("restored"),
        /** The service was listed but the owner asked again, so it was removed and added to make Android bind it anew. */
        REBOUND("rebound"),
        /** Setup never added the service, so a boot leaves the list as the owner set it. */
        NOT_GRANTED("not_granted"),
    }

    /**
     * [explicit] is the owner's request from the Buttons screen, made while the service is not connected. A listed
     * service is then removed and added again, as setup does, because Android may hold it as crashed after an update.
     * A boot restore passes false and leaves a listed service alone.
     */
    @JvmStatic fun script(packageName: String, serviceClass: String, explicit: Boolean): String {
        require(packageName.matches(namePattern) && serviceClass.matches(namePattern)) { "Unexpected component" }
        val component = "$packageName/$serviceClass"
        // Android also accepts and stores the short form when the class lives in the package's own namespace.
        val short = if (serviceClass.startsWith("$packageName.")) "$packageName/${serviceClass.removePrefix(packageName)}"
            else component
        return buildString {
            appendLine("ovc_access='$ACCESS'")
            appendLine("ovc_marker=\"\$ovc_access/accessibility-added\"")
            appendLine("ovc_component='$component'")
            appendLine("ovc_short='$short'")
            appendLine("test ! -L \"\$ovc_access\" && test -d \"\$ovc_access\" && test \"\$(stat -c '%u:%a' \"\$ovc_access\")\" = 0:700 || exit 77")
            appendLine("test ! -L \"\$ovc_marker\" || exit 77")
            appendLine("ovc_services=\$(settings get secure enabled_accessibility_services) || exit 1")
            appendLine("test \"\$ovc_services\" != null || ovc_services=")
            // Other services stay enabled in their order; this one is appended.
            appendLine("ovc_others=")
            appendLine("ovc_listed=0")
            appendLine("ovc_ifs=\$IFS")
            appendLine("IFS=:")
            appendLine("for ovc_service in \$ovc_services; do")
            appendLine("    case \"\$ovc_service\" in")
            appendLine("        \"\$ovc_component\"|\"\$ovc_short\") ovc_listed=1;;")
            appendLine("        '') ;;")
            appendLine("        *) ovc_others=\"\${ovc_others:+\$ovc_others:}\$ovc_service\";;")
            appendLine("    esac")
            appendLine("done")
            appendLine("IFS=\$ovc_ifs")
            if (explicit) {
                appendLine("ovc_result=${Outcome.RESTORED.token}")
                appendLine("if [ \"\$ovc_listed\" = 1 ]; then")
                appendLine("    settings put secure enabled_accessibility_services \"\$ovc_others\" || exit 1")
                appendLine("    sleep 1")
                appendLine("    ovc_result=${Outcome.REBOUND.token}")
                appendLine("elif [ ! -f \"\$ovc_marker\" ]; then")
                appendLine("    touch \"\$ovc_marker\" && chmod 600 \"\$ovc_marker\" && sync || exit 1")
                appendLine("fi")
            } else {
                appendLine("test \"\$ovc_listed\" = 0 || { echo '$RESULT ${Outcome.PRESENT.token}'; exit 0; }")
                appendLine("test -f \"\$ovc_marker\" || { echo '$RESULT ${Outcome.NOT_GRANTED.token}'; exit 0; }")
                appendLine("ovc_result=${Outcome.RESTORED.token}")
            }
            appendLine("settings put secure enabled_accessibility_services \"\${ovc_others:+\$ovc_others:}\$ovc_component\" || exit 1")
            appendLine("settings put secure accessibility_enabled 1 || exit 1")
            appendLine("echo \"$RESULT \$ovc_result\"")
        }
    }

    @JvmStatic @Throws(IOException::class)
    fun outcome(output: String): Outcome {
        val token = output.lineSequence().map(String::trim).lastOrNull { it.startsWith("$RESULT ") }
            ?.removePrefix("$RESULT ")
        return Outcome.entries.firstOrNull { it.token == token }
            ?: throw IOException("The button service grant did not report a result")
    }
}
