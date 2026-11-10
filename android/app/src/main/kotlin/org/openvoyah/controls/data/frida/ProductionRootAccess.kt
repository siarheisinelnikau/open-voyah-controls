package org.openvoyah.controls.data.frida

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
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
            throw error
        } catch (error: Exception) {
            throw Refused(UiText.get(R.string.production_evidence_changed), error)
        }
    }
    private val runtime by lazy {
        try {
            ReviewedFirmware.runtime(app)
        } catch (error: Exception) {
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
            verified.broker.execute(verified.guard + script)
        } catch (error: Refused) {
            proof = null
            throw error
        }
    }

    @Throws(IOException::class)
    fun requireActivation() {
        execute(ACTIVATION_GUARD)
    }

    @Throws(IOException::class)
    fun executeActive(script: String): String = execute(ACTIVATION_GUARD + script)

    fun injectorGuard(): String {
        val injector = shell(injectorPath())
        return "test ! -L $injector && test -f $injector && test -x $injector || exit 77\n" +
            "test \"\$(stat -c '%u:%a:%h' $injector)\" = 0:500:1 || exit 77\n" + hashGuard(injector, runtime.digest)
    }

    @Synchronized
    @Throws(IOException::class)
    fun sealDeployment(artifacts: Map<String, String>) {
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
            appendLine("cat > \"\$stage\" <<'OVC_TRUST_RECORD'")
            append(receipt)
            appendLine("OVC_TRUST_RECORD")
            appendLine("chmod 600 \"\$stage\" && sync && mv -f \"\$stage\" '$BASE/trust.tsv' && sync")
        }
        execute(script)
    }

    private fun evidence(): Proof {
        proof?.let { return it }
        val profile = review
        val expected = BuildConfig.RELEASE_CERT_SHA256.lowercase(Locale.ROOT)
        if (BuildConfig.DEBUG || app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 ||
            !expected.matches(ReviewedFirmware.sha256) || Process.myUserHandle() != UserHandle.getUserHandleForUid(0)
        ) {
            throw Refused(UiText.get(R.string.production_release_required))
        }
        val packages = listOf(installed(app.packageName, expected, null)) +
            profile.packages.map { installed(it.name, it.signer, it.apk) }
        val guard = deviceGuard(profile.device)
        val broker = AuthorizedRootBroker.existing()
        val response = broker.execute(buildString {
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
        if (rows.size != packages.size) throw Refused(UiText.get(R.string.production_evidence_changed))
        val verified = rows.zip(packages).map { (line, pkg) ->
            val columns = line.split('\t')
            if (columns.size != 3 || !columns[1].matches(stampPattern) || !columns[2].matches(ReviewedFirmware.sha256) ||
                pkg.expectedHash?.let { it != columns[2] } == true
            ) {
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
            throw Refused(UiText.get(R.string.production_evidence_changed), error)
        }
        val details = info.applicationInfo ?: throw Refused(UiText.get(R.string.production_evidence_changed))
        val currentSigner = info.signingInfo?.takeUnless { it.hasMultipleSigners() }
            ?.signingCertificateHistory?.lastOrNull()?.toByteArray()
        val path = details.sourceDir
        if (currentSigner == null || ReviewedFirmware.digest(currentSigner) != signer ||
            (expectedHash != null && details.flags and ApplicationInfo.FLAG_SYSTEM == 0) ||
            !details.splitSourceDirs.isNullOrEmpty() || path == null || !path.matches(apkPath) ||
            path.split('/').drop(1).any { it.isEmpty() || it == "." || it == ".." }
        ) {
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

    private data class Installed(val name: String, val path: String, val expectedHash: String?)
    private data class VerifiedPackage(val name: String, val path: String, val stamp: String, val apkHash: String)
    private data class Proof(val broker: AuthorizedRootBroker, val packages: List<VerifiedPackage>, val guard: String)

    companion object {
        const val BASE = "/data/adb/open-voyah-controls"
        const val ACTIVATION_GUARD = "test ! -e '$BASE/recovery.blocked' && test ! -L '$BASE/recovery.blocked' || exit 78\n" +
            "test ! -e '$BASE/recovery.visual-pending' && test ! -L '$BASE/recovery.visual-pending' || exit 78\n" +
            "ovc_old=/data/local/tmp/open-voyah-controls\n" +
            "for ovc_dir in \"\$ovc_old\" \"\$ovc_old/locale-setting\" \"\$ovc_old/locale-vehicle\" " +
            "\"\$ovc_old/locale-bluetooth\" \"\$ovc_old/window\" \"\$ovc_old/navigation\"; do\n" +
            "test ! -e \"\$ovc_dir/enabled\" && test ! -L \"\$ovc_dir/enabled\" || exit 78\ndone\n"
        private val artifactPath = Regex("(?:[a-z0-9-]+/)?[a-z0-9.-]+")
        private val apkPath = Regex("/[A-Za-z0-9_./+=~-]{1,511}\\.apk")
        private val stampPattern = Regex("[0-9]+(?::[0-9]+){4}")

        private fun shell(text: String) = "'${text.replace("'", "'\"'\"'")}'"

        private fun hashGuard(quotedPath: String, expected: String): String =
            "ovc_hash=\$(sha256sum $quotedPath) || exit 77\n" +
                "test \"\${ovc_hash%% *}\" = '$expected' || exit 77\n"
    }
}
