package org.openvoyah.controls.platform.voice

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openvoyah.controls.domain.voice.SpeechEngine

class VoskFallbackTest {
    @Test fun aWorkingEngineIsUsedAsChosen() {
        val failures = mutableListOf<SpeechEngine>()
        val result = withVoskFallback(SpeechEngine.ZIPFORMER, { it }, { engine, _ -> failures += engine })
        assertEquals(SpeechEngine.ZIPFORMER to false, result)
        assertTrue(failures.isEmpty())
    }

    @Test fun anOptionalEngineThatCannotStartFallsBackToVoskForTheSameRequest() {
        // The car failed in a class initializer; Android reports that as a LinkageError subclass on the next use.
        for (failure in listOf<Throwable>(ExceptionInInitializerError(IllegalArgumentException("flags")),
            NoClassDefFoundError("TranscriptMatcher"), IOException("model"))) {
            val failures = mutableListOf<Pair<SpeechEngine, Throwable>>()
            val result = withVoskFallback(SpeechEngine.ZIPFORMER, { engine ->
                if (engine == SpeechEngine.ZIPFORMER) throw failure
                engine
            }, { engine, cause -> failures += engine to cause })
            assertEquals(SpeechEngine.VOSK to true, result)
            assertEquals(listOf(SpeechEngine.ZIPFORMER to failure), failures)
        }
    }

    @Test fun voskFailuresAndCancellationAreNotHidden() {
        try {
            withVoskFallback(SpeechEngine.VOSK, { throw IOException("Vosk model missing") }, { _, _ -> fail("No fallback from Vosk") })
            fail("A Vosk failure must reach the caller")
        } catch (_: IOException) { }
        try {
            withVoskFallback(SpeechEngine.ZIPFORMER, { throw CancellationException("closed") }, { _, _ -> fail("Not a failure") })
            fail("Cancellation must reach the caller")
        } catch (_: CancellationException) { }
    }

    @Test fun theReasonNamesTheInnermostCause() {
        val failure = NoClassDefFoundError("x").apply { initCause(ExceptionInInitializerError(IllegalArgumentException("y"))) }
        assertEquals("IllegalArgumentException", rootCauseName(failure))
        assertEquals("IOException", rootCauseName(IOException("plain")))
    }
}
