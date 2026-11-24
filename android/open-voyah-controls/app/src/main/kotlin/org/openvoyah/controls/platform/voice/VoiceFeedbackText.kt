package org.openvoyah.controls.platform.voice

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import org.openvoyah.controls.R
import org.openvoyah.controls.domain.voice.VoiceFeedback
import org.openvoyah.controls.domain.voice.VoiceOutcome

internal fun Context.forRecognitionLanguage(language: String): Context = createConfigurationContext(
    Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) },
)

internal fun VoiceFeedback.speechText(recognitionContext: Context): String = when (this) {
    is VoiceFeedback.Readback -> phrase
    is VoiceFeedback.Outcome -> recognitionContext.getString(when (result) {
        VoiceOutcome.LOCAL_REQUESTED -> R.string.voice_outcome_local_requested
        VoiceOutcome.APP_ASSIGNMENT_REQUIRED -> R.string.voice_outcome_assign_app
        VoiceOutcome.VEHICLE_SENT -> R.string.voice_outcome_vehicle_sent
        VoiceOutcome.VEHICLE_ACCEPTED -> R.string.voice_vehicle_accepted
        VoiceOutcome.VEHICLE_CONFIRMED -> R.string.voice_vehicle_verified
        VoiceOutcome.VEHICLE_PARTIAL -> R.string.voice_vehicle_partial
        VoiceOutcome.VEHICLE_UNCONFIRMED -> R.string.voice_vehicle_unknown
        VoiceOutcome.VEHICLE_BLOCKED -> R.string.voice_vehicle_blocked
        VoiceOutcome.VEHICLE_APPROVAL_REQUIRED -> R.string.voice_vehicle_approval_required
        VoiceOutcome.PARK_REQUIRED -> R.string.voice_requires_park
        VoiceOutcome.STANDSTILL_REQUIRED -> R.string.voice_requires_stationary
        VoiceOutcome.VEHICLE_STATE_UNAVAILABLE -> R.string.voice_vehicle_state_unavailable
        VoiceOutcome.DISPLAY_TOO_HOT -> R.string.voice_brightness_display_hot
        VoiceOutcome.VEHICLE_UPDATE_REQUIRED -> R.string.voice_refresh_bridge
        VoiceOutcome.VEHICLE_CONNECT_REQUIRED -> R.string.ui_19
        VoiceOutcome.VEHICLE_SETUP_REQUIRED -> R.string.production_broker_unavailable
        VoiceOutcome.VEHICLE_UPDATE_PAUSED -> R.string.runtime_update_paused
        VoiceOutcome.BUSY -> R.string.ui_199
        VoiceOutcome.BLOCKED -> R.string.voice_outcome_blocked
        VoiceOutcome.UNAVAILABLE -> R.string.voice_outcome_unavailable
        VoiceOutcome.WEATHER_OFFLINE -> R.string.weather_offline
        VoiceOutcome.ONLINE_UNAVAILABLE -> R.string.voice_online_unavailable
        VoiceOutcome.NO_SPEECH -> R.string.voice_no_speech
        VoiceOutcome.UNRECOGNIZED -> R.string.unknown_command
        VoiceOutcome.AUDIO_INTERRUPTED -> R.string.voice_audio_interrupted
    })
}
