package org.openvoyah.controls.data.frida

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.diagnostics.ControlsLog
import java.io.IOException
import java.util.Locale

/** Keeps firmware review and local broker authorization at the privileged command boundary. */
class ProductionRootAccess(context: Context) {
    class Refused @JvmOverloads constructor(message: String, cause: Throwable? = null) : IOException(message, cause)

    private val app = context.applicationContext
    private val review by lazy {
        try {
            ReviewedFirmware.load(app)
        } catch (error: Refused) {
            logRefusal("unreviewed_firmware", error)
            throw error
        } catch (error: Exception) {
            logRefusal("review_unreadable", error)
            throw Refused(UiText.get(R.string.production_evidence_changed), error)
        }
    }
    private val runtime by lazy {
        try {
            ReviewedFirmware.runtime(app)
        } catch (error: Exception) {
            logRefusal("runtime_unreadable", error)
            throw Refused(UiText.get(R.string.production_evidence_changed), error)
        }
    }
    private var proof: Proof? = null

    fun packageReviewed(name: String): Boolean = review.packages.any { it.name == name }
    fun operationReviewed(operation: String): Boolean = operation in review.operations
    fun injectorPath(): String = "$BASE/${runtime.name}"
    fun injectorVersion(): String = runtime.version

    @Synchronized
    @Throws(IOException::class)
    fun refresh() {
        proof = null
        evidence()
    }

    @Synchronized
    @Throws(IOException::class)
    fun execute(script: String): String {
        val verified = evidence()
        return try {
            verified.broker.execute(verified.guard + script, "command")
        } catch (error: Refused) {
            proof = null
            throw error
        }
    }

    @Throws(IOException::class)
    fun requireActivation() {
        requireNoUpdatePause()
        execute(ACTIVATION_GUARD)
    }

    @Throws(IOException::class)
    fun executeActive(script: String): String {
        requireNoUpdatePause()
        return execute(ACTIVATION_GUARD + script)
    }

    private fun requireNoUpdatePause() {
        if (app.getSharedPreferences("bridge", Context.MODE_PRIVATE).contains("updatePauseToken")) {
            throw Refused(UiText.get(R.string.runtime_update_paused))
        }
    }

    fun injectorGuard(): String {
        val injector = shell(injectorPath())
        return "test ! -L $injector && test -f $injector && test -x $injector || exit 77\n" +
            "test \"\$(stat -c '%u:%a:%h' $injector)\" = 0:500:1 || exit 77\n" + hashGuard(injector, runtime.digest)
    }

    @Synchronized
    @Throws(IOException::class)
    fun sealDeployment(artifacts: Map<String, String>) {
        requireNoUpdatePause()

        val verified = evidence()
        val pins = linkedMapOf(runtime.name to runtime.digest)
        pins.putAll(artifacts)
        val receipt = buildString {
            val device = review.device
            appendLine("device\t${device.fingerprint}\t${device.sdk}\t${device.abi}\t${device.product}")
            for (pkg in verified.packages) appendLine("package\t${pkg.name}\t${pkg.path}\t${pkg.apkHash}")
            for ((file, digest) in pins) appendLine("artifact\t$file\t$digest")
        }
        val script = buildString {
            appendLine(ACTIVATION_GUARD)
            append(injectorGuard())
            for ((file, digest) in artifacts) {
                require(file.matches(artifactPath) && digest.matches(ReviewedFirmware.sha256))
                val path = shell("$BASE/$file")
                val mode = if (file.endsWith(".sh")) "700" else "600"
                appendLine("test ! -L $path && test -f $path && test \"\$(stat -c '%u:%a:%h' $path)\" = 0:$mode:1 || exit 77")
                append(hashGuard(path, digest))
            }
            appendLine("set -e")
            appendLine("stage=\$(mktemp '$BASE/.trust.XXXXXXXX')")
            appendLine("trap 'rm -f \"\$stage\"' EXIT")
            appendLine("ovc_key='/data/user/0/${app.packageName}/files/root-channel.key'")
            appendLine("ovc_access='/data/adb/open-voyah-controls-access'")
            appendLine("test ! -L \"\$ovc_key\" && test -f \"\$ovc_key\" && test \"\$(stat -c '%u:%a:%h' \"\$ovc_key\")\" = '${Process.myUid()}:600:1' || exit 77")
            appendLine("test \"\$(wc -c < \"\$ovc_key\")\" = 65 || exit 77")
            appendLine("test ! -L \"\$ovc_access\" && test \"\$(stat -c '%u:%a' \"\$ovc_access\")\" = 0:700 || exit 77")
            appendLine("for ovc_file in key uid; do test ! -L \"\$ovc_access/\$ovc_file\" && test -f \"\$ovc_access/\$ovc_file\" && test \"\$(stat -c '%u:%a:%h' \"\$ovc_access/\$ovc_file\")\" = 0:600:1 || exit 77; done")
            appendLine("test \"\$(cat \"\$ovc_access/uid\")\" = '${Process.myUid()}' || exit 77")
            appendLine("ovc_key_stamp=\$(stat -c '%d:%i:%s:%Y:%Z' \"\$ovc_key\") || exit 77")
            appendLine("ovc_key_hash=\$(sha256sum \"\$ovc_key\") && ovc_key_hash=\${ovc_key_hash%% *} || exit 77")
            appendLine("ovc_access_hash=\$(sha256sum \"\$ovc_access/key\") && ovc_access_hash=\${ovc_access_hash%% *} || exit 77")
            appendLine("test \"\$ovc_key_hash\" = \"\$ovc_access_hash\" && test \"\$(stat -c '%d:%i:%s:%Y:%Z' \"\$ovc_key\")\" = \"\$ovc_key_stamp\" || exit 77")
            appendLine("cat > \"\$stage\" <<'OVC_TRUST_RECORD'")
            append(receipt)
            appendLine("OVC_TRUST_RECORD")
            appendLine("printf 'lifetime\\t${app.packageName}\\t${Process.myUid()}\\t%s\\t%s\\n' \"\$ovc_key_stamp\" \"\$ovc_key_hash\" >> \"\$stage\"")
            appendLine("chmod 600 \"\$stage\" && sync && mv -f \"\$stage\" '$BASE/trust.tsv' && sync")
        }
        execute(script)
    }

