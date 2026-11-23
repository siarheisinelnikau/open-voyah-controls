package org.openvoyah.controls.presentation.settings

import androidx.lifecycle.ViewModelStore
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.presentation.settings.SettingsArchiveViewModel.State
import org.openvoyah.controls.presentation.settings.SettingsArchiveViewModel.Work

class SettingsArchiveViewModelTest {
    private data class Prepared(override val complete: Boolean = true) : SettingsArchiveDocuments.Restore {
        override val bindingCount = 3
    }

    private class Documents : SettingsArchiveDocuments {
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        val entered = CountDownLatch(1)
        val applyEntered = CountDownLatch(1)
        var gate: CountDownLatch? = null
        var prepared = Prepared()
        var failure: String? = null
        var applyFailure = false
        var languageChanged = false
        var current = "original"
        var applies = 0

        private fun slowProvider() {
            threads.add(Thread.currentThread())
            entered.countDown()
            // Some document providers ignore interruption. Cancellation must still invalidate the
            // eventual read result and must not apply the file when such a provider finally returns.
            val provider = gate ?: return
            var complete = false
            while (!complete) {
                try { complete = provider.await(5, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { }
            }
        }

        override fun export(document: String) { slowProvider(); failure?.let { throw IOException(it) } }
        override fun prepare(document: String): SettingsArchiveDocuments.Restore {
            slowProvider()
            failure?.let { throw IOException(it) }
            return prepared
        }

        override fun apply(restore: SettingsArchiveDocuments.Restore): Boolean {
            applyEntered.countDown()
            slowProvider()
            if (applyFailure) throw IOException("Transaction could not save; original restored")
            applies += 1
            current = "imported"
            return languageChanged
        }
    }

    private class Runtime(var wasConfigured: Boolean = true) : SettingsArchiveRuntime {
        var reconnects = 0
        var publishes = 0
        var error: String? = null
        var throwOnReconnect = false
        val refreshed = CountDownLatch(1)
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        override fun configured(): Boolean = wasConfigured
        override suspend fun reconnect(): String? {
            threads.add(Thread.currentThread())
            reconnects += 1
            refreshed.countDown()
            if (throwOnReconnect) throw IOException("Worker unavailable")
            return error
        }
        override fun publish(error: (String) -> Unit) {
            threads.add(Thread.currentThread())
            publishes += 1
            refreshed.countDown()
            this.error?.let(error)
        }
    }

    private class Fixture : AutoCloseable {
        val ui = Executors.newSingleThreadExecutor { Thread(it, "archive-test-ui") }.asCoroutineDispatcher()
        val ioThread = AtomicReference<Thread>()
        val io = Executors.newSingleThreadExecutor { task ->
            Thread(task, "archive-test-io").also(ioThread::set)
        }.asCoroutineDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + ui)
        val documents = Documents()
        val runtime = Runtime()
        val model = SettingsArchiveViewModel(documents, runtime, io, scope)
        val store = ViewModelStore().apply { put("archive", model) }
        suspend fun ui(action: () -> Unit) = withContext(ui) { action() }
        suspend inline fun <reified T : State> await(): T = withTimeout(5_000) {
            model.state.first { it is T } as T
        }
        suspend fun workerDrained() { withContext(io) { }; withContext(ui) { } }
        override fun close() {
            documents.gate?.countDown()
            store.clear()
            scope.cancel()
            ui.close()
            io.close()
        }
    }

    @Test fun slowDocumentReadKeepsTheUiResponsiveAndCancellationDiscardsLatePreparation() = runBlocking {
        Fixture().use { fixture ->
            fixture.documents.gate = CountDownLatch(1)
            fixture.ui { fixture.model.prepare("content://slow/archive") }
            assertTrue(fixture.documents.entered.await(5, TimeUnit.SECONDS))
            var responsive = false
            fixture.ui { responsive = true; fixture.model.cancel() }
            assertTrue(responsive)
            assertEquals(State.Idle, fixture.model.state.value)
            fixture.documents.gate!!.countDown()
            fixture.workerDrained()
            assertEquals(State.Idle, fixture.model.state.value)
            assertEquals("original", fixture.documents.current)
            assertEquals(0, fixture.documents.applies)
            assertEquals(listOf(fixture.ioThread.get()), fixture.documents.threads)
        }
    }

    @Test fun slowDocumentWriteRunsOnTheWorkerAndDoesNotBlockUiCancellation() = runBlocking {
        Fixture().use { fixture ->
            fixture.documents.gate = CountDownLatch(1)
            fixture.ui { fixture.model.export("content://slow/destination") }
            assertTrue(fixture.documents.entered.await(5, TimeUnit.SECONDS))
            fixture.ui { fixture.model.cancel() }
            fixture.documents.gate!!.countDown()
            fixture.workerDrained()
            assertEquals(State.Idle, fixture.model.state.value)
            assertEquals("original", fixture.documents.current)
            assertEquals(listOf(fixture.ioThread.get()), fixture.documents.threads)
        }
    }

    @Test fun delayedHostDialogDismissKeepsConfirmationAvailableAfterRecreation() = runBlocking {
        Fixture().use { fixture ->
            fixture.ui { fixture.model.prepare("content://settings") }
            val confirmation = fixture.await<State.Confirmation>()
            val dismissal = ArchiveDialogDismissal { fixture.model.cancel() }
            fixture.ui { dismissal.retain() }
            // Android posts onDismiss separately; it can arrive after the lifecycle callback ends.
            fixture.ui { dismissal.deliver() }
            assertEquals(confirmation, fixture.model.state.value)
            assertEquals("original", fixture.documents.current)
            fixture.ui { fixture.model.confirm() }
            fixture.await<State.Completed>()
            assertEquals(1, fixture.documents.applies)
        }
    }

