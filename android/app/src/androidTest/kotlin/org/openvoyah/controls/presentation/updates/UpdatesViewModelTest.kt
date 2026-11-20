package org.openvoyah.controls.presentation.updates

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.test.AndroidTestCase
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.core.updates.ApkUpdatePolicy
import org.openvoyah.controls.core.updates.ReleasePolicy
import org.openvoyah.controls.data.updates.OfficialUpdateRepository
import org.openvoyah.controls.data.updates.ReleaseHttp
import org.openvoyah.controls.data.updates.UpdatePreferences
import org.openvoyah.controls.data.updates.UpdateProblem
import org.openvoyah.controls.platform.updates.PreparedUpdateInstall
import org.openvoyah.controls.platform.updates.UpdateInstallApproval
import org.openvoyah.controls.platform.updates.UpdateInstallPhase
import org.openvoyah.controls.platform.updates.UpdateInstallPort
import org.openvoyah.controls.platform.updates.UpdateInstallState
import org.openvoyah.controls.platform.updates.UpdatePreparation
import org.openvoyah.controls.platform.updates.UpdateVerifier
import org.openvoyah.controls.platform.updates.VerifiedUpdate
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

class UpdatesViewModelTest : AndroidTestCase() {
    fun testMissingPreparationPortCannotStartDownloadOrClaimInstallationReady() = runBlocking {
        fixture(null) { model, http, _, _, _, _ ->
            assertFalse(model.state.value.installAvailable)
            assertFalse(model.state.value.canInstall)
            withContext(Dispatchers.Main) { model.downloadAndInstall() }
            assertEquals(0, http.downloads.get())
            assertEquals(UpdateProblem.DEVICE_PREPARATION, model.state.value.problem)
        }
    }

    fun testResumeReadsTheStartupPreferenceFromStorage() = runBlocking {
        fixture(null) { model, _, _, _, _, preferences ->
            assertTrue(model.state.value.checkOnStartup)
            withContext(Dispatchers.IO) { preferences.checkOnStartup(false) }
            withContext(Dispatchers.Main) { model.recover() }
            assertFalse(model.state.value.checkOnStartup)
        }
    }