    private fun evidence(): Proof {
        proof?.let { return it }
        val profile = review
        val expected = BuildConfig.RELEASE_CERT_SHA256.lowercase(Locale.ROOT)
        val release = when {
            BuildConfig.DEBUG -> "debug_build"
            app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 -> "debuggable"
            !expected.matches(ReviewedFirmware.sha256) -> "release_signer_unset"
            Process.myUserHandle() != UserHandle.getUserHandleForUid(0) -> "secondary_user"
            else -> null
        }
        if (release != null) {
            logRefusal(release)
            throw Refused(UiText.get(R.string.production_release_required))
        }
        val packages = listOf(installed(app.packageName, expected, null)) +
            profile.packages.map { installed(it.name, it.signer, it.apk) }
        val guard = deviceGuard(profile.device)
        val broker = AuthorizedRootChannel.existing(app)
        val response = broker.execute(purpose = "evidence", script = buildString {
            append(guard)
            for (pkg in packages) {
                val path = shell(pkg.path)
                append(pathGuard(pkg))
                appendLine("ovc_stamp=\$(stat -c '%d:%i:%s:%Y:%Z' $path) || exit 77")
                appendLine("ovc_digest=\$(sha256sum $path) || exit 77")
                appendLine("ovc_digest=\${ovc_digest%% *}")
                pkg.expectedHash?.let { appendLine("test \"\$ovc_digest\" = '$it' || exit 77") }
                appendLine("test \"\$(stat -c '%d:%i:%s:%Y:%Z' $path)\" = \"\$ovc_stamp\" || exit 77")
                appendLine("printf 'OVC_PROOF\\t%s\\t%s\\n' \"\$ovc_stamp\" \"\$ovc_digest\"")
            }
        })
        val rows = response.lineSequence().filter { it.startsWith("OVC_PROOF\t") }.toList()
        if (rows.size != packages.size) {
            logRefusal("proof_rows")
            throw Refused(UiText.get(R.string.production_evidence_changed))
        }
        val verified = rows.zip(packages).map { (line, pkg) ->
            val columns = line.split('\t')
            val invalid = when {
                columns.size != 3 || !columns[1].matches(stampPattern) || !columns[2].matches(ReviewedFirmware.sha256) ->
                    "proof_format"
                pkg.expectedHash?.let { it != columns[2] } == true -> "apk_hash"
                else -> null
            }
            if (invalid != null) {
                logRefusal(invalid, packageName = pkg.name)
                throw Refused(UiText.get(R.string.production_evidence_changed))
            }
            VerifiedPackage(pkg.name, pkg.path, columns[1], columns[2])
        }
        val commandGuard = buildString {
            append(guard)
            for ((pkg, record) in packages.zip(verified)) {
                append(pathGuard(pkg))
                appendLine("test \"\$(stat -c '%d:%i:%s:%Y:%Z' ${shell(pkg.path)})\" = '${record.stamp}' || exit 77")
            }
        }
        return Proof(broker, verified, commandGuard).also { proof = it }
    }

