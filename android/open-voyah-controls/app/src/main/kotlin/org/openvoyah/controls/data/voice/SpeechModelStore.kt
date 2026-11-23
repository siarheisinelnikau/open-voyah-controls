package org.openvoyah.controls.data.voice

import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloaded speech models under [root], one folder per model. A model is installed only after every file matched its
 * pin, so an interrupted, cancelled or tampered download leaves the previous state untouched.
 */
internal class SpeechModelStore(
    private val root: File,
    private val fetch: (String) -> InputStream = ::httpsFetch,
    private val usableSpace: (File) -> Long = File::getUsableSpace,
) {
    class StorageFull : IOException("Not enough free storage for the speech model")
    class Cancelled : IOException("Speech model download cancelled")
    class Mismatch(name: String) : IOException("Downloaded file does not match its pinned hash: $name")

    fun directory(model: SpeechModel) = File(root, model.id)

    fun installed(model: SpeechModel) = File(directory(model), READY).isFile

    fun delete(model: SpeechModel) {
        directory(model).deleteRecursively()
        staging(model).deleteRecursively()
    }

    /**
     * Downloads and verifies [model]; blocking. [progress] receives the bytes downloaded so far and the total. Stops with
     * [Cancelled] as soon as [current] returns false.
     */
    fun install(model: SpeechModel, progress: (Long, Long) -> Unit, current: () -> Boolean) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create ${root.name}")
        val staging = staging(model)
        staging.deleteRecursively()
        if (usableSpace(root) < model.installedSize + SPACE_MARGIN) throw StorageFull()
        if (!staging.mkdirs()) throw IOException("Cannot create ${staging.name}")
        try {
            var done = 0L
            val total = model.downloadSize
            for (source in model.sources) {
                if (!current()) throw Cancelled()
                CountingInput(fetch(source.url), source.downloadSize) { count ->
                    done += count
                    progress(done, total)
                    if (!current()) throw Cancelled()
                }.use { input ->
                    when (source.kind) {
                        ModelSource.Kind.FILE -> writeFile(input, staging, checkNotNull(source.file))
                        ModelSource.Kind.ZIP -> unzip(input, staging, checkNotNull(source.archiveSha256),
                            model.installedSize * 2)
                    }
                }
            }
            File(staging, READY).writeText(model.id)
            val target = directory(model)
            target.deleteRecursively()
            if (!staging.renameTo(target)) throw IOException("Cannot install ${model.id}")
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            throw failure
        }
    }

    private fun staging(model: SpeechModel) = File(root, ".${model.id}.part")

    private fun writeFile(input: InputStream, folder: File, pin: PinnedFile) {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        FileOutputStream(File(folder, pin.name)).use { output ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                written += count
                if (written > pin.size) throw Mismatch(pin.name)
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
        if (written != pin.size || hex(digest.digest()) != pin.sha256) throw Mismatch(pin.name)
    }

    /** Unpacks without the archive's top folder; the hash covers the whole archive, read to its end. */
    private fun unzip(input: InputStream, folder: File, sha256: String, limit: Long) {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashed = DigestInputStream(input, digest)
        val base = folder.canonicalFile
        var unpacked = 0L
        val zip = ZipInputStream(hashed)
        val buffer = ByteArray(BUFFER)
        while (true) {
            val entry = zip.nextEntry ?: break
            val relative = entry.name.replace('\\', '/').substringAfter('/', "")
            if (relative.isEmpty()) continue
            val target = File(base, relative).canonicalFile
            if (!target.path.startsWith(base.path + File.separator)) throw IOException("Unsafe archive entry")
            if (entry.isDirectory) {
                if (!target.isDirectory && !target.mkdirs()) throw IOException("Cannot unpack ${entry.name}")
                continue
            }
            target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot unpack ${entry.name}") }
            FileOutputStream(target).use { output ->
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    unpacked += count
                    if (unpacked > limit) throw IOException("Archive is larger than the model")
                    output.write(buffer, 0, count)
                }
            }
        }
        // The central directory follows the entries; the archive hash needs every byte.
        while (hashed.read(buffer) >= 0) Unit
        if (hex(digest.digest()) != sha256) throw Mismatch("archive")
    }

    /** Reports every read and refuses more bytes than the source declares. */
    private class CountingInput(input: InputStream, private val limit: Long, private val read: (Int) -> Unit) :
        FilterInputStream(input) {
        private var total = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) count(count)
            return count
        }

        private fun count(count: Int) {
            total += count
            if (total > limit) throw IOException("Download is larger than expected")
            read(count)
        }
    }

    companion object {
        const val READY = "ready"
        private const val BUFFER = 64 * 1024
        private const val SPACE_MARGIN = 64L * 1024 * 1024

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        /** HTTPS only, also across redirects, which the model hosts use to reach their storage. */
        fun httpsFetch(url: String): InputStream {
            var location = URL(url)
            repeat(MAX_REDIRECTS) {
                if (location.protocol != "https") throw IOException("Model downloads use HTTPS only")
                val connection = location.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "OpenVoyahControls")
                val code = connection.responseCode
                if (code in 300..399) {
                    val next = connection.getHeaderField("Location")
                    connection.disconnect()
                    location = URL(location, next ?: throw IOException("Redirect without a location"))
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    connection.disconnect()
                    throw IOException("Model server answered HTTP $code")
                }
                return connection.inputStream
            }
            throw IOException("Too many redirects")
        }

        private const val MAX_REDIRECTS = 6
    }
}
