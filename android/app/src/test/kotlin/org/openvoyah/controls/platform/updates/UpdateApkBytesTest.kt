package org.openvoyah.controls.platform.updates

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.openvoyah.controls.core.updates.ReleasePolicy
import org.openvoyah.controls.data.updates.UpdateException
import org.openvoyah.controls.data.updates.UpdateProblem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

class UpdateApkBytesTest {
    @Test fun validBytesAreCopiedAndIndependentlyVerifiable() = runBlocking {
        val bytes = ByteArray(150_000) { (it % 239).toByte() }
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Float>()
        UpdateApkBytes.transfer(ByteArrayInputStream(bytes), output, bytes.size.toLong(), hash(bytes)) { progress += it }
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(0f, progress.first(), 0f)
        assertEquals(1f, progress.last(), 0f)
        UpdateApkBytes.transfer(ByteArrayInputStream(output.toByteArray()), null, bytes.size.toLong(), hash(bytes))
    }

    @Test fun corruptionTruncationAndExtraBytesAreRejected() = runBlocking {
        val original = ByteArray(100) { it.toByte() }
        for (received in listOf(original.copyOf(99), original.copyOf(101), original.copyOf().apply { this[12] = 77 })) {
            try {
                UpdateApkBytes.transfer(ByteArrayInputStream(received), ByteArrayOutputStream(), original.size.toLong(), hash(original))
                fail("Mismatched bytes accepted")
            } catch (failure: UpdateException) { assertEquals(UpdateProblem.SIZE_OR_DIGEST, failure.problem) }
        }
    }

    @Test fun unboundedOrInvalidDeclarationsFailBeforeReading() = runBlocking {
        val input = object : InputStream() { override fun read(): Int = error("Must not read invalid input") }
        for ((bytes, hash) in listOf(0L to "a".repeat(64), ReleasePolicy.MAX_APK_BYTES + 1 to "a".repeat(64), 1L to "invalid")) {
            try { UpdateApkBytes.transfer(input, null, bytes, hash); fail("Invalid size or digest accepted") }
            catch (failure: UpdateException) { assertEquals(UpdateProblem.SIZE_OR_DIGEST, failure.problem) }
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
