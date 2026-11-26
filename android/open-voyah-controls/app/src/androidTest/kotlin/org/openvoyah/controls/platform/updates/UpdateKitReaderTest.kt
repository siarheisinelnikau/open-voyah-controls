package org.openvoyah.controls.platform.updates

import android.test.AndroidTestCase
import kotlinx.coroutines.runBlocking
import org.openvoyah.controls.core.updates.ReleasePolicy
import org.openvoyah.controls.data.updates.UpdateException
import org.openvoyah.controls.data.updates.UpdateProblem
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdateKitReaderTest : AndroidTestCase() {
    fun testVerifiesAndExtractsOnlyTheSignedApk() = runBlocking {
        fixture { reader, release, archived, apk ->
            val result = reader.extract(release, archived)
            try {
                assertEquals(55L, result.versionCode)
                assertEquals(sha(apk), result.release.sha256)
                assertTrue(result.file.readBytes().contentEquals(apk))
            } finally { result.file.delete() }
        }
    }

    fun testRejectsIncorrectCompatibilitySignaturePayloadAndDuplicateFields() = runBlocking {
        for ((change, problem) in listOf(
            "firmware" to UpdateProblem.KIT_COMPATIBILITY,
            "signature" to UpdateProblem.KIT_SIGNATURE,
            "payload" to UpdateProblem.SIZE_OR_DIGEST,
            "duplicate" to UpdateProblem.KIT_FORMAT,
            "runtime-pin" to UpdateProblem.KIT_FORMAT,
        )) {
            fixture(change) { reader, release, archived, _ ->
                try {
                    reader.extract(release, archived)
                    fail("Expected $problem for $change")
                } catch (failure: UpdateException) { assertEquals(problem, failure.problem) }
            }
        }
    }

    private suspend fun fixture(change: String = "", body: suspend (UpdateKitReader, ReleasePolicy.Candidate, File, ByteArray) -> Unit) {
        val keygen = KeyPairGenerator.getInstance("EC")
        keygen.initialize(ECGenParameterSpec("secp256r1"))
        val key = keygen.generateKeyPair()
        val pin = hex(key.public.encoded.takeLast(65).toByteArray())
        val runtime = "fixture runtime".toByteArray()
        val runtimePin = if (change == "runtime-pin") "c".repeat(64) else sha(runtime)
        val metadata = """{"version":"17.26.2","file":"open-voyah-controls-frida-17.26.2","binary_sha256":"$runtimePin","size":${runtime.size}}"""
        val apk = zip(mapOf("AndroidManifest.xml" to byteArrayOf(1),
            "assets/production/FRIDA_RUNTIME.json" to metadata.toByteArray()))
        val code = if (change == "duplicate") """"versionCode":54,"versionCode":55""" else """"versionCode":55"""
        val fingerprint = if (change == "firmware") "vendor/other" else UNIT.fingerprint
        val manifest = """
            {"schema":1,"version":"0.31.0",$code,"compatibility":[
            {"fingerprint":"$fingerprint","sdk":30,"abi":"arm64-v8a","product":"fixture"}],"files":[
            {"role":"controls-apk","name":"$APK_NAME","size":${apk.size},"sha256":"${sha(apk)}"},
            {"role":"frida-runtime","name":"frida-inject","size":${runtime.size},"sha256":"${sha(runtime)}"}]}
        """.trimIndent().toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private)
            update(manifest)
            sign()
        }
        if (change == "signature") signature[0] = 0
        val stagedRuntime = runtime.clone()
        if (change == "payload") stagedRuntime[0] = 0
        val bytes = zip(mapOf("manifest.json" to manifest, "manifest.sig" to signature,
            APK_NAME to apk, "frida-inject" to stagedRuntime))
        val release = checkNotNull(ReleasePolicy.kitCandidate("fixture-v0.31.0", false, false, KIT_NAME,
            "${ReleasePolicy.DOWNLOAD_PREFIX}fixture-v0.31.0/$KIT_NAME", bytes.size.toLong(), "sha256:${sha(bytes)}"))
        val directory = checkNotNull(context.cacheDir).let { File(it, "kit-fixture-${java.util.UUID.randomUUID()}") }
        assertTrue(directory.mkdirs())
        val file = File(directory, KIT_NAME)
        try {
            file.writeBytes(bytes)
            body(UpdateKitReader(pin) { UNIT }, release, file, apk)
            assertEquals(listOf(KIT_NAME), directory.list()?.toList())
        } finally { directory.deleteRecursively() }
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    crc = CRC32().also { it.update(bytes) }.value
                })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private fun sha(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        const val KIT_NAME = "OpenVoyahControlsKit-v0.31.0.zip"
        const val APK_NAME = "OpenVoyahControls-v0.31.0.apk"
        val UNIT = KitHeadUnit("vendor/fixture", 30, "arm64-v8a", "fixture")
    }
}
