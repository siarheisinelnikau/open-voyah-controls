package org.openvoyah.controls.presentation.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.VoiceCommandUseCase
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Destination
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Request
import org.openvoyah.controls.domain.voice.SpeechEvent
import org.openvoyah.controls.domain.voice.VoiceInput
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
) : AutoCloseable {
    data class Labels(
        val preparing: String, val executing: String, val unknown: String, val failed: String,
        val weatherOffline: String,
        val interpreting: String = executing,
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
    private val mutableFeedback = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val state = mutableState.asStateFlow()
    val feedback = mutableFeedback.asSharedFlow()
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
                    is SpeechEvent.Failed -> finish(VoiceResult(final.text, failed = true))
                    is SpeechEvent.Listening -> error("Expected a final recognition result")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                finish(VoiceResult(labels.failed + failure.message.orEmpty(), failed = true), state.value.phrase)
            }
        }
    }

    private suspend fun resolve(text: String) {
        val phrase = VoiceCatalog.normalize(text)
        val command = catalog.match(phrase)
        if (command == null) {
            finish(VoiceResult(labels.unknown, failed = true))
            return
        }
        val request = commands.prepare(command, phrase)
        if (request.destination() == Destination.OFFLINE_WEATHER) {
            finish(VoiceResult(labels.weatherOffline, failed = true), phrase)
            return
        }
        mutableState.value = State(Phase.EXECUTING, labels.executing, phrase)
        mutableFeedback.tryEmit(phrase)
        finish(execute(request), phrase)
    }

    private suspend fun interpret(phrase: String) {
        val resolve = natural
        if (resolve == null) {
            finish(VoiceResult(labels.unknown, failed = true))
            return
        }
        mutableState.value = State(Phase.EXECUTING, labels.interpreting, phrase)
        val result = resolve(phrase)
        if (!result.failed) mutableFeedback.tryEmit(phrase)
        finish(result, phrase)
    }

    fun finish(result: VoiceResult, phrase: String? = null) {
        mutableState.value = State(Phase.DONE, result.message, phrase, result.failed)
    }

    override fun close() {
        scope.cancel()
    }
}
