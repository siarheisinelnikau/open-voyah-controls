package org.openvoyah.controls

import android.test.AndroidTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.openvoyah.controls.core.voice.VoiceCommandUseCase
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.domain.voice.SpeechEvent
import org.openvoyah.controls.domain.voice.VoiceInput
import org.openvoyah.controls.domain.voice.VoiceResult
import org.openvoyah.controls.presentation.voice.VoiceViewModel
import org.openvoyah.controls.presentation.voice.VoiceViewModel.Phase

class VoiceViewModelTest : AndroidTestCase() {
    private class Input : VoiceInput {
        val events = MutableSharedFlow<SpeechEvent>()
        var captures = 0
        override fun listen(language: String, grammar: Set<String>) = events.also { captures++ }
        override suspend fun repair(language: String) = Unit
    }

    fun testFinalSeatCommandDispatchesOnceWithoutAnAdditionalAction() = runBlocking {
        val input = Input()
        val received = mutableListOf<String>()
        model(input, received).use { model ->
            model.listen()
            model.listen()
            input.events.emit(SpeechEvent.Listening("listening"))
            assertEquals(Phase.LISTENING, model.state.value.phase)
            input.events.emit(SpeechEvent.Recognized("сиденье водителя один"))
            input.events.emit(SpeechEvent.Recognized("выключи фары"))
            assertEquals(listOf("GetFromMemoryCellOne"), received)
            assertEquals(1, input.captures)
            assertEquals(Phase.DONE, model.state.value.phase)
            assertEquals("sent", model.state.value.status)
            assertFalse(model.state.value.failed)
        }
    }

    fun testUnknownPhraseDoesNotDispatch() = runBlocking {
        val input = Input()
        val received = mutableListOf<String>()
        model(input, received).use { model ->
            model.listen()
            input.events.emit(SpeechEvent.Recognized("не выключай фары"))
            assertTrue(received.isEmpty())
            assertEquals(Phase.DONE, model.state.value.phase)
            assertEquals("unknown", model.state.value.status)
            assertTrue(model.state.value.failed)
        }
    }

    fun testClosingPopupDiscardsLateRecognition() = runBlocking {
        val input = Input()
        val received = mutableListOf<String>()
        model(input, received).use { model ->
            model.listen()
            model.close()
            input.events.emit(SpeechEvent.Recognized("включи массаж"))
            assertTrue(received.isEmpty())
        }
    }

    fun testNaturalPhraseUsesOnlyTheSeparateInterpreter() = runBlocking {
        val input = Input()
        val vehicle = mutableListOf<String>()
        val interpretations = mutableListOf<String>()
        model(input, vehicle) { phrase ->
            interpretations.add(phrase)
            VoiceResult("adjusted")
        }.use { model ->
            model.listen()
            input.events.emit(SpeechEvent.Natural("можно немного громче"))
            input.events.emit(SpeechEvent.Natural("ещё громче"))
            assertEquals(listOf("можно немного громче"), interpretations)
            assertTrue(vehicle.isEmpty())
            assertEquals("adjusted", model.state.value.status)
        }
    }

    fun testNaturalPhraseIsIgnoredWhenOnlineInterpretationIsDisabled() = runBlocking {
        val input = Input()
        val received = mutableListOf<String>()
        model(input, received).use { model ->
            model.listen()
            input.events.emit(SpeechEvent.Natural("можно немного громче"))
            assertTrue(received.isEmpty())
            assertEquals("unknown", model.state.value.status)
            assertTrue(model.state.value.failed)
        }
    }

    private fun model(
        input: Input,
        received: MutableList<String>,
        natural: (suspend (String) -> VoiceResult)? = null,
    ) = VoiceViewModel(
        VoiceSettings.catalog(), "ru", VoiceCommandUseCase({ false }, { "" }), input,
        { received.add(it.command().id()); VoiceResult("sent") },
        VoiceViewModel.Labels("preparing", "executing", "unknown", "failed", "offline"), Dispatchers.Unconfined, natural,
    )
}