    @Suppress("DEPRECATION")
    private fun installed(name: String, signer: String, expectedHash: String?): Installed {
        val info = try {
            app.packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (error: PackageManager.NameNotFoundException) {
            logRefusal("package_missing", error, name)
            throw Refused(UiText.get(R.string.production_evidence_changed), error)
        }
        val details = info.applicationInfo ?: run {
            logRefusal("application_info_missing", packageName = name)
            throw Refused(UiText.get(R.string.production_evidence_changed))
        }
        val currentSigner = info.signingInfo?.takeUnless { it.hasMultipleSigners() }
            ?.signingCertificateHistory?.lastOrNull()?.toByteArray()
        val path = details.sourceDir
        val invalid = when {
            currentSigner == null -> "signer_unavailable"
            ReviewedFirmware.digest(currentSigner) != signer -> "wrong_signer"
            expectedHash != null && details.flags and ApplicationInfo.FLAG_SYSTEM == 0 -> "not_system_app"
            !details.splitSourceDirs.isNullOrEmpty() -> "split_apk"
            path == null || !path.matches(apkPath) || path.split('/').drop(1).any { it.isEmpty() || it == "." || it == ".." } ->
                "apk_path"
            else -> null
        }
        if (invalid != null || path == null) {
            logRefusal(invalid ?: "apk_path", packageName = name)
            throw Refused(UiText.get(R.string.production_evidence_changed))
        }
        return Installed(name, path, expectedHash)
    }

    private fun deviceGuard(device: ReviewedFirmware.Device) = """
        PATH=/system/bin:/system/xbin
        export PATH
        umask 077
        test "${'$'}(id -u)" = 0 && test "${'$'}(getenforce)" = Enforcing || exit 77
        test "${'$'}(am get-current-user)" = 0 || exit 77
        test "${'$'}(getprop ro.build.fingerprint)" = ${shell(device.fingerprint)} || exit 77
        test "${'$'}(getprop ro.build.version.sdk)" = ${shell(device.sdk)} || exit 77
        test "${'$'}(getprop ro.product.cpu.abi)" = ${shell(device.abi)} || exit 77
        test "${'$'}(getprop ro.product.name)" = ${shell(device.product)} || exit 77
        for ovc_dir in /data/adb '$BASE'; do
            test ! -L "${'$'}ovc_dir" && test -d "${'$'}ovc_dir" || exit 77
            test "${'$'}(stat -c '%u:%a' "${'$'}ovc_dir")" = 0:700 || exit 77
        done
    """.trimIndent() + "\n"

    private fun pathGuard(pkg: Installed): String {
        val path = shell(pkg.path)
        return "test \"\$(pm path --user 0 ${shell(pkg.name)})\" = ${shell("package:${pkg.path}")} || exit 77\n" +
            "test ! -L $path && test -f $path || exit 77\n"
    }

    /** Every refusal reaches the screen as the same localized text; this entry names the check that failed. */
    private fun logRefusal(reason: String, error: Throwable? = null, packageName: String? = null) {
        val fields = if (packageName == null) arrayOf<Any?>("outcome", "refused", "reason", reason)
            else arrayOf<Any?>("outcome", "refused", "reason", reason, "package", packageName)
        when {
            refusals.isRepeat("$reason:$packageName") -> ControlsLog.debug("production_evidence", *fields, "error", error)
            error != null -> ControlsLog.warn("production_evidence", error, *fields)
            else -> ControlsLog.warn("production_evidence", *fields)
        }
    }

    private data class Installed(val name: String, val path: String, val expectedHash: String?)
    private data class VerifiedPackage(val name: String, val path: String, val stamp: String, val apkHash: String)
    private data class Proof(val broker: AuthorizedRootChannel, val packages: List<VerifiedPackage>, val guard: String)

    companion object {
        const val BASE = "/data/adb/open-voyah-controls"
        const val ACTIVATION_GUARD = "test ! -e '$BASE/recovery.blocked' && test ! -L '$BASE/recovery.blocked' || exit 78\n" +
            "test ! -e '$BASE/recovery.visual-pending' && test ! -L '$BASE/recovery.visual-pending' || exit 78\n" +
            "test ! -e '$BASE/update.pending' && test ! -L '$BASE/update.pending' || exit 78\n" +
            "ovc_old=/data/local/tmp/open-voyah-controls\n" +
            "for ovc_dir in \"\$ovc_old\" \"\$ovc_old/locale-setting\" \"\$ovc_old/locale-vehicle\" " +
            "\"\$ovc_old/locale-bluetooth\" \"\$ovc_old/window\" \"\$ovc_old/navigation\" \"\$ovc_old/can\"; do\n" +
            "test ! -e \"\$ovc_dir/enabled\" && test ! -L \"\$ovc_dir/enabled\" || exit 78\ndone\n"
        private val refusals = RepeatedFailureFilter()
        private val artifactPath = Regex("(?:[a-z0-9-]+/)?[a-z0-9.-]+")
        private val apkPath = Regex("/[A-Za-z0-9_./+=~-]{1,511}\\.apk")
        private val stampPattern = Regex("[0-9]+(?::[0-9]+){4}")

        private fun shell(text: String) = "'${text.replace("'", "'\"'\"'")}'"

        private fun hashGuard(quotedPath: String, expected: String): String =
            "ovc_hash=\$(sha256sum $quotedPath) || exit 77\n" +
                "test \"\${ovc_hash%% *}\" = '$expected' || exit 77\n"
    }
}
