package org.openvoyah.controls

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.VoiceCommandUseCase
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Destination
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.data.frida.FridaVoiceDispatcher
import org.openvoyah.controls.data.frida.ReviewedVoiceCommands
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.data.voice.CloudVoiceSettings
import org.openvoyah.controls.data.voice.Interpretation
import org.openvoyah.controls.data.voice.OpenRouterVoiceInterpreter
import org.openvoyah.controls.domain.voice.VoiceResult
import org.openvoyah.controls.platform.voice.OfflineVoiceInput
import org.openvoyah.controls.platform.voice.VoiceUiActions
import org.openvoyah.controls.presentation.voice.VoiceViewModel

internal object VoiceScreenFactory {
    fun create(
        activity: Activity, catalog: VoiceCatalog, settings: VoiceSettings, language: String,
        duckMedia: Boolean,
    ): VoiceViewModel {
        val application = activity.applicationContext
        val android = VoiceUiActions(activity, settings::app, split = {
            ButtonAccessibilityService.connected()
                ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) == true
        }, events = UiText.displayEvents(activity))
        val commands = VoiceCommandUseCase(android::supports, settings::phone)
        val vehicle = FridaVoiceDispatcher(FridaBridge(application), settings, ReviewedVoiceCommands(application))
        val labels = VoiceViewModel.Labels(
            UiText.get(R.string.ui_139), UiText.get(R.string.voice_executing), UiText.get(R.string.unknown_command),
            UiText.get(R.string.ui_205), UiText.get(R.string.weather_offline), UiText.get(R.string.voice_online_checking),
        )
        val cloudSettings = CloudVoiceSettings(application)
        val enabled = cloudSettings.configured()
        val interpreter = OpenRouterVoiceInterpreter(cloudSettings::key)
        val natural: (suspend (String) -> VoiceResult)? = if (enabled) { phrase ->
            when (val result = interpreter.interpret(phrase)) {
                is Interpretation.Command -> android.runNatural(result.action)
                Interpretation.Unknown -> VoiceResult(labels.unknown, failed = true)
                Interpretation.Unavailable -> VoiceResult(UiText.get(R.string.voice_online_unavailable), failed = true)
            }
        } else null
        return VoiceViewModel(catalog, language, commands, OfflineVoiceInput(application, duckMedia, enabled), { request ->
            when (request.destination()) {
                Destination.LOCAL -> android.runLocal(request.command().id(), request.phrase())
                Destination.DIAL -> android.dial(request.phone())
                Destination.VEHICLE -> vehicle.dispatch(request)
                Destination.OFFLINE_WEATHER -> VoiceResult(labels.weatherOffline, failed = true)
            }
        }, labels, natural = natural)
    }
}
