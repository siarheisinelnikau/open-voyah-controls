package org.openvoyah.controls.presentation.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import org.openvoyah.controls.domain.voice.SequenceCommandPolicy
import org.openvoyah.controls.domain.voice.VoiceSequence
import org.openvoyah.controls.domain.voice.sequenceCommandPolicy
import org.openvoyah.controls.domain.voice.sequenceIsUncertain
import org.openvoyah.controls.core.voice.ClimatePhrases
import org.openvoyah.controls.core.voice.VolumePhrases

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
    private val sequenceLabels: SequenceLabels? = null,
    private val custom: Map<String, suspend () -> VoiceResult> = emptyMap(),
    private val executeSequenceStep: suspend (Request) -> VoiceResult = execute,
) : AutoCloseable {
    /** [unknown] is shown when nothing usable was heard, [noMatch] beside a heard phrase that names no command. */
    data class Labels(
        val preparing: String, val executing: String, val unknown: String, val failed: String,
        val weatherOffline: String,
        val interpreting: String = executing,
        val noMatch: String = unknown,
    )
    data class SequenceLabels(
        val sending: String, val finished: String, val duplicate: String, val singleOnly: String,
        val lastOnly: String, val stopped: String, val tooMany: String, val timedOut: String,
    )

    data class SequenceLine(val phrase: String, val result: String, val failed: Boolean = false)

    enum class Phase { PREPARING, LISTENING, EXECUTING, DONE }
    data class State(
        val phase: Phase,
        val status: String,
        val phrase: String? = null,
        val failed: Boolean = false,
        val steps: List<SequenceLine> = emptyList(),
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(State(Phase.PREPARING, labels.preparing))
    private val pendingFeedback = Channel<VoiceFeedback>(capacity = 1)
    val state = mutableState.asStateFlow()
    val feedback = pendingFeedback.receiveAsFlow()
    private var started = false
    private val sequence = VoiceSequence(language) { catalog.match(it) != null || it in custom }

    fun listen() {
        if (started) return
        started = true
        scope.launch {
            try {
                val grammar = catalog.grammar() + custom.keys + (sequenceLabels?.let { sequence.separators } ?: emptySet())
                val final = input.listen(language, grammar).onEach { event ->
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
            val launch = custom[phrase]
            val parts = sequenceLabels?.let { sequence.split(phrase) }
            when {
                launch != null -> {
                    begin(phrase)
                    complete(launch(), phrase)
                }
                parts != null -> resolveSequence(phrase, parts, requireNotNull(sequenceLabels))
                else -> complete(noMatch(), phrase)
            }
            return
        }
        val request = commands.prepare(command, phrase)
        if (request.destination() == Destination.OFFLINE_WEATHER) {
            complete(VoiceResult(labels.weatherOffline, failed = true, outcome = VoiceOutcome.WEATHER_OFFLINE), phrase)
            return
        }
        begin(phrase)
        complete(execute(request), phrase)
    }

    private fun begin(phrase: String) {
        mutableState.value = State(Phase.EXECUTING, labels.executing, phrase)
        if (feedbackStyle == FeedbackStyle.READBACK) pendingFeedback.trySend(VoiceFeedback.Readback(phrase))
    }

    private suspend fun resolveSequence(phrase: String, parts: List<String>, messages: SequenceLabels) {
        begin(phrase)
        val steps = parts.take(VoiceSequence.LIMIT)
        val rows = mutableListOf<SequenceLine>()
        val attempted = mutableSetOf<String>()
        var stopped = false
        var lastSuccess: VoiceOutcome? = null
        var uncertain: VoiceOutcome? = null
        val completed = withTimeoutOrNull(20_000) {
            steps.forEachIndexed { index, segment ->
                currentCoroutineContext().ensureActive()
                mutableState.value = State(Phase.EXECUTING, messages.sending, phrase,
                    steps = rows + SequenceLine(segment, messages.sending))
                val command = catalog.match(segment)
                val launch = custom[segment]
                val policy = command?.id()?.let(::sequenceCommandPolicy) ?: SequenceCommandPolicy.LAST_ONLY
                val last = index == parts.lastIndex
                val prepared = command?.id()?.let { id -> when {
                    ClimatePhrases.supports(id) -> "$id:${ClimatePhrases.value(id, segment)}"
                    id == VolumePhrases.COMMAND_ID -> "$id:${VolumePhrases.percentage(segment)}"
                    else -> id
                } } ?: segment
                val result = when {
                    stopped -> VoiceResult(messages.stopped, failed = true)
                    command == null && launch == null -> noMatch()
                    policy == SequenceCommandPolicy.SINGLE_ONLY -> VoiceResult(messages.singleOnly, failed = true)
                    policy == SequenceCommandPolicy.LAST_ONLY && !last -> VoiceResult(messages.lastOnly, failed = true)
                    !attempted.add(prepared) -> VoiceResult(messages.duplicate, failed = true)
                    else -> {
                        val response = try {
                            if (command != null) executeSequenceStep(commands.prepare(command, segment))
                            else checkNotNull(launch).invoke()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            VoiceResult(messages.timedOut, failed = true)
                        }
                        if (response.outcome.sequenceIsUncertain() || response.outcome == VoiceOutcome.AUDIO_INTERRUPTED) {
                            stopped = true
                            uncertain = response.outcome
                        }
                        response
                    }
                }
                rows += SequenceLine(segment, result.message, result.failed)
                if (!result.failed) lastSuccess = result.outcome
            }
            true
        } ?: false
        if (!completed) {
            val interruptedAt = rows.size
            steps.drop(interruptedAt).forEachIndexed { index, segment ->
                rows += SequenceLine(segment, if (index == 0) messages.timedOut else messages.stopped, failed = true)
            }
            uncertain = VoiceOutcome.VEHICLE_UNCONFIRMED
        }
        if (parts.size > VoiceSequence.LIMIT) rows += SequenceLine("", messages.tooMany, failed = true)
        val outcome = uncertain ?: lastSuccess ?: VoiceOutcome.UNRECOGNIZED
        if (feedbackStyle == FeedbackStyle.OUTCOME && outcome != VoiceOutcome.AUDIO_INTERRUPTED)
            pendingFeedback.trySend(VoiceFeedback.Outcome(outcome))
        mutableState.value = State(Phase.DONE, messages.finished, phrase, failed = rows.any { it.failed }, steps = rows.toList())
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
