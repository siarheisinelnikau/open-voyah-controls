package org.openvoyah.controls.presentation.voice

import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.VoiceCommandUseCase
import org.openvoyah.controls.domain.voice.SpeechEvent
import org.openvoyah.controls.domain.voice.VoiceInput
import org.openvoyah.controls.domain.voice.VoiceOutcome
import org.openvoyah.controls.domain.voice.VoiceResult

/** The popup shows what it heard next to the answer, and a heard phrase alone never runs anything. */
class VoiceTranscriptTest {
    private val actions = mutableListOf<String>()
    private val interpreted = mutableListOf<String>()

    @Test fun aPhraseOutsideTheCatalogStaysVisibleBesideTheNoMatchError() = runBlocking {
        listen(SpeechEvent.Recognized("music please now")).use { voice ->
            voice.listen()
            assertDone(voice, "no match", phrase = "music please now", failed = true)
            assertTrue(actions.isEmpty())
        }
    }

    @Test fun aFreeTranscriptIsShownButNeitherDispatchedNorInterpreted() = runBlocking {
        listen(SpeechEvent.Unmatched("open the sunroof halfway"), natural = true).use { voice ->
            voice.listen()
            assertDone(voice, "no match", phrase = "open the sunroof halfway", failed = true)
            assertTrue(actions.isEmpty())
            assertTrue(interpreted.isEmpty())
        }
    }

    @Test fun aNaturalPhraseWithoutOnlineInterpretationKeepsItsText() = runBlocking {
        listen(SpeechEvent.Natural("a little louder")).use { voice ->
            voice.listen()
            assertDone(voice, "no match", phrase = "a little louder", failed = true)
        }
    }

    @Test fun nothingHeardKeepsTheShortCaptureMessageWithoutAPhrase() = runBlocking {
        listen(SpeechEvent.Failed("Command not recognized", VoiceOutcome.UNRECOGNIZED)).use { voice ->
            voice.listen()
            assertDone(voice, "Command not recognized", phrase = null, failed = true)
        }
    }

    @Test fun aCatalogPhraseStillRunsAndKeepsItsPhrase() = runBlocking {
        listen(SpeechEvent.Recognized("music")).use { voice ->
            voice.listen()
            assertDone(voice, "done", phrase = "music", failed = false)
            assertEquals(listOf("Music"), actions)
        }
    }

    @Test fun withoutASeparateLabelANoMatchReadsAsUnknown() {
        val labels = VoiceViewModel.Labels("preparing", "executing", "unknown", "failed", "offline")
        assertEquals("unknown", labels.noMatch)
    }

    private fun assertDone(voice: VoiceViewModel, status: String, phrase: String?, failed: Boolean) {
        val state = voice.state.value
        assertEquals(VoiceViewModel.Phase.DONE, state.phase)
        assertEquals(status, state.status)
        if (phrase == null) assertNull(state.phrase) else assertEquals(phrase, state.phrase)
        if (failed) assertTrue(state.failed) else assertFalse(state.failed)
    }

    private fun listen(event: SpeechEvent, natural: Boolean = false): VoiceViewModel {
        val catalog = VoiceCatalog(ByteArrayInputStream("Music\tApps\tmusic\n".toByteArray()))
        val input = object : VoiceInput {
            override fun listen(language: String, grammar: Set<String>) = flowOf(event)
            override suspend fun repair(language: String) = Unit
        }
        val interpreter: (suspend (String) -> VoiceResult)? = if (natural) { phrase ->
            interpreted += phrase
            VoiceResult("interpreted")
        } else null
        return VoiceViewModel(catalog, "en", VoiceCommandUseCase({ it == "Music" }, { "" }), input,
            { request -> actions += request.command().id(); VoiceResult("done") },
            VoiceViewModel.Labels("preparing", "executing", "unknown", "failed", "offline", noMatch = "no match"),
            Dispatchers.Unconfined, natural = interpreter)
    }
}