    @Test fun importDoesNotApplyUntilExplicitConfirmationAndCanBeDeclined() = runBlocking {
        Fixture().use { fixture ->
            fixture.ui { fixture.model.prepare("content://settings") }
            val confirmation = fixture.await<State.Confirmation>()
            fixture.workerDrained()
            assertEquals(3, confirmation.restore.bindingCount)
            assertEquals("original", fixture.documents.current)
            assertEquals(0, fixture.documents.applies)
            assertEquals(0, fixture.runtime.reconnects)
            fixture.ui { fixture.model.cancel(); fixture.model.confirm() }
            fixture.workerDrained()
            assertEquals(State.Idle, fixture.model.state.value)
            assertEquals("original", fixture.documents.current)
        }
    }

    @Test fun onlyACompleteRestoreOfAnAlreadyConfiguredBridgeReconnects() = runBlocking {
        for (complete in listOf(false, true)) for (configured in listOf(false, true)) {
            Fixture().use { fixture ->
                fixture.documents.prepared = Prepared(complete)
                fixture.runtime.wasConfigured = configured
                fixture.ui { fixture.model.prepare("content://settings") }
                fixture.await<State.Confirmation>()
                fixture.ui { fixture.model.confirm() }
                fixture.await<State.Completed>()
                fixture.workerDrained()
                assertEquals("imported", fixture.documents.current)
                assertEquals(if (complete && configured) 1 else 0, fixture.runtime.reconnects)
                assertEquals(if (complete) 0 else 1, fixture.runtime.publishes)
                assertTrue(fixture.documents.threads.all { it === fixture.ioThread.get() })
                assertTrue(fixture.runtime.threads.all { it === fixture.ioThread.get() })
            }
        }
    }

    @Test fun readAndTransactionFailuresPreserveSettingsAndDoNotRefreshWorkers() = runBlocking {
        Fixture().use { fixture ->
            fixture.documents.failure = "Provider unavailable"
            fixture.ui { fixture.model.prepare("content://broken") }
            assertEquals("Provider unavailable", fixture.await<State.Failed>().message)
            assertEquals("original", fixture.documents.current)
            fixture.ui { fixture.model.cancel() }
            fixture.documents.failure = null
            fixture.documents.applyFailure = true
            fixture.ui { fixture.model.prepare("content://settings") }
            fixture.await<State.Confirmation>()
            fixture.ui { fixture.model.confirm() }
            fixture.await<State.Failed>()
            assertEquals("original", fixture.documents.current)
            assertEquals(0, fixture.runtime.reconnects)
            assertEquals(0, fixture.runtime.publishes)
        }
    }

    @Test fun reconnectFailureReportsSavedSettingsAndKeepsTheCompletion() = runBlocking {
        Fixture().use { fixture ->
            fixture.runtime.throwOnReconnect = true
            fixture.ui { fixture.model.prepare("content://settings") }
            fixture.await<State.Confirmation>()
            fixture.ui { fixture.model.confirm() }
            val result = fixture.await<State.Completed>()
            assertEquals("Worker unavailable", result.runtimeError)
            assertEquals("imported", fixture.documents.current)
            assertFalse(result.exported)
        }
    }

    @Test fun languageRefreshIsConsumedOnceWhileTheCompletionSurvivesRecreation() = runBlocking {
        Fixture().use { fixture ->
            fixture.documents.languageChanged = true
            fixture.ui { fixture.model.prepare("content://settings") }
            fixture.await<State.Confirmation>()
            fixture.ui { fixture.model.confirm() }
            val result = fixture.await<State.Completed>()
            assertTrue(result.languageChanged)
            assertTrue(result.refreshPending)
            fixture.ui { fixture.model.acknowledgeRefresh(result) }
            val retained = fixture.await<State.Completed>()
            assertFalse(retained.refreshPending)
            assertFalse(retained.languageChanged)
            fixture.ui { fixture.model.acknowledgeRefresh(result) }
            assertEquals(retained, fixture.model.state.value)
        }
    }

    @Test fun closingAConfirmedRestoreLetsTheTransactionFinishWithoutPublishingStaleUiState() = runBlocking {
        for (complete in listOf(false, true)) Fixture().use { fixture ->
            fixture.documents.prepared = Prepared(complete)
            fixture.ui { fixture.model.prepare("content://settings") }
            fixture.await<State.Confirmation>()
            fixture.documents.gate = CountDownLatch(1)
            fixture.ui { fixture.model.confirm() }
            assertTrue(fixture.documents.applyEntered.await(5, TimeUnit.SECONDS))
            fixture.ui { fixture.model.cancel() }
            assertEquals(State.Working(Work.APPLY), fixture.model.state.value)
            fixture.ui { fixture.store.clear() }
            fixture.documents.gate!!.countDown()
            fixture.workerDrained()
            assertEquals("imported", fixture.documents.current)
            assertEquals(if (complete) 1 else 0, fixture.runtime.reconnects)
            assertEquals(if (complete) 0 else 1, fixture.runtime.publishes)
            assertEquals(State.Idle, fixture.model.state.value)
        }
    }
}
