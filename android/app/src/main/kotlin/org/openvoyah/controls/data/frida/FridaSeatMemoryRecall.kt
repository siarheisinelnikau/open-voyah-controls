package org.openvoyah.controls.data.frida

import android.content.Context
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Destination
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Request
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.domain.seatmemory.SeatMemoryRecall
import org.openvoyah.controls.domain.seatmemory.SeatMemorySlot
import org.openvoyah.controls.domain.voice.VoiceResult

/** Routes only the three existing, individually reviewed recall commands. */
internal class FridaSeatMemoryRecall(context: Context) : SeatMemoryRecall {
    private val app = context.applicationContext
    private val reviewed = ReviewedVoiceCommands(app)
    private val dispatcher = FridaVoiceDispatcher(FridaBridge(app), VoiceSettings(app), reviewed)
    private val catalog by lazy { VoiceSettings.catalog() }

    override fun approvedSlots(): Set<SeatMemorySlot> =
        SeatMemorySlot.entries.filterTo(mutableSetOf()) { reviewed.profileFor(it.recallCommandId) != null }

    override suspend fun recall(slot: SeatMemorySlot): VoiceResult {
        val id = slot.recallCommandId
        if (reviewed.isSwitchedOff(id)) return VoiceResult(UiText.get(R.string.voice_vehicle_switched_off), failed = true)
        if (reviewed.profileFor(id) == null) return VoiceResult(UiText.get(R.string.voice_vehicle_approval_required), failed = true)
        val command = catalog.byId(id) ?: return VoiceResult(UiText.get(R.string.voice_vehicle_blocked), failed = true)
        return dispatcher.dispatch(Request(Destination.VEHICLE, command, "", ""))
    }
}
