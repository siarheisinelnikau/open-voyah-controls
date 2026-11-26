package org.openvoyah.controls.platform.updates

import android.os.Build
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.core.updates.KitSignature
import org.openvoyah.controls.core.updates.ReleasePolicy
import org.openvoyah.controls.data.updates.UpdateException
import org.openvoyah.controls.data.updates.UpdateProblem
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

internal data class KitHeadUnit(val fingerprint: String, val sdk: Int, val abi: String, val product: String) {
    companion object {
        fun current() = KitHeadUnit(Build.FINGERPRINT, Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), Build.PRODUCT)
    }
}

internal data class ExtractedUpdateKit(val release: ReleasePolicy.Candidate, val file: File, val versionCode: Long)

/** Verifies a signed release archive in private cache before opening any Android install session. */
internal class UpdateKitReader(
    private val publicKey: String = BuildConfig.KIT_PUBLIC_KEY_HEX,
    private val headUnit: () -> KitHeadUnit = KitHeadUnit::current,
) {
    suspend fun extract(kit: ReleasePolicy.Candidate, archive: File): ExtractedUpdateKit {
        var output: File? = null
        try {
            return withContext(Dispatchers.IO) {
                if (!kit.kit || archive.length() != kit.bytes) invalid()
                val apkName = "OpenVoyahControls-v${kit.version}.apk"
                val manifest = ZipFile(archive).use { zip ->
                    val entries = linkedMapOf<String, ZipEntry>()
                    val expected = setOf("manifest.json", "manifest.sig", apkName, RUNTIME_NAME)
                    val listing = zip.entries()
                    while (listing.hasMoreElements()) {
                        val entry = listing.nextElement()
                        if (entry.name !in expected || entries.put(entry.name, entry) != null ||
                            entry.method != ZipEntry.STORED || entry.isDirectory || entry.size <= 0 ||
                            entry.size != entry.compressedSize) invalid()
                    }
                    if (entries.keys != expected) invalid()
                    val raw = zip.smallEntry(entries.getValue("manifest.json"), 65_536)
                    val signature = zip.smallEntry(entries.getValue("manifest.sig"), 72)
                    if (!KitSignature.verifies(publicKey, raw, signature)) throw UpdateException(UpdateProblem.KIT_SIGNATURE)
                    val manifest = parseManifest(raw, kit.version.toString(), apkName)
                    if (headUnit() !in manifest.compatibility) throw UpdateException(UpdateProblem.KIT_COMPATIBILITY)
                    val apk = manifest.payloads.getValue("controls-apk")
                    val runtime = manifest.payloads.getValue("frida-runtime")
                    val apkEntry = entries.getValue(apk.name)
                    val runtimeEntry = entries.getValue(runtime.name)
                    if (apk.bytes != apkEntry.size || runtime.bytes != runtimeEntry.size) invalid()
                    zip.getInputStream(runtimeEntry).use { UpdateApkBytes.transfer(it, null, runtime.bytes, runtime.sha256) }
                    val file = File.createTempFile("kit-", ".apk", archive.parentFile)
                    output = file
                    zip.getInputStream(apkEntry).use { input ->
                        FileOutputStream(file).use { staged ->
                            UpdateApkBytes.transfer(input, staged, apk.bytes, apk.sha256)
                            staged.fd.sync()
                        }
                    }
                    manifest
                }
                val file = checkNotNull(output)
                val runtime = manifest.payloads.getValue("frida-runtime")
                val contents = UpdateArchiveContents.read(file, requireRuntimeSize = true)
                if (contents.runtime.sha256 != runtime.sha256 || contents.runtimeBytes != runtime.bytes) invalid()
                val apk = manifest.payloads.getValue("controls-apk")
                val release = ReleasePolicy.embeddedApk(kit, apk.name, apk.bytes, apk.sha256) ?: invalid()
                coroutineContext.ensureActive()
                ExtractedUpdateKit(release, file, manifest.versionCode)
            }
        } catch (failure: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { output?.delete() }
            if (failure is CancellationException || failure is UpdateException) throw failure
            throw UpdateException(UpdateProblem.KIT_FORMAT)
        }
    }

    private data class Payload(val name: String, val bytes: Long, val sha256: String)
    private data class Manifest(val versionCode: Long, val compatibility: Set<KitHeadUnit>, val payloads: Map<String, Payload>)

    private fun parseManifest(bytes: ByteArray, version: String, apkName: String): Manifest {
        val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return JsonReader(json.reader()).use { reader ->
            var versionCode = 0L
            var compatibility = emptySet<KitHeadUnit>()
            var payloads = emptyMap<String, Payload>()
            reader.fields("schema", "version", "versionCode", "compatibility", "files") { field ->
                when (field) {
                    "schema" -> if (reader.number(1) != 1L) invalid()
                    "version" -> if (reader.string() != version) invalid()
                    "versionCode" -> versionCode = reader.number(Int.MAX_VALUE.toLong())
                    "compatibility" -> compatibility = readCompatibility(reader)
                    "files" -> payloads = readPayloads(reader, apkName)
                }
            }
            if (reader.peek() != JsonToken.END_DOCUMENT) invalid()
            Manifest(versionCode, compatibility, payloads)
        }
    }

    private fun readCompatibility(reader: JsonReader): Set<KitHeadUnit> {
        val rows = mutableSetOf<KitHeadUnit>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (rows.size >= 64) invalid()
            var fingerprint = ""
            var product = ""
            var abi = ""
            var sdk = 0
            reader.fields("fingerprint", "sdk", "abi", "product") { field ->
                when (field) {
                    "fingerprint" -> fingerprint = reader.string()
                    "product" -> product = reader.string()
                    "abi" -> abi = reader.string()
                    "sdk" -> sdk = reader.number(30).toInt()
                }
            }
            if (!FINGERPRINT.matches(fingerprint) || !PRODUCT.matches(product) ||
                (sdk != 28 && sdk != 30) || abi != "arm64-v8a" || !rows.add(KitHeadUnit(fingerprint, sdk, abi, product))) invalid()
        }
        reader.endArray()
        if (rows.isEmpty()) invalid()
        return rows
    }

    private fun readPayloads(reader: JsonReader, apkName: String): Map<String, Payload> {
        val payloads = mutableMapOf<String, Payload>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (payloads.size >= 2) invalid()
            var role = ""
            var name = ""
            var size = 0L
            var sha256 = ""
            reader.fields("role", "name", "size", "sha256") { field ->
                when (field) {
                    "role" -> role = reader.string()
                    "name" -> name = reader.string()
                    "size" -> size = reader.number(ReleasePolicy.MAX_APK_BYTES)
                    "sha256" -> sha256 = reader.string()
                }
            }
            val expectedName = when (role) {
                "controls-apk" -> apkName
                "frida-runtime" -> RUNTIME_NAME.also { if (size > MAX_RUNTIME_BYTES) invalid() }
                else -> invalid()
            }
            if (name != expectedName || !SHA256.matches(sha256) || payloads.put(role, Payload(name, size, sha256)) != null) invalid()
        }
        reader.endArray()
        if (payloads.size != 2) invalid()
        return payloads
    }

    private inline fun JsonReader.fields(vararg fields: String, read: (String) -> Unit) {
        val remaining = fields.toMutableSet()
        beginObject()
        while (hasNext()) {
            val field = nextName()
            if (!remaining.remove(field)) invalid()
            read(field)
        }
        endObject()
        if (remaining.isNotEmpty()) invalid()
    }

    private fun JsonReader.string(): String {
        if (peek() != JsonToken.STRING) invalid()
        return nextString()
    }

    private fun JsonReader.number(max: Long): Long {
        if (peek() != JsonToken.NUMBER) invalid()
        val raw = nextString()
        if (!INTEGER.matches(raw)) invalid()
        return raw.toLongOrNull()?.takeIf { it <= max } ?: invalid()
    }

    private fun ZipFile.smallEntry(entry: ZipEntry, maximum: Int): ByteArray {
        if (entry.size > maximum) invalid()
        val bytes = ByteArray(entry.size.toInt())
        getInputStream(entry).use { input ->
            java.io.DataInputStream(input).readFully(bytes)
            if (input.read() != -1) invalid()
        }
        return bytes
    }

    private fun invalid(): Nothing = throw UpdateException(UpdateProblem.KIT_FORMAT)

    private companion object {
        const val RUNTIME_NAME = "frida-inject"
        const val MAX_RUNTIME_BYTES = 128L * 1024 * 1024
        val FINGERPRINT = Regex("[A-Za-z0-9][A-Za-z0-9._:/+,-]{0,511}")
        val PRODUCT = Regex("[A-Za-z0-9][A-Za-z0-9._:/+-]{0,127}")
        val SHA256 = Regex("[0-9a-f]{64}")
        val INTEGER = Regex("[1-9][0-9]{0,9}")
    }
}
