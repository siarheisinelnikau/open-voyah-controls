package org.openvoyah.controls.data.frida

/**
 * Decides, in one root command, whether the sealed deployment can simply start again after a boot or after an update
 * of the app itself.
 *
 * Every staged script and agent must have the SHA-256 of the asset this APK carries, and `boot-start.sh --verify` must
 * pass. Right after an update Android records the new APK a few seconds late, and the supervisor answers "waiting"
 * (exit 2) until it has: the check asks again for up to [VERIFY_WAIT_SECONDS]. A receipt without this version's
 * `signer` row is current but was sealed by another version of the app, so it is resealed.
 */
internal object ResumeCheck {
    const val VERIFY_WAIT_SECONDS = 40
    private const val MARKER = "OVC_RESUME "
    private val sha256 = Regex("[0-9a-f]{64}")
    private val artifact = Regex("(?:[a-z0-9-]+/)?[a-z0-9.-]+")

    enum class Result(val token: String) {
        /** Files and receipt are this version's: start the workers. */
        CURRENT("current"),
        /** Files verify, but the receipt was sealed by another version: reseal, then start. */
        RESEAL("reseal"),
        /** A file differs from this APK or the deployment does not verify: deploy afresh. */
        STALE("stale"),
    }

    @JvmStatic fun script(base: String, pins: Map<String, String>, signerRow: String): String {
        require(base.matches(Regex("/[A-Za-z0-9_./-]+")) && !base.contains("..")) { "Unexpected runtime directory" }
        require(pins.isNotEmpty() && pins.all { (file, digest) -> file.matches(artifact) && digest.matches(sha256) }) {
            "Unexpected artifact pins"
        }
        val row = signerRow.split('\t')
        require(row.size == 4 && row[0] == "signer" && row.none { it.isEmpty() || it.contains('\'') }) { "Unexpected signer row" }
        return buildString {
            appendLine("ovc_resume() {")
            appendLine("[ -f '$base/enabled' ] && [ ! -L '$base/enabled' ] || { echo ${MARKER}stale; return; }")
            for ((file, digest) in pins) {
                val path = "'$base/$file'"
                appendLine("[ -f $path ] && [ ! -L $path ] && ovc_hash=\$(sha256sum $path 2>/dev/null) && " +
                    "[ \"\${ovc_hash%% *}\" = '$digest' ] || { echo ${MARKER}stale; return; }")
            }
            appendLine("ovc_try=0")
            appendLine("while :; do")
            appendLine("ovc_verify=\$(/system/bin/sh '$base/boot-start.sh' --verify 2>/dev/null); ovc_code=\$?")
            appendLine("[ \"\$ovc_code\" = 0 ] && [ \"\$ovc_verify\" = OVC_BOOT_VERIFIED ] && break")
            appendLine("[ \"\$ovc_code\" = 2 ] && [ \"\$ovc_try\" -lt ${VERIFY_WAIT_SECONDS / 2} ] || { echo ${MARKER}stale; return; }")
            appendLine("ovc_try=\$((ovc_try+1)); sleep 2")
            appendLine("done")
            appendLine("ovc_row=\$(printf '%s\\t%s\\t%s\\t%s' '${row[0]}' '${row[1]}' '${row[2]}' '${row[3]}')")
            appendLine("if grep -qxF \"\$ovc_row\" '$base/trust.tsv'; then echo ${MARKER}current; else echo ${MARKER}reseal; fi")
            appendLine("}")
            appendLine("ovc_resume")
        }
    }

    @JvmStatic fun parse(output: String): Result {
        val token = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith(MARKER) }?.removePrefix(MARKER)
        return Result.entries.firstOrNull { it.token == token } ?: Result.STALE
    }
}
