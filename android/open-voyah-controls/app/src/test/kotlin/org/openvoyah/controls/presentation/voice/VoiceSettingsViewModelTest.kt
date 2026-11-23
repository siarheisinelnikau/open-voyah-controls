package org.openvoyah.controls.presentation.voice

import androidx.lifecycle.ViewModelStore
import java.io.ByteArrayInputStream
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.VoiceAudioOptions

class VoiceSettingsViewModelTest {
    private companion object {
        fun catalog(language: String) = VoiceCatalog(ByteArrayInputStream(
            "Music\tApps\t${if (language == "en") "music" else "музыка"}\nSeatLevelDefault\tClimate\tseat level\n".toByteArray()))

        fun configuration(language: String = "ru") = VoiceSettingsConfiguration(catalog(language), language,
            false, emptySet(), mapOf("SeatLevelDefault" to 2), false, false, false, false, false)
    }

    private class Source : VoiceSettingsSource {
        @Volatile var current = configuration()
        private var previous = current
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        val changes = Collections.synchronizedList(mutableListOf<VoiceSettingsChange>())
        val readEntered = CountDownLatch(1)
        val writeEntered = CountDownLatch(1)
        val documentEntered = CountDownLatch(1)
        val waiting = Collections.synchronizedSet(mutableSetOf<CountDownLatch>())
        var readGate: CountDownLatch? = null
        var writeGate: CountDownLatch? = null
        var documentGate: CountDownLatch? = null
        var onlineFailure: String? = null
        var loadFailure: String? = null
        var imports = 0
        var exports = 0

