package org.openvoyah.controls.data.updates

import android.content.Context
import android.content.ContextWrapper
import android.test.AndroidTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.core.updates.ReleasePolicy
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** All HTTP responses are in-memory fixtures. These tests make no network requests. */
class OfficialUpdateRepositoryTest : AndroidTestCase() {
    fun testStartupOptOutMakesNoRequestAndManualCheckRemainsAvailable() = runBlocking {
        withDirectory { directory ->
            val http = MemoryHttp("[]".toByteArray())
            val repository = OfficialUpdateRepository("0.18.0", directory, http)
            repository.startup(false)
            assertEquals(0, http.requests.get())
            assertEquals(UpdateCheckPhase.COMPLETE, repository.check().phase)
            assertEquals(1, http.requests.get())
            repository.startup(true)
            assertEquals(1, http.requests.get())
        }
    }

    fun testStartupIsNonblockingRunsOnceAndKeepsFailureDistinctFromUpToDate() = runBlocking {
        withDirectory { directory ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val count = AtomicInteger()
            val http = object : ReleaseHttp {
                override fun open(url: String): ReleaseHttp.Response {
                    count.incrementAndGet()
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    return MemoryHttp.response(ByteArray(0), status = 429)
                }
            }
            val repository = OfficialUpdateRepository("0.18.0", directory, http)
            repository.startup(true)
            // startup has already returned while the worker is waiting for its response.
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            repository.startup(true)
            release.countDown()
            val result = withTimeout(3_000) { repository.checks.first { it.phase == UpdateCheckPhase.FAILED } }
            assertEquals(1, count.get())
            assertEquals(UpdateProblem.RATE_LIMIT, result.problem)
            assertTrue(result.automatic)
            assertNull(result.candidate)
        }
    }

    fun testCatalogSelectsHighestPublishedStableCanonicalDigestAsset() = runBlocking {
        withDirectory { directory ->
            val releases = JSONArray().put(release("0.19.0")).put(release("0.99.0", draft = true))
                .put(release("0.21.0", prerelease = true)).put(release("0.20.0")).put(release("0.17.0"))
            val repository = OfficialUpdateRepository("0.18.0", directory, MemoryHttp(releases.toString().toByteArray()))
            val result = repository.check()
            assertEquals(UpdateCheckPhase.COMPLETE, result.phase)
            assertEquals("0.20.0", result.candidate?.version.toString())
        }
    }

    fun testMalformedAndOversizedCatalogsAreFailuresRatherThanNoUpdate() = runBlocking {
        withDirectory { directory ->
            val invalid = OfficialUpdateRepository("0.18.0", directory, MemoryHttp("not JSON".toByteArray())).check()
            assertEquals(UpdateCheckPhase.FAILED, invalid.phase)
            assertEquals(UpdateProblem.CATALOG, invalid.problem)
            val oversized = OfficialUpdateRepository("0.18.0", directory,
                MemoryHttp("[]".toByteArray(), declaredLength = 4L * 1024 * 1024 + 1)).check()
            assertEquals(UpdateCheckPhase.FAILED, oversized.phase)
            assertEquals(UpdateProblem.CATALOG, oversized.problem)
            val badSize = release("0.19.0")
            badSize.getJSONArray("assets").getJSONObject(0).put("size", 1.5)
            val noninteger = OfficialUpdateRepository("0.18.0", directory,
                MemoryHttp(JSONArray().put(badSize).toString().toByteArray())).check()
            assertEquals(UpdateProblem.CATALOG, noninteger.problem)
        }
    }

