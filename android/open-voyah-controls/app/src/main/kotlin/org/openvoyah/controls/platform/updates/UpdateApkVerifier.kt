package org.openvoyah.controls.platform.updates

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.core.updates.ApkUpdatePolicy
import org.openvoyah.controls.core.updates.ReleasePolicy
import org.openvoyah.controls.data.frida.ReviewedFirmware
import org.openvoyah.controls.data.updates.UpdateException
import org.openvoyah.controls.data.updates.UpdateProblem
import org.openvoyah.controls.diagnostics.ControlsLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

internal data class VerifiedUpdate(
    val release: ReleasePolicy.Candidate,
    val file: File,
    val identity: ApkUpdatePolicy.Identity,
    val installedIdentity: ApkUpdatePolicy.Identity,
    val installedPath: String,
    val installedUpdatedAt: Long,
)

internal interface UpdateVerifier {
    val installationProblem: UpdateProblem?
    suspend fun verify(release: ReleasePolicy.Candidate, file: File): VerifiedUpdate
}

/** Archive inspection is local. No runtime is downloaded, deployed or granted new authority here. */
internal class UpdateApkVerifier(context: Context) : UpdateVerifier {
    private val context = context.applicationContext
    private val packages get() = context.packageManager
    private val directory = File(this.context.cacheDir, "official-updates")

    override val installationProblem: UpdateProblem?
        get() = if (BuildConfig.DEBUG || !ReleasePolicy.isSha256(BuildConfig.RELEASE_CERT_SHA256)) UpdateProblem.RELEASE_SIGNER else null

    override suspend fun verify(release: ReleasePolicy.Candidate, file: File): VerifiedUpdate = withContext(Dispatchers.IO) {
        installationProblem?.let { throw UpdateException(it) }
        try {
            val canonical = file.canonicalFile
            if (!file.isFile || canonical.parentFile != directory.canonicalFile || canonical.name != file.name ||
                file.length() != release.bytes || hash(file) != release.sha256) throw UpdateException(UpdateProblem.SIZE_OR_DIGEST)
            val runtime = ReviewedFirmware.runtime(context).let { ApkUpdatePolicy.Runtime(it.version, it.name, it.digest) }
            val before = packages.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val installed = identity(before, emptySet(), runtime)
            val contents = UpdateArchiveContents.read(canonical)
            // Early Android 9 needs both flags to collect archive certificates. Pin only modern current signers.
            @Suppress("DEPRECATION")
            val archiveFlags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
            val archive = packages.getPackageArchiveInfo(canonical.path, archiveFlags)
                ?: throw UpdateException(UpdateProblem.PACKAGE)
            val candidate = identity(archive, contents.nativeAbis, contents.runtime)
            val refused = ApkUpdatePolicy.verify(release, installed, candidate, BuildConfig.RELEASE_CERT_SHA256,
                Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toSet(), runtime)
            if (refused != null) throw UpdateException(UpdateProblem.valueOf(refused.name))
            val after = packages.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            if (!sameInstalled(before, after) || file.length() != release.bytes) throw UpdateException(UpdateProblem.VERSION)
            coroutineContext.ensureActive()
            VerifiedUpdate(release, canonical, candidate, installed, checkNotNull(after.applicationInfo).sourceDir, after.lastUpdateTime)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: UpdateException) {
            throw error
        } catch (error: Exception) {
            ControlsLog.warn("update_verify", error, "stage", "package", "problem", UpdateProblem.PACKAGE)
            throw UpdateException(UpdateProblem.PACKAGE)
        }
    }

    /** Preparation must call this again, then hash bytes as they are copied into its owned session. */
    suspend fun reverify(update: VerifiedUpdate): VerifiedUpdate {
        val current = verify(update.release, update.file)
        if (current.installedPath != update.installedPath || current.installedUpdatedAt != update.installedUpdatedAt ||
            current.installedIdentity.versionCode != update.installedIdentity.versionCode ||
            current.installedIdentity.signers != update.installedIdentity.signers) throw UpdateException(UpdateProblem.VERSION)
        return current
    }

