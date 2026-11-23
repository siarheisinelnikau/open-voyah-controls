package org.openvoyah.controls.platform.voice

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndCueOverlapTest {
    @Test fun thePhraseIsDecodedWhileTheEndSoundPlays() = runBlocking(Dispatchers.IO) {
        val cueDone = AtomicBoolean(false)
        val started = System.nanoTime()
        val result = alongsideCue({ delay(400); cueDone.set(true) }) {
            Thread.sleep(400) // Native decoding blocks its thread.
            "phrase"
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertEquals("phrase", result)
        assertTrue("Spoken feedback still follows the end sound", cueDone.get())
        assertTrue("Took $elapsed ms; the cue and the decoding must overlap", elapsed < 700)
    }

    @Test fun withoutAnEndSoundOnlyTheDecodingRuns() = runBlocking {
        assertEquals(7, alongsideCue(null) { 7 })
    }
}
