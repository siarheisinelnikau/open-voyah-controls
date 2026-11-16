package org.openvoyah.controls.platform.updates

import kotlinx.coroutines.ensureActive
import org.openvoyah.controls.core.updates.ReleasePolicy
import org.openvoyah.controls.data.updates.UpdateException
import org.openvoyah.controls.data.updates.UpdateProblem
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** A bounded digest check over the exact bytes sent to Android, or read back from the installed base APK. */
internal object UpdateApkBytes {
    suspend fun transfer(input: InputStream, output: OutputStream?, bytes: Long, sha256: String,
                         progress: (Float) -> Unit = {}) {
        if (bytes !in 1..ReleasePolicy.MAX_APK_BYTES || !ReleasePolicy.isSha256(sha256)) {
            throw UpdateException(UpdateProblem.SIZE_OR_DIGEST)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        var reportedAt = System.nanoTime()
        progress(0f)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count == -1) break
            copied += count
            if (copied > bytes) throw UpdateException(UpdateProblem.SIZE_OR_DIGEST)
            digest.update(buffer, 0, count)
            output?.write(buffer, 0, count)
            val now = System.nanoTime()
            if (now - reportedAt >= 300_000_000L) {
                progress(copied.toFloat() / bytes)
                reportedAt = now
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (copied != bytes || !actual.equals(sha256, ignoreCase = true)) throw UpdateException(UpdateProblem.SIZE_OR_DIGEST)
        coroutineContext.ensureActive()
        progress(1f)
    }
}