        private fun worker() { threads.add(Thread.currentThread()) }
        private fun await(gate: CountDownLatch?) {
            if (gate == null) return
            waiting.add(gate)
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < deadline) {
                    try { if (gate.await(100, TimeUnit.MILLISECONDS)) return }
                    catch (_: InterruptedException) { }
                }
                throw IOException("Fixture provider timed out")
            } finally {
                waiting.remove(gate)
            }
        }

        override fun load(): VoiceSettingsConfiguration {
            worker()
            val beforeWait = current
            readEntered.countDown()
            await(readGate)
            loadFailure?.let { throw IOException(it) }
            return beforeWait
        }

        override fun change(change: VoiceSettingsChange) {
            worker()
            writeEntered.countDown()
            await(writeGate)
            changes.add(change)
            val before = current
            current = when (change) {
                is VoiceSettingsChange.Language -> current.copy(language = change.language, catalog = catalog(change.language))
                is VoiceSettingsChange.Engine -> current.copy(engine = change.engine)
                is VoiceSettingsChange.App -> current.copy(assigned = if (change.component.isEmpty())
                    current.assigned - change.command else current.assigned + change.command)
                is VoiceSettingsChange.Level -> current.copy(levels = current.levels + (change.command to change.level))
                is VoiceSettingsChange.Windows -> current.copy(doubleSmoke = change.smoke, doubleVentilation = change.ventilation)
                is VoiceSettingsChange.Audio -> current.copy(duckMedia = change.duck, spokenFeedback = change.feedback,
                    audioOptions = change.options, offlineVoiceName = change.offlineVoiceName, readyPrompt = change.readyPrompt)
                is VoiceSettingsChange.Contact -> current
                VoiceSettingsChange.RestorePrevious -> { loadFailure = null; previous }
            }
            previous = before
        }

        override fun saveOnline(enabled: Boolean, key: String) {
            worker()
            onlineFailure?.let { throw IOException(it) }
            current = current.copy(onlineConfigured = enabled)
        }
        override suspend fun repairModel() { worker() }
        override suspend fun saveDocument(document: String) { worker(); exports += 1 }
        override suspend fun restoreDocument(document: String) {
            worker()
            documentEntered.countDown()
            await(documentGate)
            currentCoroutineContext().ensureActive()
            imports += 1
            current = configuration("en").copy(assigned = setOf("Music"), doubleSmoke = true)
        }
    }

    private class Fixture : AutoCloseable {
        val ui = Executors.newSingleThreadExecutor { Thread(it, "voice-settings-ui") }.asCoroutineDispatcher()
        val ioThread = AtomicReference<Thread>()
        val io = Executors.newSingleThreadExecutor { task ->
            Thread(task, "voice-settings-io").also(ioThread::set)
        }.asCoroutineDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + ui)
        val source = Source()
        val model = VoiceSettingsViewModel(source, io, scope)
        private val store = ViewModelStore().apply { put("voice-settings", model) }
        val owner = Any()
        suspend fun ui(action: () -> Unit) = withContext(ui) { action() }
        suspend fun load() { ui { model.enter(owner) }; settled() }
        suspend fun settled() = withTimeout(5_000) {
            model.state.first { !it.busy && it.configuration != null }
        }
        suspend fun workerDrained() { withContext(io) { }; withContext(ui) { } }
        override fun close() {
            source.readGate?.countDown()
            source.writeGate?.countDown()
            source.documentGate?.countDown()
            synchronized(source.waiting) { source.waiting.toList().forEach(CountDownLatch::countDown) }
            store.clear()
            scope.cancel()
            ui.close()
            io.close()
        }
    }

    @Test fun settingsChangesRefreshCatalogAssignmentsLevelsAndAudioOnTheWorker() = runBlocking {
        Fixture().use { f ->
            f.load()
            f.ui { assertTrue(f.model.language("en")) }
            assertEquals("music", f.settled().configuration!!.catalog.byId("Music").title())
            f.ui { f.model.app("Music", "example.app/.Main") }
            assertEquals(setOf("Music"), f.settled().configuration!!.assigned)
            f.ui { f.model.level("SeatLevelDefault", 3) }
            assertEquals(3, f.settled().configuration!!.levels["SeatLevelDefault"])
            val audioOptions = VoiceAudioOptions(75, 40, true, 30)
            val selection = VoiceSettingsChange.Audio(true, true, audioOptions, "en", "en-US-local", "Go ahead.")
            f.ui {
                f.model.openAudio()
                assertFalse(f.model.audio(selection.copy(language = "ru")))
                assertFalse(f.model.audio(selection.copy(readyPrompt = "Line\nbreak")))
                assertTrue(f.model.audio(selection))
            }
            val audio = f.settled()
            val config = audio.configuration!!
            assertTrue(config.duckMedia && config.spokenFeedback)
            assertEquals(audioOptions, config.audioOptions)
            assertEquals("en-US-local", config.offlineVoiceName)
            assertEquals("Go ahead.", config.readyPrompt)
            assertFalse(audio.audioOpen)
            assertTrue(f.source.threads.all { it === f.ioThread.get() })
        }
    }

    @Test fun oneWindowSaveAndRestorePreviousKeepBothOptionsTogether() = runBlocking {
        Fixture().use { f ->
            f.load()
            f.ui { f.model.windows(true, true) }
            assertTrue(f.settled().configuration!!.doubleVentilation)
            assertEquals(listOf(VoiceSettingsChange.Windows(true, true)), f.source.changes)
            f.ui { f.model.restorePrevious() }
            val restored = f.settled().configuration!!
            assertFalse(restored.doubleSmoke)
            assertFalse(restored.doubleVentilation)
        }
    }

    @Test fun aRecreatedDestinationKeepsItsAuthorizedSaveAndRejectsDuplicateWrites() = runBlocking {
        Fixture().use { f ->
            f.load()
            f.source.writeGate = CountDownLatch(1)
            f.ui { f.model.windows(true, false) }
            assertTrue(f.source.writeEntered.await(5, TimeUnit.SECONDS))
            val replacement = Any()
            f.ui {
                assertFalse(f.model.language("en"))
                f.model.leave(f.owner, recreating = true)
                f.model.enter(replacement)
            }
            f.source.writeGate!!.countDown()
            val saved = f.settled().configuration!!
            assertTrue(saved.doubleSmoke)
            assertEquals("ru", saved.language)
            assertEquals(1, f.source.changes.size)
        }
    }

    @Test fun navigationCancelsLateReadsAndReturningReadsTheCurrentStore() = runBlocking {
        Fixture().use { f ->
            val gate = CountDownLatch(1)
            f.source.readGate = gate
            f.ui { f.model.enter(f.owner) }
            assertTrue(f.source.readEntered.await(5, TimeUnit.SECONDS))
            f.ui { f.model.leave(f.owner) }
            assertFalse(f.model.state.value.busy)
            f.source.current = configuration("en")
            f.source.readGate = null
            f.ui { f.model.enter(Any()) }
            gate.countDown()
            assertEquals("en", f.settled().configuration!!.language)
            f.workerDrained()
            assertEquals("en", f.model.state.value.configuration!!.language)
        }
    }

    @Test fun leavingAnAuthorizedWriteFinishesItBeforeTheReturningReadWithoutStaleCompletion() = runBlocking {
        Fixture().use { f ->
            f.load()
            f.source.writeGate = CountDownLatch(1)
            f.ui { f.model.windows(true, true) }
            assertTrue(f.source.writeEntered.await(5, TimeUnit.SECONDS))
            f.ui { f.model.leave(f.owner); f.model.enter(Any()) }
            f.source.writeGate!!.countDown()
            val current = f.settled()
            assertTrue(current.configuration!!.doubleSmoke)
            assertTrue(current.configuration.doubleVentilation)
            assertEquals(1, f.source.changes.size)
            assertEquals(null, current.message)
        }
    }

    @Test fun aReturningDocumentPreemptsResumeReadInsteadOfLosingTheImport() = runBlocking {
        Fixture().use { f ->
            val gate = CountDownLatch(1)
            f.source.readGate = gate
            f.ui { f.model.enter(f.owner) }
            assertTrue(f.source.readEntered.await(5, TimeUnit.SECONDS))
            f.ui { assertTrue(f.model.transfer("content://voice/import", restoring = true)) }
            f.source.readGate = null
            gate.countDown()
            val imported = f.settled().configuration!!
            assertEquals("en", imported.language)
            assertEquals(setOf("Music"), imported.assigned)
            assertEquals(1, f.source.imports)
        }
    }

    @Test fun leavingDuringDocumentReadDoesNotApplyItOrPublishSuccess() = runBlocking {
        Fixture().use { f ->
            f.load()
            f.source.documentGate = CountDownLatch(1)
            f.ui { f.model.transfer("content://voice/slow", restoring = true) }
            assertTrue(f.source.documentEntered.await(5, TimeUnit.SECONDS))
            f.ui { f.model.leave(f.owner) }
            f.source.documentGate!!.countDown()
            f.workerDrained()
            assertEquals(0, f.source.imports)
            assertEquals("ru", f.model.state.value.configuration!!.language)
            assertFalse(f.model.state.value.busy)
        }
    }

    @Test fun onlineFailureNeverPlacesTheCredentialInScreenState() = runBlocking {
        Fixture().use { f ->
            f.load()
            val key = "private-key-not-for-screen-state"
            f.source.onlineFailure = "Keystore rejected $key"
            f.ui { f.model.openOnline(); f.model.saveOnline(true, key) }
            val failed = f.settled()
            assertTrue(failed.onlineOpen && failed.onlineFailed)
            assertFalse(failed.toString().contains(key))
            assertFalse(failed.configuration!!.onlineConfigured)
            f.source.onlineFailure = null
            f.ui { f.model.saveOnline(true, key) }
            val saved = f.settled()
            assertTrue(saved.configuration!!.onlineConfigured)
            assertFalse(saved.onlineOpen)
        }
    }

    @Test fun previousSettingsCanRecoverAnInitialCatalogFailureWithoutRecreatingTheOwner() = runBlocking {
        Fixture().use { f ->
            f.source.loadFailure = "Unreadable voice settings"
            f.ui { f.model.enter(f.owner) }
            val failed = withTimeout(5_000) { f.model.state.first { !it.busy && it.startupError != null } }
            assertEquals("Unreadable voice settings", failed.startupError)
            f.ui { assertTrue(f.model.restorePrevious()) }
            val recovered = f.settled()
            assertEquals("ru", recovered.configuration!!.language)
            assertEquals(null, recovered.startupError)
        }
    }
}
