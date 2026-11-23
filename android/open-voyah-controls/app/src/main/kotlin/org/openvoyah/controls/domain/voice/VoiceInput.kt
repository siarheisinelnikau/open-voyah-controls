package org.openvoyah.controls.domain.voice

import kotlinx.coroutines.flow.Flow

sealed interface SpeechEvent {
    data class Listening(val text: String) : SpeechEvent
    data class Recognized(val text: String) : SpeechEvent
    data class Natural(val text: String) : SpeechEvent
    /** What a free local pass heard when the speech was not a confident catalog phrase. It never runs an action. */
    data class Unmatched(val text: String) : SpeechEvent
    data class Failed(val text: String, val reason: VoiceOutcome = VoiceOutcome.UNAVAILABLE) : SpeechEvent
}

interface VoiceInput {
    fun listen(language: String, grammar: Set<String>): Flow<SpeechEvent>
    suspend fun repair(language: String)
}
