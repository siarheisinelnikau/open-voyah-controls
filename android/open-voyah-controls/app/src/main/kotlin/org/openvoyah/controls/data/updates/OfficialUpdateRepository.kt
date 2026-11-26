package org.openvoyah.controls.data.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.core.updates.KitSignature
import org.openvoyah.controls.core.updates.ReleasePolicy
import org.openvoyah.controls.core.updates.ReleaseVersion
import org.openvoyah.controls.diagnostics.ControlsLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

internal enum class UpdateProblem {
    NETWORK, RATE_LIMIT, CATALOG, DOWNLOAD, UNTRUSTED_DOWNLOAD, SIZE_OR_DIGEST, STORAGE,
    RELEASE_SIGNER, PACKAGE, DEBUGGABLE, SPLIT, VERSION, VERSION_CODE, MIN_SDK, ABI, RUNTIME,
    DEVICE_PREPARATION, INSTALL_PERMISSION, INSTALLATION, PREFERENCE,
    KIT_SIGNATURE, KIT_COMPATIBILITY, KIT_FORMAT, KIT_WINDOWS,
}

internal class UpdateException(val problem: UpdateProblem) : IOException(problem.name)

internal enum class UpdateCheckPhase { IDLE, CHECKING, COMPLETE, FAILED }

internal data class UpdateCheck(
    val phase: UpdateCheckPhase = UpdateCheckPhase.IDLE,
    val candidate: ReleasePolicy.Candidate? = null,
    val problem: UpdateProblem? = null,
    val automatic: Boolean = false,
    val kit: ReleasePolicy.Candidate? = null,
) {
    val latest: ReleasePolicy.Candidate? get() = listOfNotNull(candidate, kit).maxByOrNull { it.version }
}

internal data class DownloadProgress(val bytes: Long, val total: Long)

