package org.openvoyah.controls.presentation.voice

import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.VoiceCommandUseCase
import org.openvoyah.controls.domain.voice.SpeechEvent
import org.openvoyah.controls.domain.voice.VoiceInput
import org.openvoyah.controls.domain.voice.VoiceOutcome
import org.openvoyah.controls.domain.voice.VoiceResult
import org.openvoyah.controls.domain.voice.VoiceSequence

class VoiceSequenceTest {
    @Test fun wholePhraseAndExactLongestSegmentWin() {
        val sequence = VoiceSequence("en") { it in setOf("airflow body and feet", "pause music", "next track") }
        assertNull(sequence.split("airflow body and feet"))
        assertEquals(listOf("airflow body and feet", "next track", "pause music"),
            sequence.split("Airflow body and feet and next track and then pause music."))
        assertEquals(listOf("unknown", "next track"), sequence.split("unknown then next track"))
        assertNull(sequence.split("stop there then read this"))
        val russian = VoiceSequence("ru") { it in setOf("пауза", "следующий трек") }
        assertEquals(listOf("пауза", "следующий трек"), russian.split("пауза потом следующий трек"))
        val kazakh = VoiceSequence("kk") { it in setOf("қосу", "келесі ән") }
        assertEquals(listOf("қосу", "келесі ән"), kazakh.split("қосу содан кейін келесі ән"))
    }

    @Test fun duplicateSingleOnlyAndOverflowNeverDispatch() {
        val requests = mutableListOf<String>()
        display(SpeechEvent.Recognized("pause music then pause media then reboot modem then next track")) { id ->
            requests += id
            VoiceResult("requested", outcome = VoiceOutcome.LOCAL_REQUESTED)
        }.use { model ->
            model.listen()
            assertEquals(listOf("MediaPause"), requests)
            assertEquals(listOf("requested", "duplicate", "single", "limit"), model.state.value.steps.map { it.result })
        }
    }

    @Test fun unknownDeliverySealsQueueAndUnmatchedSpeechCannotRun() {
        val requests = mutableListOf<String>()
        val phrase = "pause music then next track"
        display(SpeechEvent.Recognized(phrase)) { id ->
            requests += id
            VoiceResult("unconfirmed", failed = true, outcome = VoiceOutcome.VEHICLE_UNCONFIRMED)
        }.use { model ->
            model.listen()
            model.listen()
            assertEquals(listOf("MediaPause"), requests)
            assertTrue(model.state.value.failed)
            assertEquals(listOf("unconfirmed", "stopped"), model.state.value.steps.map { it.result })
        }
        display(SpeechEvent.Unmatched(phrase)) { error("Unmatched speech cannot dispatch") }.use { model ->
            model.listen()
            assertTrue(model.state.value.steps.isEmpty())
        }
    }

    @Test fun exactCustomPhraseCanContainASeparatorAndRunsOnlyOnce() {
        var calls = 0
        val phrase = "wake then relax"
        val custom: Map<String, suspend () -> VoiceResult> = mapOf(phrase to {
            calls++
            VoiceResult("requested", outcome = VoiceOutcome.LOCAL_REQUESTED)
        })
        display(SpeechEvent.Recognized(phrase), custom) { error("Expected custom routine") }.use { model ->
            model.listen()
            assertEquals(1, calls)
            assertTrue(model.state.value.steps.isEmpty())
        }
    }

    private fun display(event: SpeechEvent, custom: Map<String, suspend () -> VoiceResult> = emptyMap(),
                        run: (String) -> VoiceResult): VoiceViewModel {
        val entries = "MediaPause\tMedia\tpause music|pause media\nMediaNext\tMedia\tnext track\nRebootTbox\tOther\treboot modem\n"
        val catalog = VoiceCatalog(ByteArrayInputStream(entries.toByteArray()))
        val input = object : VoiceInput {
            override fun listen(language: String, grammar: Set<String>) = flowOf(event)
            override suspend fun repair(language: String) = Unit
        }
        return VoiceViewModel(catalog, "en", VoiceCommandUseCase({ it != "RebootTbox" }, { "" }), input,
            { run(it.command().id()) }, VoiceViewModel.Labels("preparing", "executing", "unknown", "failed", "weather"),
            Dispatchers.Unconfined, sequenceLabels = VoiceViewModel.SequenceLabels(
                "sending", "finished", "duplicate", "single", "last", "stopped", "limit", "timeout"), custom = custom)
    }
}
