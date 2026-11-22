package org.openvoyah.controls

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.VoiceAudioOptions.FeedbackStyle
import org.openvoyah.controls.core.voice.VoiceAudioOptions.ListeningWindow
import org.openvoyah.controls.core.voice.VoiceCommandUseCase
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Destination
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Request
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.data.frida.FridaVoiceDispatcher
import org.openvoyah.controls.data.frida.ReviewedVoiceCommands
import org.openvoyah.controls.data.settings.SettingsStore
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.data.voice.CloudVoiceSettings
import org.openvoyah.controls.data.voice.Interpretation
import org.openvoyah.controls.data.voice.OpenRouterVoiceInterpreter
import org.openvoyah.controls.domain.seatmemory.SeatMemorySlot
import org.openvoyah.controls.domain.voice.VoicePanelApps
import org.openvoyah.controls.domain.voice.VoiceOutcome
import org.openvoyah.controls.domain.voice.VoiceResult
import org.openvoyah.controls.platform.voice.OfflineVoiceInput
import org.openvoyah.controls.platform.voice.VoiceUiActions
import org.openvoyah.controls.presentation.voice.VoiceViewModel

internal object VoiceScreenFactory {
    fun create(
        activity: Activity, catalog: VoiceCatalog, settings: VoiceSettings, language: String,
        duckMedia: Boolean,
        readyCue: (suspend () -> Unit)? = null,
        endCue: (suspend () -> Unit)? = null,
        feedbackStyle: FeedbackStyle = FeedbackStyle.READBACK,
        listeningWindow: ListeningWindow = ListeningWindow.FOUR_SECONDS,
    ): VoiceViewModel {
        val application = activity.applicationContext
        // Without an app chosen in Voice settings, music, navigation and radio open the app of their panel button.
        val panelActions = { runCatching { SettingsStore(application).load().panelActions.toMap() }.getOrDefault(emptyMap()) }
        val android = VoiceUiActions(activity, { id -> VoicePanelApps.resolve(id, settings.app(id), panelActions) }, split = {
            ButtonAccessibilityService.connected()
                ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) == true
        }, events = UiText.displayEvents(activity))
        val commands = VoiceCommandUseCase(android::supports, settings::phone)
        val reviewed = ReviewedVoiceCommands(application)
        val vehicle = FridaVoiceDispatcher(FridaBridge(application), settings, reviewed)
        val labels = VoiceViewModel.Labels(
            UiText.get(R.string.ui_139), UiText.get(R.string.voice_executing), UiText.get(R.string.unknown_command),
            UiText.get(R.string.ui_205), UiText.get(R.string.weather_offline), UiText.get(R.string.voice_online_checking),
            UiText.get(R.string.voice_no_matching_command),
        )
        val cloudSettings = CloudVoiceSettings(application)
        val enabled = cloudSettings.configured()
        val interpreter = OpenRouterVoiceInterpreter(cloudSettings::key)
        val natural: (suspend (String) -> VoiceResult)? = if (enabled) { phrase ->
            when (val result = interpreter.interpret(phrase)) {
                is Interpretation.Command -> android.runNatural(result.action)
                Interpretation.Unknown -> VoiceResult(labels.noMatch, failed = true, outcome = VoiceOutcome.UNRECOGNIZED)
                Interpretation.Unavailable -> VoiceResult(UiText.get(R.string.voice_online_unavailable), failed = true,
                    outcome = VoiceOutcome.ONLINE_UNAVAILABLE)
            }
        } else null
        val input = OfflineVoiceInput(application, duckMedia, enabled, readyCue, endCue, listeningWindow)
        return VoiceViewModel(catalog, language, commands, input, { request ->
            when (request.destination()) {
                Destination.LOCAL -> android.runLocal(request.command().id(), request.phrase())
                Destination.DIAL -> android.dial(request.phone())
                Destination.VEHICLE -> routeVehicleVoice(
                    request,
                    recallApproved = { id -> withContext(Dispatchers.IO) { reviewed.profileFor(id) != null } },
                    dispatch = vehicle::dispatch,
                    openDriverMemory = android::openDriverMemory,
                )
                Destination.OFFLINE_WEATHER -> VoiceResult(labels.weatherOffline, failed = true, outcome = VoiceOutcome.WEATHER_OFFLINE)
            }
        }, labels, natural = natural, feedbackStyle = feedbackStyle)
    }
}

/** For an unreviewed numbered recall, only open the on-screen factory instructions. */
internal suspend fun routeVehicleVoice(
    request: Request,
    recallApproved: suspend (String) -> Boolean,
    dispatch: suspend (Request) -> VoiceResult,
    openDriverMemory: (SeatMemorySlot) -> VoiceResult,
): VoiceResult {
    val slot = if (request.destination() == Destination.VEHICLE) {
        SeatMemorySlot.forRecallCommand(request.command().id())
    } else null
    return if (slot != null && !recallApproved(slot.recallCommandId)) openDriverMemory(slot) else dispatch(request)
}
