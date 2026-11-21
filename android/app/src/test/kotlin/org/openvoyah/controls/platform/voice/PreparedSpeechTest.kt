package org.openvoyah.controls.platform.voice

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PreparedSpeechTest {
    private class FakeModel(val language: String)

    private class FakeRecognizer(val model: FakeModel, val grammar: String, private val events: MutableList<String>) :
        AutoCloseable {
        override fun close() { events += "close ${model.language}:$grammar" }
    }

    private class Harness(private val loadGate: CountDownLatch? = null, private val loading: CountDownLatch? = null) {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val reports: MutableList<PreparedSpeech.Preparation> = Collections.synchronizedList(mutableListOf())
        var failLoad = false
        val speech = PreparedSpeech<FakeModel, FakeRecognizer>(
            loadModel = { language, _ ->
                events += "load $language"
                loading?.countDown()
                loadGate?.await(5, TimeUnit.SECONDS)
                if (failLoad) throw IOException("model unavailable")
                FakeModel(language)
            },
            releaseModel = { events += "release" },
            openRecognizer = { model, grammar ->
                events += "open ${model.language}:$grammar"
                FakeRecognizer(model, grammar, events)
            },
            reset = { events += "reset ${it.model.language}:${it.grammar}" },
            clock = { 0L },
            report = { reports += it },
        )

        suspend fun request(language: String, grammar: String) =
            speech.lock.withLock { speech.prepare(language, grammar, "request") { true } }

        fun outcomes() = reports.map { "${it.trigger}:${it.outcome}" }
    }

    @Test fun warmUpPreparesModelAndRecognizerOnceAndTheFirstRequestReusesThem() = runBlocking {
        val h = Harness()
        val warm = h.speech.warm("ru", "commands", "startup")
        val request = h.request("ru", "commands")
        assertSame(warm.model, request.model)
        assertSame(warm.recognizer, request.recognizer)
        assertEquals(listOf("load ru", "open ru:commands", "reset ru:commands"), h.events)
        assertEquals(listOf("startup:ready", "request:reused"), h.outcomes())
    }

    @Test fun aRequestDuringTheWarmUpWaitsForItInsteadOfLoadingAgain() = runBlocking {
        val gate = CountDownLatch(1)
        val loading = CountDownLatch(1)
        val h = Harness(gate, loading)
        val warm = launch(Dispatchers.IO) { h.speech.warm("ru", "commands", "startup") }
        withContext(Dispatchers.IO) { assertTrue(loading.await(5, TimeUnit.SECONDS)) }
        val request = async(Dispatchers.IO) { h.request("ru", "commands") }
        delay(150)
        assertFalse("The request must wait while the model loads", request.isCompleted)
        gate.countDown()
        val ready = request.await()
        warm.join()
        assertEquals("ru", ready.model.language)
        assertEquals(1, h.events.count { it.startsWith("load") })
        assertEquals(1, h.events.count { it.startsWith("open") })
        assertEquals(listOf("startup:ready", "request:reused"), h.outcomes())
    }

    @Test fun aLanguageChangeReleasesTheOldModelBeforeLoadingTheNewOne() = runBlocking {
        val h = Harness()
        h.speech.warm("ru", "команды", "startup")
        h.events.clear()
        val english = h.speech.warm("en", "commands", "settings")
        assertEquals("en", english.model.language)
        // One model at a time: the recognizer and the Russian model are gone before the English load starts.
        assertEquals(listOf("close ru:команды", "release", "load en", "open en:commands"), h.events)
    }

    @Test fun anotherGrammarReplacesOnlyTheRecognizer() = runBlocking {
        val h = Harness()
        val first = h.speech.warm("ru", "commands", "startup")
        h.events.clear()
        val second = h.request("ru", "commands and a contact")
        assertSame(first.model, second.model)
        assertEquals(listOf("close ru:commands", "open ru:commands and a contact"), h.events)
    }

    @Test fun setupPreparesOnlyTheModelAndTheRequestAddsItsRecognizer() = runBlocking {
        val h = Harness()
        assertNull(h.speech.warm("ru", null, "setup").recognizer)
        h.request("ru", "commands")
        assertEquals(listOf("load ru", "open ru:commands"), h.events)
    }

    @Test fun aFailedLoadIsReportedAndTheNextRequestTriesAgain() = runBlocking {
        val h = Harness()
        h.failLoad = true
        try {
            h.speech.warm("ru", "commands", "startup")
            fail("The load failure must reach the caller")
        } catch (_: IOException) { }
        assertEquals("failed", h.reports.single().outcome)
        h.failLoad = false
        h.request("ru", "commands")
        assertEquals(2, h.events.count { it == "load ru" })
        assertEquals(listOf("startup:failed", "request:ready"), h.outcomes())
    }

    @Test fun repairDropsEverythingBeforeTheFilesChange() = runBlocking {
        val h = Harness()
        h.speech.warm("ru", "commands", "startup")
        h.events.clear()
        h.speech.lock.withLock { h.speech.invalidate { h.events += "files" } }
        assertEquals(listOf("close ru:commands", "release", "files"), h.events)
        h.request("ru", "commands")
        assertTrue("load ru" in h.events)
    }

    @Test fun preparationOutsideTheSpeechLockIsRejected() {
        val h = Harness()
        try {
            h.speech.prepare("ru", "commands", "request") { true }
            fail("Preparation must hold the speech lock")
        } catch (_: IllegalStateException) { }
        assertTrue(h.events.isEmpty())
    }
}
