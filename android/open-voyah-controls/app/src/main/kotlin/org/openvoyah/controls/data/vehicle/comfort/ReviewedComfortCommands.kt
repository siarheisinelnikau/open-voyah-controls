package org.openvoyah.controls.data.vehicle.comfort

import android.content.Context
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Destination
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Request
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.data.frida.FridaVoiceDispatcher
import org.openvoyah.controls.data.frida.ReviewedVoiceCommands
import org.openvoyah.controls.data.settings.VoiceSettings

internal enum class ComfortCommandAvailability { AVAILABLE, FORBIDDEN, FIRMWARE_UNREVIEWED }

/** Uses the existing guarded launcher transport and requires a command-specific review for this firmware. */
internal class ReviewedComfortCommands(context: Context) {
    private val app = context.applicationContext
    private val reviewed = ReviewedVoiceCommands(app)
    private val dispatcher = FridaVoiceDispatcher(FridaBridge(app), VoiceSettings(app), reviewed)
    private val catalog by lazy { runCatching { VoiceSettings.catalog() }.getOrNull() }

    fun available(commandId: String): ComfortCommandAvailability = when {
        !reviewed.permission(commandId).runs -> ComfortCommandAvailability.FORBIDDEN
        commandId !in reviewed.verifiedCommands() || command(commandId) == null ->
            ComfortCommandAvailability.FIRMWARE_UNREVIEWED
        else -> ComfortCommandAvailability.AVAILABLE
    }

    suspend fun dispatch(commandId: String, stillAllowed: () -> Boolean) = command(commandId)
        ?.takeIf { stillAllowed() && available(commandId) == ComfortCommandAvailability.AVAILABLE }
        ?.let { dispatcher.dispatch(Request(Destination.VEHICLE, it, "", "")) {
            stillAllowed() && available(commandId) == ComfortCommandAvailability.AVAILABLE
        } }

    private fun command(commandId: String): VoiceCatalog.Command? = catalog?.byId(commandId)
}
