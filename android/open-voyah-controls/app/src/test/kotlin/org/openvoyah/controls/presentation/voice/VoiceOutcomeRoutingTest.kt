package org.openvoyah.controls.presentation.voice

import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.VoiceAudioOptions.FeedbackStyle
import org.openvoyah.controls.core.voice.VoiceCommandUseCase
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Request
import org.openvoyah.controls.domain.voice.SpeechEvent
import org.openvoyah.controls.domain.voice.VoiceFeedback
import org.openvoyah.controls.domain.voice.VoiceInput
import org.openvoyah.controls.domain.voice.VoiceOutcome
import org.openvoyah.controls.domain.voice.VoiceResult

class VoiceOutcomeRoutingTest {
    @Test fun legacyReadbackIsEmittedOnceBeforeTheResult() = runBlocking {
        val pending = CompletableDeferred<VoiceResult>()
        model(FeedbackStyle.READBACK, "seat one") { pending.await() }.use { voice ->
            voice.listen()
            assertEquals(VoiceFeedback.Readback("seat one"), withTimeout(1_000) { voice.feedback.first() })
            assertEquals(VoiceViewModel.Phase.EXECUTING, voice.state.value.phase)
            pending.complete(VoiceResult("Refused", true, VoiceOutcome.PARK_REQUIRED))
            assertNull(withTimeoutOrNull(30) { voice.feedback.first() })
        }
    }

    @Test fun outcomeWaitsForTheActualResultAndDropsItsPrivateText() = runBlocking {
        val outcomes = listOf(VoiceOutcome.VEHICLE_ACCEPTED, VoiceOutcome.VEHICLE_CONFIRMED, VoiceOutcome.VEHICLE_PARTIAL,
            VoiceOutcome.VEHICLE_APPROVAL_REQUIRED, VoiceOutcome.PARK_REQUIRED, VoiceOutcome.VEHICLE_UNCONFIRMED)
        for (result in outcomes) {
            val pending = CompletableDeferred<VoiceResult>()
            val commands = mutableListOf<String>()
            model(FeedbackStyle.OUTCOME, "seat one") { request ->
                commands += request.command().id()
                pending.await()
            }.use { voice ->
                voice.listen()
                assertEquals(listOf("GetFromMemoryCellOne"), commands)
                assertNull(withTimeoutOrNull(30) { voice.feedback.first() })
                pending.complete(VoiceResult("Fixture exception details and contact number", true, result))
                assertEquals(VoiceFeedback.Outcome(result), withTimeout(1_000) { voice.feedback.first() })
                assertNull(withTimeoutOrNull(30) { voice.feedback.first() })
            }
        }
    }

    @Test fun localOutcomeAndUnrecognizedAudioUseDifferentFeedback() = runBlocking {
        model(FeedbackStyle.OUTCOME, "music") { VoiceResult("Application-specific detail", outcome = VoiceOutcome.LOCAL_REQUESTED) }
            .use { voice ->
                voice.listen()
                assertEquals(VoiceFeedback.Outcome(VoiceOutcome.LOCAL_REQUESTED), withTimeout(1_000) { voice.feedback.first() })
            }
        model(FeedbackStyle.OUTCOME, "unknown transcript") { error("Unrecognized text must not dispatch") }.use { voice ->
            voice.listen()
            assertEquals(VoiceFeedback.Outcome(VoiceOutcome.UNRECOGNIZED), withTimeout(1_000) { voice.feedback.first() })
        }
    }

    private fun model(style: FeedbackStyle, phrase: String, action: suspend (Request) -> VoiceResult): VoiceViewModel {
        val catalog = VoiceCatalog(ByteArrayInputStream("Music\tApps\tmusic\nGetFromMemoryCellOne\tSeat\tseat one\n".toByteArray()))
        val input = object : VoiceInput {
            override fun listen(language: String, grammar: Set<String>) = flowOf(SpeechEvent.Recognized(phrase))
            override suspend fun repair(language: String) = Unit
        }
        return VoiceViewModel(catalog, "en", VoiceCommandUseCase({ it == "Music" }, { "" }), input, action,
            VoiceViewModel.Labels("preparing", "executing", "unknown", "failed", "offline"), Dispatchers.Unconfined,
            feedbackStyle = style)
    }
}
