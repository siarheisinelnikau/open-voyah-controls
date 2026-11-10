package org.openvoyah.controls.domain.voice

import kotlinx.coroutines.flow.Flow

sealed interface SpeechEvent {
    data class Listening(val text: String) : SpeechEvent
    data class Recognized(val text: String) : SpeechEvent
    data class Natural(val text: String) : SpeechEvent
    data class Failed(val text: String) : SpeechEvent
}

interface VoiceInput {
    fun listen(language: String, grammar: Set<String>): Flow<SpeechEvent>
    suspend fun repair(language: String)
}

data class VoiceResult(val message: String, val failed: Boolean = false)
