package org.openvoyah.controls.platform.updates

import android.test.AndroidTestCase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.openvoyah.controls.data.updates.UpdateException
import org.openvoyah.controls.data.updates.UpdateProblem
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdateArchiveContentsTest : AndroidTestCase() {
    fun testReadsOnlyExactRuntimeContractAndNativeArchitectures() = runBlocking {
        archive(mapOf(MANIFEST to byteArrayOf(1), RUNTIME to runtime(), "lib/arm64-v8a/libfixture.so" to byteArrayOf(2))) { file ->
            val contents = UpdateArchiveContents.read(file)
            assertEquals("17.26.2", contents.runtime.version)
            assertEquals("open-voyah-controls-frida-17.26.2", contents.runtime.file)
            assertEquals(setOf("arm64-v8a"), contents.nativeAbis)
        }
    }

    fun testMissingMalformedAndOversizedRuntimeMetadataCannotBeAccepted() = runBlocking {
        for (entries in listOf(mapOf(MANIFEST to byteArrayOf(1)),
            mapOf(MANIFEST to byteArrayOf(1), RUNTIME to "{}".toByteArray()),
            mapOf(MANIFEST to byteArrayOf(1), RUNTIME to ByteArray(65_537) { ' '.code.toByte() }),
            mapOf(MANIFEST to byteArrayOf(1), "assets/FRIDA_RUNTIME.json" to runtime()))) {
            archive(entries) { file -> expect(UpdateProblem.RUNTIME) { UpdateArchiveContents.read(file) } }
        }
    }

    fun testDuplicateRuntimeEntriesAreRejectedRatherThanSelectedByZipOrder() = runBlocking {
        val alias = RUNTIME.dropLast(1) + "m"
        archive(mapOf(MANIFEST to byteArrayOf(1), RUNTIME to runtime(), alias to runtime())) { file ->
            val bytes = file.readBytes()
            val from = alias.toByteArray()
            val to = RUNTIME.toByteArray()
            var replacements = 0
            for (start in 0..bytes.size - from.size) {
                if (from.indices.all { bytes[start + it] == from[it] }) {
                    to.copyInto(bytes, start)
                    replacements++
                }
            }
            assertEquals(2, replacements)
            file.writeBytes(bytes)
            expect(UpdateProblem.RUNTIME) { UpdateArchiveContents.read(file) }
        }
    }

    fun testMissingManifestAndMalformedNativePathAreRejected() = runBlocking {
        archive(mapOf(RUNTIME to runtime())) { file -> expect(UpdateProblem.PACKAGE) { UpdateArchiveContents.read(file) } }
        archive(mapOf(MANIFEST to byteArrayOf(1), RUNTIME to runtime(), "lib/../fixture.so" to byteArrayOf(2))) { file ->
            expect(UpdateProblem.ABI) { UpdateArchiveContents.read(file) }
        }
    }

    private suspend fun archive(entries: Map<String, ByteArray>, test: suspend (File) -> Unit) {
        val file = File(context.cacheDir, "update-archive-${UUID.randomUUID()}.apk")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            }
            test(file)
        } finally { file.delete() }
    }

    private fun runtime() = JSONObject().put("version", "17.26.2").put("file", "open-voyah-controls-frida-17.26.2")
        .put("binary_sha256", "b".repeat(64)).toString().toByteArray()

    private suspend fun expect(problem: UpdateProblem, action: suspend () -> Unit) {
        try { action(); fail("Expected $problem") }
        catch (error: UpdateException) { assertEquals(problem, error.problem) }
    }

    private companion object {
        const val MANIFEST = "AndroidManifest.xml"
        const val RUNTIME = "assets/production/FRIDA_RUNTIME.json"
    }
}
