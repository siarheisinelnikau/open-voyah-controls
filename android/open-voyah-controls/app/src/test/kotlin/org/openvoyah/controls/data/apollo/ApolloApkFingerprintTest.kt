package org.openvoyah.controls.data.apollo

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApolloApkFingerprintTest {
    @Test fun streamsBaseApksLargerThanTheOldSixtyFourMiBLimit() = runBlocking {
        val file = File.createTempFile("apollo-fingerprint", ".apk")
        try {
            val bytes = 64L * 1024 * 1024 + 1
            RandomAccessFile(file, "rw").use { it.setLength(bytes) }
            assertEquals(ApolloApkFingerprint("observed", bytes,
                "91990977345985aaf03af1358f4f989d7eaf985b58529efb72f613c588f6599a"), fingerprintApolloApk(file))
            assertEquals(ApolloApkFingerprint("size_limit", bytes), fingerprintApolloApk(file, maxBytes = bytes - 1))
        } finally { file.delete() }
    }

    @Test fun anAbsentPackageCannotAppearAsAHashMatch() = runBlocking {
        val result = fingerprintApolloApk(null)
        assertEquals("unavailable", result.status)
        assertNull(result.sha256)
    }
}