    fun testOpeningScreenDoesNotDownloadAndBlockedPreparationNeverCommits() = runBlocking {
        val port = RecordingInstaller(blocked = true)
        fixture(port) { model, http, verifier, _, directory, _ ->
            assertEquals(0, http.downloads.get())
            assertEquals(0, port.prepares.get())
            withContext(Dispatchers.Main) { model.downloadAndInstall() }
            withTimeout(3_000) { model.state.first { it.work == UpdateWork.IDLE && it.problem == UpdateProblem.DEVICE_PREPARATION } }
            assertEquals(1, http.downloads.get())
            assertEquals(1, verifier.calls.get())
            assertEquals(1, port.prepares.get())
            assertEquals(0, port.commits.get())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    fun testCancellationAfterReadyButBeforeCommitCancelsCapabilityAndDeletesDownload() = runBlocking {
        val port = RecordingInstaller(cancelAtReady = true)
        fixture(port) { model, _, _, _, directory, _ ->
            withContext(Dispatchers.Main) { model.downloadAndInstall() }
            try {
                withTimeout(3_000) {
                    port.cancelled.await()
                    model.state.first { it.work == UpdateWork.IDLE && it.cancelled }
                }
            } catch (_: TimeoutCancellationException) {
                fail("Cleanup did not settle: events=${port.events}, work=${model.state.value.work}, " +
                    "cancelled=${model.state.value.cancelled}, files=${directory.listFiles().orEmpty().map { it.name }}")
            }
            assertEquals(listOf("prepare", "ready", "cancel-requested", "cancel"), port.events.toList())
            assertEquals(1, port.cancels.get())
            assertEquals(0, port.commits.get())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    fun testUserCancellingBlockedDownloadDeletesPartialFileAndReturnsToIdle() = runBlocking {
        val port = RecordingInstaller()
        val http = FixtureHttp(blockDownload = true)
        fixture(port, http) { model, _, _, _, directory, _ ->
            withContext(Dispatchers.Main) { model.downloadAndInstall() }
            withTimeout(3_000) { http.downloadStarted.await() }
            withContext(Dispatchers.Main) { model.cancelDownload() }
            try {
                withTimeout(3_000) {
                    http.downloadClosed.await()
                    model.state.first { it.work == UpdateWork.IDLE && it.cancelled }
                }
            } catch (_: TimeoutCancellationException) {
                fail("Download cancellation did not settle: closed=${http.downloadClosed.isCompleted}, " +
                    "work=${model.state.value.work}, files=${directory.listFiles().orEmpty().map { it.name }}")
            }
            assertEquals(0, port.prepares.get())
            assertEquals(0, port.commits.get())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    fun testClosingScreenAfterCommitDoesNotCancelAnAmbiguousAndroidAttempt() = runBlocking {
        val port = RecordingInstaller(waitInCommit = true)
        fixture(port) { model, _, _, store, directory, _ ->
            withContext(Dispatchers.Main) { model.downloadAndInstall() }
            withTimeout(3_000) { port.committed.await() }
            withContext(Dispatchers.Main) { store.clear() }
            withTimeout(3_000) { port.commitEnded.await() }
            assertEquals(0, port.cancels.get())
            assertEquals(1, port.commits.get())
            // The durable installer now owns these bytes; the screen cannot discard its evidence.
            assertEquals(1, directory.listFiles().orEmpty().count { it.name.endsWith(".apk") })
        }
    }

    private suspend fun fixture(port: RecordingInstaller?, http: FixtureHttp = FixtureHttp(), test: suspend
        (UpdatesViewModel, FixtureHttp, FixtureVerifier, ViewModelStore, File, UpdatePreferences) -> Unit) {
        val prefix = "updates_vm_${UUID.randomUUID()}_"
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        val directory = File(context.cacheDir, prefix)
        assertTrue(directory.mkdirs())
        val repository = OfficialUpdateRepository("0.18.0", directory, http)
        repository.check()
        val verifier = FixtureVerifier()
        val preferences = UpdatePreferences(isolated)
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            ViewModelProvider(store, viewModelFactory { initializer {
                UpdatesViewModel(repository, preferences, verifier, port)
            } })[UpdatesViewModel::class.java]
        }
        try { test(model, http, verifier, store, directory, preferences) }
        finally {
            withContext(Dispatchers.Main) { store.clear() }
            context.deleteSharedPreferences(prefix + "updates")
            directory.deleteRecursively()
        }
    }

    private class FixtureVerifier : UpdateVerifier {
        val calls = AtomicInteger()
        override val installationProblem: UpdateProblem? = null
        override suspend fun verify(release: ReleasePolicy.Candidate, file: File): VerifiedUpdate {
            calls.incrementAndGet()
            val runtime = ApkUpdatePolicy.Runtime("17.26.2", "open-voyah-controls-frida-17.26.2", "b".repeat(64))
            val installed = ApkUpdatePolicy.Identity(ApkUpdatePolicy.PACKAGE, "0.18.0", 20, 28, false, false,
                setOf("a".repeat(64)), setOf("arm64-v8a"), runtime)
            val proposed = ApkUpdatePolicy.Identity(ApkUpdatePolicy.PACKAGE, "0.19.0", 21, 28, false, false,
                setOf("a".repeat(64)), setOf("arm64-v8a"), runtime)
            return VerifiedUpdate(release, file, proposed, installed, "/fixture/base.apk", 100L)
        }
    }

    private class RecordingInstaller(private val blocked: Boolean = false, private val cancelAtReady: Boolean = false,
                                     private val waitInCommit: Boolean = false) : UpdateInstallPort {
        override val state = MutableStateFlow(UpdateInstallState())
        val prepares = AtomicInteger()
        val commits = AtomicInteger()
        val cancels = AtomicInteger()
        val events = ConcurrentLinkedQueue<String>()
        val cancelled = CompletableDeferred<Unit>()
        val committed = CompletableDeferred<Unit>()
        val commitEnded = CompletableDeferred<Unit>()
        private val ready = object : PreparedUpdateInstall { }
        override suspend fun prepare(update: VerifiedUpdate): UpdatePreparation {
            prepares.incrementAndGet()
            events.add("prepare")
            if (blocked) return UpdatePreparation.Blocked(UpdateProblem.DEVICE_PREPARATION)
            events.add("ready")
            if (cancelAtReady) {
                events.add("cancel-requested")
                coroutineContext[Job]?.cancel()
            }
            return UpdatePreparation.Ready(ready)
        }
        override suspend fun commit(prepared: PreparedUpdateInstall) {
            check(prepared === ready)
            events.add("commit")
            commits.incrementAndGet()
            state.value = UpdateInstallState(UpdateInstallPhase.INSTALLING)
            committed.complete(Unit)
            try { if (waitInCommit) awaitCancellation() } finally { commitEnded.complete(Unit) }
        }
        override suspend fun cancel(prepared: PreparedUpdateInstall) {
            check(prepared === ready)
            events.add("cancel")
            cancels.incrementAndGet()
            cancelled.complete(Unit)
        }
        override suspend fun discardInterrupted() { }
        override suspend fun recover() { }
        override fun takeApproval(approval: UpdateInstallApproval): Intent? = null
        override fun approvalUnavailable(sessionId: Int) { }
    }

    private class FixtureHttp(private val blockDownload: Boolean = false) : ReleaseHttp {
        private val apk = "download fixture".toByteArray()
        val downloads = AtomicInteger()
        val downloadStarted = CompletableDeferred<Unit>()
        val downloadClosed = CompletableDeferred<Unit>()
        private val closeDownload = CountDownLatch(1)
        override fun open(url: String): ReleaseHttp.Response {
            val catalog = url == ReleasePolicy.CATALOG_URL
            val bytes = if (catalog) {
                val name = "OpenVoyahControls-v0.19.0.apk"
                val digest = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it.toInt() and 255) }
                JSONArray().put(JSONObject().put("tag_name", "android-v0.19.0").put("draft", false).put("prerelease", false)
                    .put("assets", JSONArray().put(JSONObject().put("name", name)
                        .put("browser_download_url", "${ReleasePolicy.DOWNLOAD_PREFIX}android-v0.19.0/$name")
                        .put("size", apk.size).put("digest", "sha256:$digest")))).toString().toByteArray()
            } else { downloads.incrementAndGet(); apk }
            return object : ReleaseHttp.Response {
                override val status = 200
                override val length = bytes.size.toLong()
                override fun input(): InputStream = if (catalog || !blockDownload) ByteArrayInputStream(bytes)
                    else object : InputStream() {
                        override fun read(): Int = error("Use buffered reads")
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            downloadStarted.complete(Unit)
                            check(closeDownload.await(3, TimeUnit.SECONDS))
                            throw IOException("Fixture closed")
                        }
                    }
                override fun close() {
                    if (!catalog && blockDownload) {
                        closeDownload.countDown()
                        downloadClosed.complete(Unit)
                    }
                }
            }
        }
    }
}
