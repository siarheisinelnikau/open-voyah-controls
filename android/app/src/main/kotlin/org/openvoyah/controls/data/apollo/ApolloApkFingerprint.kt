package org.openvoyah.controls.data.apollo

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ApolloApkFingerprint(val status: String, val size: Long? = null, val sha256: String? = null)

// The inspected factory settings base APK is 413,825,468 bytes.
private const val MAX_APK_BYTES = 512L * 1024 * 1024

internal suspend fun fingerprintApolloApk(file: File?, maxBytes: Long = MAX_APK_BYTES): ApolloApkFingerprint {
    if (file == null) return ApolloApkFingerprint("unavailable")
    val context = currentCoroutineContext()
    val path = file.toPath()
    return try {
        val before = Files.readAttributes(path, BasicFileAttributes::class.java)
        val size = before.size()
        if (!before.isRegularFile || size <= 0) return ApolloApkFingerprint("unavailable")
        if (size > maxBytes) return ApolloApkFingerprint("size_limit", size)
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        var read = 0L
        file.inputStream().use { stream ->
            while (true) {
                context.ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                read += count
                if (read > size) return ApolloApkFingerprint("changed")
                hash.update(buffer, 0, count)
            }
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java)
        if (read != size || after.size() != size || after.fileKey() != before.fileKey() ||
            after.lastModifiedTime() != before.lastModifiedTime()) ApolloApkFingerprint("changed")
        else ApolloApkFingerprint("observed", size, apolloHex(hash.digest()))
    } catch (_: IOException) { ApolloApkFingerprint("unavailable") }
    catch (_: SecurityException) { ApolloApkFingerprint("access_denied") }
}

internal fun apolloHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
