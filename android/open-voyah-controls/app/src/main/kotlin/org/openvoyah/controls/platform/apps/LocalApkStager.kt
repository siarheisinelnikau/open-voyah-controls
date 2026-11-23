package org.openvoyah.controls.platform.apps

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import org.openvoyah.controls.diagnostics.ControlsLog

internal enum class LocalApkRefusal { OWN_PACKAGE, INVALID_APK, CHANGED }
internal class LocalApkStagingFailure(val refusal: LocalApkRefusal) : IOException(refusal.name)

internal data class LocalApkFileIdentity(
    val device: Long, val inode: Long, val bytes: Long, val modified: Long, val changed: Long, val mode: Int,
)

internal data class StagedLocalApk(
    val file: File, val packageName: String, val bytes: Long, val digest: String, val identity: LocalApkFileIdentity,
)

/** A content provider is read once. Only the private, parsed and hashed snapshot can reach a session. */
internal class LocalApkStager(
    context: Context,
    private val open: (Uri, CancellationSignal) -> InputStream? = { uri, signal ->
        context.contentResolver.openAssetFileDescriptor(uri, "r", signal)?.let { descriptor ->
            try { descriptor.createInputStream() } catch (error: Exception) { descriptor.close(); throw error }
        }
    },
    private val inspect: (File) -> String? = { file ->
        // Early Android 9 archive readers collect certificates only when the legacy flag is also set.
        // Continue requiring the modern SigningInfo result; the extra flag never replaces verification.
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(file.path, flags)
        if (info?.applicationInfo == null || info.signingInfo?.apkContentsSigners.isNullOrEmpty()) null else info.packageName
    },
) {
    private val context = context.applicationContext
    private val directory = File(this.context.cacheDir, DIRECTORY)

    /** The caller serializes this operation and cleanupInterrupted with the shared staging mutex. */
    suspend fun stage(uri: Uri): StagedLocalApk {
        var owned: File? = null
        try {
            return try { withTimeout(300_000) {
                if (uri.scheme != "content") throw LocalApkStagingFailure(LocalApkRefusal.INVALID_APK)
                ensureDirectory()
                val file = File(directory, "local-${UUID.randomUUID()}.apk")
                check(file.createNewFile())
                owned = file
                Os.chmod(file.path, 0x180)
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                val signal = CancellationSignal()
                val input = AtomicReference<InputStream?>()
                coroutineScope {
                    val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally {
                            runCatching { signal.cancel() }
                            runCatching { input.get()?.close() }
                        }
                    }
                    try {
                        val source = open(uri, signal) ?: throw LocalApkStagingFailure(LocalApkRefusal.INVALID_APK)
                        input.set(source)
                        source.use {
                            coroutineContext.ensureActive()
                            FileOutputStream(file).use { output ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val count = source.read(buffer)
                                    if (count == -1) break
                                    if (count == 0) throw LocalApkStagingFailure(LocalApkRefusal.INVALID_APK)
                                    bytes += count
                                    if (bytes > MAX_APK_BYTES) throw LocalApkStagingFailure(LocalApkRefusal.INVALID_APK)
                                    digest.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                }
                                if (bytes == 0L) throw LocalApkStagingFailure(LocalApkRefusal.INVALID_APK)
                                output.fd.sync()
                            }
                        }
                    } finally { cancellation.cancelAndJoin() }
                }
                coroutineContext.ensureActive()
                check(file.setReadOnly())
                val identity = identity(file)
                if (identity.bytes != bytes) throw LocalApkStagingFailure(LocalApkRefusal.CHANGED)
                val packageName = try { inspect(file) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    ControlsLog.warn("apk_install", failure, "step", "inspect")
                    null
                }
                if (packageName.isNullOrBlank()) throw LocalApkStagingFailure(LocalApkRefusal.INVALID_APK)
                if (packageName == context.packageName) throw LocalApkStagingFailure(LocalApkRefusal.OWN_PACKAGE)
                val staged = StagedLocalApk(file, packageName, bytes, digest.digest().toHexString(), identity)
                // Archive parsing and later session staging must observe the original provider snapshot.
                copy(staged, null)
                coroutineContext.ensureActive()
                staged
            } } catch (timeout: TimeoutCancellationException) {
                // A stalled document provider is a failed read, not the user's cancellation. A real cancellation of
                // the caller is still rethrown as one.
                coroutineContext.ensureActive()
                ControlsLog.warn("apk_install", timeout, "step", "stage", "outcome", "provider_timeout")
                throw IOException("The APK document did not finish reading in time", timeout)
            }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { owned?.let { discard(it) } }
            throw error
        }
    }

    suspend fun copy(staged: StagedLocalApk, output: OutputStream?) {
        if (identity(staged.file) != staged.identity) throw LocalApkStagingFailure(LocalApkRefusal.CHANGED)
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        FileInputStream(staged.file).use { input ->
            if (checkedIdentity(Os.fstat(input.fd)) != staged.identity) throw LocalApkStagingFailure(LocalApkRefusal.CHANGED)
            val buffer = ByteArray(128 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                bytes += count
                if (bytes > staged.bytes) throw LocalApkStagingFailure(LocalApkRefusal.CHANGED)
                digest.update(buffer, 0, count)
                output?.write(buffer, 0, count)
            }
            if (bytes != staged.bytes || digest.digest().toHexString() != staged.digest ||
                checkedIdentity(Os.fstat(input.fd)) != staged.identity || identity(staged.file) != staged.identity) {
                throw LocalApkStagingFailure(LocalApkRefusal.CHANGED)
            }
        }
    }

    fun discard(staged: StagedLocalApk) = discard(staged.file)

    fun cleanupInterrupted() {
        if (!directory.exists()) return
        ensureDirectory()
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            var scanned = 0
            for (entry in entries) {
                check(++scanned <= MAX_STAGED_FILES)
                val file = entry.toFile()
                if (OWNED_NAME.matches(file.name)) discard(file)
            }
        }
    }

    private fun discard(file: File) {
        ensureDirectory()
        if (!file.exists()) return
        identity(file)
        check(file.delete())
    }

    private fun identity(file: File): LocalApkFileIdentity {
        if (!OWNED_NAME.matches(file.name) || file.canonicalFile.parentFile != directory.canonicalFile ||
            file.canonicalFile.name != file.name) throw LocalApkStagingFailure(LocalApkRefusal.CHANGED)
        return checkedIdentity(Os.lstat(file.path))
    }

    private fun checkedIdentity(stat: StructStat): LocalApkFileIdentity {
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != Process.myUid() || stat.st_nlink != 1L ||
            stat.st_mode and 0x3f != 0) throw LocalApkStagingFailure(LocalApkRefusal.CHANGED)
        return LocalApkFileIdentity(stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime, stat.st_ctime, stat.st_mode)
    }

    private fun ensureDirectory() {
        val parent = context.cacheDir.canonicalFile
        check(directory.canonicalFile.parentFile == parent && directory.canonicalFile.name == DIRECTORY)
        val stat = try { Os.lstat(directory.path) } catch (missing: ErrnoException) {
            if (missing.errno != OsConstants.ENOENT) throw missing
            Os.mkdir(directory.path, 0x1c0)
            Os.lstat(directory.path)
        }
        check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid() && stat.st_mode and 0x1ff == 0x1c0)
    }

    companion object {
        const val MAX_APK_BYTES = 2_147_483_647L
        const val DIRECTORY = "local-apk-staging"
        private const val MAX_STAGED_FILES = 32
        private val OWNED_NAME = Regex("local-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.apk")
    }
}
