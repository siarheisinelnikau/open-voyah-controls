package org.openvoyah.controls.domain.voice

/** A spoken outcome never contains the user's transcript, target app, contact or exception text. */
enum class VoiceOutcome {
    LOCAL_REQUESTED,
    APP_ASSIGNMENT_REQUIRED,
    VEHICLE_SENT,
    VEHICLE_ACCEPTED,
    VEHICLE_CONFIRMED,
    VEHICLE_PARTIAL,
    VEHICLE_UNCONFIRMED,
    VEHICLE_BLOCKED,
    VEHICLE_APPROVAL_REQUIRED,
    PARK_REQUIRED,
    STANDSTILL_REQUIRED,
    VEHICLE_STATE_UNAVAILABLE,
    VEHICLE_UPDATE_REQUIRED,
    VEHICLE_CONNECT_REQUIRED,
    VEHICLE_SETUP_REQUIRED,
    VEHICLE_UPDATE_PAUSED,
    BUSY,
    BLOCKED,
    UNAVAILABLE,
    WEATHER_OFFLINE,
    ONLINE_UNAVAILABLE,
    NO_SPEECH,
    UNRECOGNIZED,
    AUDIO_INTERRUPTED,
}

data class VoiceResult(val message: String, val failed: Boolean = false, val outcome: VoiceOutcome = VoiceOutcome.UNAVAILABLE)

sealed interface VoiceFeedback {
    data class Readback(val phrase: String) : VoiceFeedback
    data class Outcome(val result: VoiceOutcome) : VoiceFeedback
}