    private fun identity(info: PackageInfo, abis: Set<String>, runtime: ApkUpdatePolicy.Runtime?): ApkUpdatePolicy.Identity {
        val app = info.applicationInfo ?: throw UpdateException(UpdateProblem.PACKAGE)
        val signers = info.signingInfo?.takeUnless { it.hasMultipleSigners() }?.apkContentsSigners
            ?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()
        return ApkUpdatePolicy.Identity(info.packageName, info.versionName, info.longVersionCode, app.minSdkVersion,
            app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            !info.splitNames.isNullOrEmpty() || !app.splitSourceDirs.isNullOrEmpty(), signers, abis, runtime)
    }

    private fun sameInstalled(before: PackageInfo, after: PackageInfo): Boolean =
        before.applicationInfo?.sourceDir == after.applicationInfo?.sourceDir && before.lastUpdateTime == after.lastUpdateTime &&
            before.longVersionCode == after.longVersionCode && before.versionName == after.versionName &&
            before.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() ==
            after.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()

    private suspend fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                bytes += count
                if (bytes > ReleasePolicy.MAX_APK_BYTES) throw UpdateException(UpdateProblem.SIZE_OR_DIGEST)
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal data class UpdateArchiveContents(
    val runtime: ApkUpdatePolicy.Runtime,
    val nativeAbis: Set<String>,
    val runtimeBytes: Long? = null,
) {
    companion object {
        private const val RUNTIME_PATH = "assets/production/FRIDA_RUNTIME.json"
        private const val MAX_RUNTIME_BYTES = 65_536

        /** Reads only the exact metadata entry. It never extracts untrusted archive paths. */
        suspend fun read(file: File, requireRuntimeSize: Boolean = false): UpdateArchiveContents {
            try {
                ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    val abis = mutableSetOf<String>()
                    var runtime: ApkUpdatePolicy.Runtime? = null
                    var runtimeBytes: Long? = null
                    var manifests = 0
                    var count = 0
                    while (entries.hasMoreElements()) {
                        coroutineContext.ensureActive()
                        if (++count > 50_000) throw UpdateException(UpdateProblem.PACKAGE)
                        val entry = entries.nextElement()
                        if (entry.name == "AndroidManifest.xml" && ++manifests > 1) throw UpdateException(UpdateProblem.PACKAGE)
                        if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                            val parts = entry.name.split('/')
                            if (parts.size != 3 || parts[1].isEmpty() || parts[1] == "." || parts[1] == "..") {
                                throw UpdateException(UpdateProblem.ABI)
                            }
                            abis += parts[1]
                        }
                        if (entry.name != RUNTIME_PATH) continue
                        if (runtime != null || entry.isDirectory || entry.size > MAX_RUNTIME_BYTES) throw UpdateException(UpdateProblem.RUNTIME)
                        val output = ByteArrayOutputStream()
                        zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(4096)
                            while (true) {
                                coroutineContext.ensureActive()
                                val bytes = input.read(buffer)
                                if (bytes == -1) break
                                if (output.size() + bytes > MAX_RUNTIME_BYTES) throw UpdateException(UpdateProblem.RUNTIME)
                                output.write(buffer, 0, bytes)
                            }
                        }
                        val metadata = JSONObject(output.toString(Charsets.UTF_8.name()))
                        runtime = ApkUpdatePolicy.Runtime(metadata.getString("version"), metadata.getString("file"),
                            metadata.getString("binary_sha256"))
                        if (requireRuntimeSize) runtimeBytes = when (val size = metadata.get("size")) {
                            is Int -> size.toLong()
                            is Long -> size
                            else -> throw UpdateException(UpdateProblem.RUNTIME)
                        }
                    }
                    if (manifests != 1) throw UpdateException(UpdateProblem.PACKAGE)
                    return UpdateArchiveContents(runtime ?: throw UpdateException(UpdateProblem.RUNTIME), abis.toSet(), runtimeBytes)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: UpdateException) {
                throw error
            } catch (error: Exception) {
                ControlsLog.warn("update_verify", error, "stage", "archive", "problem", UpdateProblem.RUNTIME)
                throw UpdateException(UpdateProblem.RUNTIME)
            }
        }
    }
}
