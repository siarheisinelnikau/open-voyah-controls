package org.openvoyah.controls.data.frida

import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import java.io.IOException

/** A separate login protocol; guest-account files must never be used as its rollback. */
internal object OverseaProfiles {
    class Refused(val label: Int) : IOException(UiText.get(label))
    @JvmStatic fun isProduct(product: String) = product == "f6811"

    @JvmStatic fun requireSlot(slot: String) {
        require(slot in setOf("A", "B", "C")) { "Invalid driver" }
    }

    @JvmStatic fun refusal(reply: String, nonce: String): Refused {
        require(nonce.matches(Regex("[a-f0-9]{32}")))
        val prefix = "OVC_OVERSEA_PROFILE_PROBE $nonce "
        val rows = reply.lineSequence().filter { it.startsWith("OVC_OVERSEA_PROFILE_PROBE ") }.toList()
        val status = rows.singleOrNull()?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        return Refused(when (status) {
            "api-present account=guest", "api-present account=identified" -> R.string.profile_oversea_recovery_missing
            "api-present account=unavailable", "api-present account=unstable" -> R.string.profile_oversea_account_unavailable
            "unsupported account=unavailable" -> R.string.profile_oversea_api_unsupported
            else -> R.string.profile_oversea_probe_unavailable
        })
    }

    @JvmStatic fun probeCommand(base: String, injector: String, templateSha: String, nonce: String): String {
        require(base == ProductionRootAccess.BASE)
        require(injector.matches(Regex(Regex.escape(base) + "/open-voyah-controls-frida-[0-9]+\\.[0-9]+\\.[0-9]+")))
        require(templateSha.matches(ReviewedFirmware.sha256) && nonce.matches(Regex("[a-f0-9]{32}")))
        return """
            set -eu
            [ "${'$'}(getprop ro.product.name)" = f6811 ] || exit 77
            template='$base/profile-probe.template.js'
            [ ! -L "${'$'}template" ] && [ "${'$'}(stat -c '%u:%a:%h' "${'$'}template")" = 0:600:1 ] || exit 77
            [ "${'$'}(sha256sum "${'$'}template" | awk '{print ${'$'}1}')" = '$templateSha' ] || exit 77
            login_pid() { pidof com.qinggan.app.login | awk 'NF==1 && ${'$'}1~/^[1-9][0-9]*${'$'}/ {print ${'$'}1}'; }
            started() {
                line=${'$'}(cat "/proc/${'$'}1/stat") || return 1
                printf '%s\n' "${'$'}{line##*) }" | awk 'NF>=20 && ${'$'}1!="Z" && ${'$'}1!="X" {print ${'$'}20}'
            }
            pid=${'$'}(login_pid)
            [ -n "${'$'}pid" ] && [ "${'$'}pid" -gt 1 ] || exit 77
            before=${'$'}(started "${'$'}pid")
            [ -n "${'$'}before" ] || exit 77
            probe=${'$'}(mktemp '$base/.profile-probe.XXXXXXXX')
            trap 'rm -f "${'$'}probe"' EXIT
            chmod 600 "${'$'}probe"
            printf "globalThis.OVC_PROFILE_PROBE_ID='%s';\n" '$nonce' > "${'$'}probe"
            cat "${'$'}template" >> "${'$'}probe"
            [ "${'$'}(login_pid)" = "${'$'}pid" ] && [ "${'$'}(started "${'$'}pid")" = "${'$'}before" ] || exit 77
            result=${'$'}(timeout -k 2 8 '$injector' -p "${'$'}pid" -s "${'$'}probe" 2>/dev/null) || code=${'$'}?
            case "${'$'}{code:-0}" in 0|124) ;; *) exit 77;; esac
            [ "${'$'}(login_pid)" = "${'$'}pid" ] && [ "${'$'}(started "${'$'}pid")" = "${'$'}before" ] || exit 77
            printf '%s\n' "${'$'}result" | grep '^OVC_OVERSEA_PROFILE_PROBE '
        """.trimIndent()
    }

    @JvmStatic fun recoveryUnavailable(): Refused = Refused(R.string.profile_oversea_recovery_missing)
}