/** Finite checks and user-requested downloads; the repository never prepares or changes OEM state. */
internal class OfficialUpdateRepository(
    private val installedVersion: String,
    private val directory: File,
    private val http: ReleaseHttp = GitHubReleaseHttp(installedVersion),
    private val checkKits: Boolean = KitSignature.hasPin(BuildConfig.KIT_PUBLIC_KEY_HEX),
) {
    private val mutable = MutableStateFlow(UpdateCheck())
    val checks = mutable.asStateFlow()
    private val checkLock = Mutex()
    private val downloadLock = Mutex()
    private val startupHandled = AtomicBoolean(false)
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cleanup = startupScope.launch { runCatching { discardInterruptedDownloads() } }

    fun startup(enabled: Boolean) {
        if (!startupHandled.compareAndSet(false, true) || !enabled) return
        startupScope.launch { check(automatic = true) }
    }

    suspend fun check(automatic: Boolean = false): UpdateCheck = checkLock.withLock {
        val before = mutable.value
        mutable.value = before.copy(phase = UpdateCheckPhase.CHECKING, problem = null, automatic = automatic)
        var httpStatus: Int? = null
        try {
            val selected = withContext(Dispatchers.IO) {
                val installed = ReleaseVersion.parse(installedVersion) ?: throw UpdateException(UpdateProblem.VERSION)
                val deadline = deadline(15)
                http.open(ReleasePolicy.CATALOG_URL).use { response ->
                    httpStatus = response.status
                    requireSuccess(response)
                    if (response.length > MAX_CATALOG_BYTES) throw UpdateException(UpdateProblem.CATALOG)
                    withResponseCancellation(response) {
                        val output = ByteArrayOutputStream()
                        response.input().use { input ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                checkActive(deadline)
                                val count = input.read(buffer)
                                if (count == -1) break
                                if (output.size().toLong() + count > MAX_CATALOG_BYTES) throw UpdateException(UpdateProblem.CATALOG)
                                output.write(buffer, 0, count)
                            }
                        }
                        select(JSONArray(output.toString(Charsets.UTF_8.name())), installed)
                    }
                }
            }
            UpdateCheck(UpdateCheckPhase.COMPLETE, selected.first, automatic = automatic,
                kit = selected.second).also { mutable.value = it }
        } catch (cancelled: CancellationException) {
            mutable.value = before
            throw cancelled
        } catch (error: Exception) {
            val problem = when (error) {
                is UpdateException -> error.problem
                is JSONException -> UpdateProblem.CATALOG
                else -> UpdateProblem.NETWORK
            }
            // An unsynchronized clock (TLS handshake), DNS and an HTTP refusal all reach the screen as one problem.
            ControlsLog.warn("update_check", error, "problem", problem, "http_status", httpStatus, "automatic", automatic)
            before.copy(phase = UpdateCheckPhase.FAILED, problem = problem, automatic = automatic).also { mutable.value = it }
        }
    }

    suspend fun download(candidate: ReleasePolicy.Candidate, progress: (DownloadProgress) -> Unit): File {
        cleanup.join()
        return downloadLock.withLock {
            var handedOff: File? = null
            try { withContext(Dispatchers.IO) {
                ensureDirectory()
                val partial = try { File.createTempFile("release-", ".part", directory) }
                catch (failure: IOException) {
                    ControlsLog.warn("update_download", failure, "problem", UpdateProblem.STORAGE, "step", "create_partial")
                    throw UpdateException(UpdateProblem.STORAGE)
                }
                var complete: File? = null
                var httpStatus: Int? = null
                try {
                    val deadline = deadline(300)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var written = 0L
                    var reportedAt = 0L
                    http.open(candidate.url).use { response ->
                        httpStatus = response.status
                        requireSuccess(response)
                        if (response.length >= 0 && response.length != candidate.bytes) throw UpdateException(UpdateProblem.SIZE_OR_DIGEST)
                        withResponseCancellation(response) {
                            response.input().use { input ->
                                FileOutputStream(partial).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    progress(DownloadProgress(0, candidate.bytes))
                                    while (true) {
                                        checkActive(deadline)
                                        val count = input.read(buffer)
                                        if (count == -1) break
                                        written += count
                                        if (written > candidate.bytes) throw UpdateException(UpdateProblem.SIZE_OR_DIGEST)
                                        digest.update(buffer, 0, count)
                                        try { output.write(buffer, 0, count) }
                                        catch (_: IOException) { throw UpdateException(UpdateProblem.STORAGE) }
                                        val now = System.nanoTime()
                                        if (now - reportedAt >= 300_000_000L) {
                                            progress(DownloadProgress(written, candidate.bytes))
                                            reportedAt = now
                                        }
                                    }
                                    if (written != candidate.bytes || !hex(digest.digest()).equals(candidate.sha256, ignoreCase = true)) {
                                        throw UpdateException(UpdateProblem.SIZE_OR_DIGEST)
                                    }
                                    try { output.fd.sync() }
                                    catch (_: IOException) { throw UpdateException(UpdateProblem.STORAGE) }
                                }
                            }
                        }
                    }
                    checkActive(deadline)
                    val target = File(directory, partial.name.removeSuffix(".part") + if (candidate.kit) ".zip" else ".apk")
                    if (target.exists() || !partial.renameTo(target)) throw UpdateException(UpdateProblem.STORAGE)
                    complete = target
                    handedOff = target
                    progress(DownloadProgress(written, candidate.bytes))
                    target
                } catch (cancelled: CancellationException) {
                    complete?.delete()
                    throw cancelled
                } catch (error: Exception) {
                    complete?.delete()
                    ControlsLog.warn("update_download", error, "problem", (error as? UpdateException)?.problem ?: UpdateProblem.DOWNLOAD,
                        "http_status", httpStatus)
                    if (error is UpdateException) throw error
                    throw UpdateException(UpdateProblem.DOWNLOAD)
                } finally {
                    partial.delete()
                }
            } } catch (cancelled: CancellationException) {
                // withContext can cancel delivery after the IO block has already promoted the file.
                withContext(NonCancellable + Dispatchers.IO) { handedOff?.delete() }
                throw cancelled
            }
        }
    }

    /** Download requests await this cleanup before creating any live partial files. */
    private fun discardInterruptedDownloads() {
        if (!directory.isDirectory) return
        val canonical = directory.canonicalFile
        directory.listFiles()?.forEach { file ->
            if (file.name.startsWith("release-") && (file.name.endsWith(".part") || file.name.endsWith(".zip")) && file.isFile &&
                file.canonicalFile.parentFile == canonical) file.delete()
        }
    }

    private fun ensureDirectory() {
        val canonical = directory.canonicalFile
        if ((!directory.isDirectory && !directory.mkdirs()) || canonical.name != directory.name ||
            canonical.parentFile != directory.parentFile?.canonicalFile) {
            throw UpdateException(UpdateProblem.STORAGE)
        }
    }

    private fun select(releases: JSONArray, installed: ReleaseVersion):
        Pair<ReleasePolicy.Candidate?, ReleasePolicy.Candidate?> {
        if (releases.length() > 30) throw UpdateException(UpdateProblem.CATALOG)
        var selected: ReleasePolicy.Candidate? = null
        var kit: ReleasePolicy.Candidate? = null
        for (r in 0 until releases.length()) {
            val release = releases.getJSONObject(r)
            val tag = release.getString("tag_name")
            val draft = release.get("draft") as? Boolean ?: throw UpdateException(UpdateProblem.CATALOG)
            val prerelease = release.get("prerelease") as? Boolean ?: throw UpdateException(UpdateProblem.CATALOG)
            if (draft || prerelease) continue
            val assets = release.getJSONArray("assets")
            for (a in 0 until assets.length()) {
                val asset = assets.getJSONObject(a)
                val bytes = when (val value = asset.get("size")) {
                    is Int -> value.toLong()
                    is Long -> value
                    else -> throw UpdateException(UpdateProblem.CATALOG)
                }
                val name = asset.getString("name")
                val url = asset.getString("browser_download_url")
                val digest = if (asset.isNull("digest")) null else asset.getString("digest")
                val candidate = ReleasePolicy.candidate(tag, draft, prerelease, name, url, bytes, digest)
                selected = ReleasePolicy.newer(selected, candidate, installed)
                if (checkKits) {
                    val kitCandidate = ReleasePolicy.kitCandidate(tag, draft, prerelease, name, url, bytes, digest)
                    kit = ReleasePolicy.newer(kit, kitCandidate, installed)
                }
            }
        }
        return selected to kit
    }

    private fun requireSuccess(response: ReleaseHttp.Response) {
        if (response.status == 403 || response.status == 429) throw UpdateException(UpdateProblem.RATE_LIMIT)
        if (response.status != 200) throw UpdateException(UpdateProblem.NETWORK)
    }

    private suspend fun checkActive(deadline: Long) {
        coroutineContext.ensureActive()
        if (System.nanoTime() > deadline) throw UpdateException(UpdateProblem.NETWORK)
    }

    private suspend fun <T> withResponseCancellation(response: ReleaseHttp.Response, read: suspend () -> T): T = coroutineScope {
        // The sibling closes a blocked read as soon as its owner is cancelled.
        val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { response.close() }
        }
        try { read() } finally { cancellation.cancelAndJoin() }
    }

    private fun deadline(seconds: Long): Long = System.nanoTime() + seconds * 1_000_000_000L

    private companion object {
        const val MAX_CATALOG_BYTES = 4L * 1024 * 1024
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
