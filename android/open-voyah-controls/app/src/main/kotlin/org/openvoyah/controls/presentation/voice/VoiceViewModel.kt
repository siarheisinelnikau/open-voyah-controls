package org.openvoyah.controls.presentation.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.VoiceAudioOptions.FeedbackStyle
import org.openvoyah.controls.core.voice.VoiceCommandUseCase
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Destination
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Request
import org.openvoyah.controls.domain.voice.SpeechEvent
import org.openvoyah.controls.domain.voice.VoiceFeedback
import org.openvoyah.controls.domain.voice.VoiceInput
import org.openvoyah.controls.domain.voice.VoiceOutcome
import org.openvoyah.controls.domain.voice.VoiceResult

/** The button opens one foreground capture. Only its first final result can reach an action. */
class VoiceViewModel(
    private val catalog: VoiceCatalog,
    private val language: String,
    private val commands: VoiceCommandUseCase,
    private val input: VoiceInput,
    private val execute: suspend (Request) -> VoiceResult,
    private val labels: Labels,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val natural: (suspend (String) -> VoiceResult)? = null,
    private val feedbackStyle: FeedbackStyle = FeedbackStyle.READBACK,
) : AutoCloseable {
    /** [unknown] is shown when nothing usable was heard, [noMatch] beside a heard phrase that names no command. */
    data class Labels(
        val preparing: String, val executing: String, val unknown: String, val failed: String,
        val weatherOffline: String,
        val interpreting: String = executing,
        val noMatch: String = unknown,
    )

    enum class Phase { PREPARING, LISTENING, EXECUTING, DONE }
    data class State(
        val phase: Phase,
        val status: String,
        val phrase: String? = null,
        val failed: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(State(Phase.PREPARING, labels.preparing))
    private val pendingFeedback = Channel<VoiceFeedback>(capacity = 1)
    val state = mutableState.asStateFlow()
    val feedback = pendingFeedback.receiveAsFlow()
    private var started = false

    fun listen() {
        if (started) return
        started = true
        scope.launch {
            try {
                val final = input.listen(language, catalog.grammar()).onEach { event ->
                    if (event is SpeechEvent.Listening) mutableState.value = State(Phase.LISTENING, event.text)
                }.first { it !is SpeechEvent.Listening }
                when (final) {
                    is SpeechEvent.Recognized -> resolve(final.text)
                    is SpeechEvent.Natural -> interpret(final.text)
                    is SpeechEvent.Unmatched -> complete(noMatch(), final.text)
                    is SpeechEvent.Failed -> complete(VoiceResult(final.text, failed = true, outcome = final.reason))
                    is SpeechEvent.Listening -> error("Expected a final recognition result")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                complete(VoiceResult(labels.failed + failure.message.orEmpty(), failed = true), state.value.phrase)
            }
        }
    }

    private suspend fun resolve(text: String) {
        val phrase = VoiceCatalog.normalize(text)
        val command = catalog.match(phrase)
        if (command == null) {
            complete(noMatch(), phrase)
            return
        }
        val request = commands.prepare(command, phrase)
        if (request.destination() == Destination.OFFLINE_WEATHER) {
            complete(VoiceResult(labels.weatherOffline, failed = true, outcome = VoiceOutcome.WEATHER_OFFLINE), phrase)
            return
        }
        mutableState.value = State(Phase.EXECUTING, labels.executing, phrase)
        if (feedbackStyle == FeedbackStyle.READBACK) pendingFeedback.trySend(VoiceFeedback.Readback(phrase))
        complete(execute(request), phrase)
    }

    private suspend fun interpret(phrase: String) {
        val resolve = natural
        if (resolve == null) {
            complete(noMatch(), phrase)
            return
        }
        mutableState.value = State(Phase.EXECUTING, labels.interpreting, phrase)
        val result = resolve(phrase)
        if (feedbackStyle == FeedbackStyle.READBACK && !result.failed) pendingFeedback.trySend(VoiceFeedback.Readback(phrase))
        complete(result, phrase)
    }

    private fun noMatch() = VoiceResult(labels.noMatch, failed = true, outcome = VoiceOutcome.UNRECOGNIZED)

    private fun complete(result: VoiceResult, phrase: String? = null) {
        if (feedbackStyle == FeedbackStyle.OUTCOME && result.outcome != VoiceOutcome.AUDIO_INTERRUPTED) {
            pendingFeedback.trySend(VoiceFeedback.Outcome(result.outcome))
        }
        finish(result, phrase)
    }

    fun finish(result: VoiceResult, phrase: String? = null) {
        mutableState.value = State(Phase.DONE, result.message, phrase, result.failed)
    }

    override fun close() {
        scope.cancel()
        pendingFeedback.cancel()
    }
}