    fun testVerifiedDownloadPromotesOnlyCompleteBytesAndReportsProgress() = runBlocking {
        withDirectory { directory ->
            val bytes = ByteArray(140_000) { (it % 251).toByte() }
            val repository = OfficialUpdateRepository("0.18.0", directory, MemoryHttp(bytes))
            val progress = mutableListOf<DownloadProgress>()
            val file = repository.download(candidate(bytes), progress::add)
            assertTrue(file.name.endsWith(".apk"))
            assertTrue(bytes.contentEquals(file.readBytes()))
            assertEquals(DownloadProgress(bytes.size.toLong(), bytes.size.toLong()), progress.last())
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".part") })
        }
    }

    fun testTruncatedOversizedAndWrongDigestDownloadsRemovePartialFiles() = runBlocking {
        withDirectory { directory ->
            val expected = "verified bytes".toByteArray()
            val candidate = candidate(expected)
            for (bytes in listOf(expected.copyOf(expected.size - 1), expected + byteArrayOf(1), "untrusted bytes".toByteArray())) {
                val repository = OfficialUpdateRepository("0.18.0", directory, MemoryHttp(bytes, declaredLength = -1))
                expect(UpdateProblem.SIZE_OR_DIGEST) { repository.download(candidate) {} }
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            }
        }
    }

    fun testDownloadCancellationClosesBlockedResponseAndRemovesPartialFile() = runBlocking {
        withDirectory { directory ->
            val reading = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val response = object : ReleaseHttp.Response {
                override val status = 200
                override val length = -1L
                override fun input(): InputStream = object : InputStream() {
                    override fun read(): Int = error("Use buffered reads")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        reading.countDown()
                        check(closed.await(3, TimeUnit.SECONDS))
                        throw IOException("Fixture closed")
                    }
                }
                override fun close() { closed.countDown() }
            }
            val http = object : ReleaseHttp { override fun open(url: String) = response }
            val repository = OfficialUpdateRepository("0.18.0", directory, http)
            val job = launch(Dispatchers.IO) { repository.download(candidate(byteArrayOf(1))) {} }
            assertTrue(reading.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) { job.cancelAndJoin() }
            assertEquals(0L, closed.count)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    fun testRedirectsKeepHttpsAndExactAssetHosts() {
        assertTrue(GitHubReleaseHttp.trustedRedirect(URI("https://release-assets.githubusercontent.com/path?token=fixture")))
        for (url in listOf("http://release-assets.githubusercontent.com/path", "https://github.com.evil.example/path",
            "https://user@github.com/path", "https://github.com:444/path", "https://github.com/path#fragment", "file:///tmp/a")) {
            assertFalse(url, GitHubReleaseHttp.trustedRedirect(URI(url)))
        }
    }

    fun testStartupPreferenceDefaultsEnabledAndPersistsOptOut() {
        val prefix = "update_test_${UUID.randomUUID()}_"
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        try {
            assertTrue(UpdatePreferences(isolated).checkOnStartup())
            UpdatePreferences(isolated).checkOnStartup(false)
            assertFalse(UpdatePreferences(isolated).checkOnStartup())
        } finally { context.deleteSharedPreferences(prefix + "updates") }
    }

    private suspend fun withDirectory(test: suspend (File) -> Unit) {
        val directory = File(context.cacheDir, "update-test-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        try { test(directory) } finally { directory.deleteRecursively() }
    }

    private suspend fun expect(problem: UpdateProblem, action: suspend () -> Unit) {
        try { action(); fail("Expected $problem") }
        catch (error: UpdateException) { assertEquals(problem, error.problem) }
    }

    private fun release(version: String, draft: Boolean = false, prerelease: Boolean = false): JSONObject {
        val name = "OpenVoyahControls-v$version.apk"
        return JSONObject().put("tag_name", "android-v$version").put("draft", draft).put("prerelease", prerelease)
            .put("assets", JSONArray().put(JSONObject().put("name", name)
                .put("browser_download_url", "${ReleasePolicy.DOWNLOAD_PREFIX}android-v$version/$name")
                .put("size", 1).put("digest", "sha256:" + "a".repeat(64))))
    }

    private fun candidate(bytes: ByteArray): ReleasePolicy.Candidate = checkNotNull(ReleasePolicy.candidate("android-v0.19.0",
        false, false, "OpenVoyahControls-v0.19.0.apk",
        "${ReleasePolicy.DOWNLOAD_PREFIX}android-v0.19.0/OpenVoyahControls-v0.19.0.apk", bytes.size.toLong(),
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }))

    private class MemoryHttp(private val bytes: ByteArray, private val declaredLength: Long = bytes.size.toLong()) : ReleaseHttp {
        val requests = AtomicInteger()
        override fun open(url: String): ReleaseHttp.Response { requests.incrementAndGet(); return response(bytes, declaredLength = declaredLength) }
        companion object {
            fun response(bytes: ByteArray, declaredLength: Long = bytes.size.toLong(), status: Int = 200) = object : ReleaseHttp.Response {
                override val status = status
                override val length = declaredLength
                override fun input() = ByteArrayInputStream(bytes)
                override fun close() { }
            }
        }
    }
}
