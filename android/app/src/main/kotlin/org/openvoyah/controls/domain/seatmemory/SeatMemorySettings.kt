package org.openvoyah.controls.domain.seatmemory

import org.openvoyah.controls.domain.voice.VoiceResult

internal enum class SeatMemoryAction { SAVE, RESTORE }

internal enum class SeatMemorySlot(val number: Int, val recallCommandId: String) {
    ONE(1, "GetFromMemoryCellOne"),
    TWO(2, "GetFromMemoryCellTwo"),
    THREE(3, "GetFromMemoryCellThree");

    companion object {
        fun forNumber(number: Int): SeatMemorySlot? = entries.firstOrNull { it.number == number }

        fun forRecallCommand(id: String): SeatMemorySlot? = entries.firstOrNull { it.recallCommandId == id }
    }
}

/** App-local driver selection and preferences. The vehicle owns the physical positions. */
internal data class SeatMemorySettings(
    val selectedSlot: SeatMemorySlot = SeatMemorySlot.ONE,
    val slotNames: Map<SeatMemorySlot, String> = emptyMap(),
    val openFactorySeatsOnTap: Boolean = false,
) {
    init {
        require(slotNames.values.all { validName(it) && it == it.trim() && it.isNotEmpty() })
    }

    fun rename(slot: SeatMemorySlot, name: String): SeatMemorySettings {
        require(validName(name))
        val clean = name.trim()
        return copy(slotNames = if (clean.isEmpty()) slotNames - slot else slotNames + (slot to clean))
    }

    companion object {
        const val MAX_NAME_LENGTH = 32

        fun validName(value: String): Boolean =
            value.length <= MAX_NAME_LENGTH && value.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }
    }
}

internal interface SeatMemorySettingsStore {
    fun load(): SeatMemorySettings
    fun select(slot: SeatMemorySlot): SeatMemorySettings
    fun rename(slot: SeatMemorySlot, name: String): SeatMemorySettings
    fun setOpenFactorySeatsOnTap(enabled: Boolean): SeatMemorySettings
}

internal interface SeatMemoryRecall {
    fun approvedSlots(): Set<SeatMemorySlot>
    suspend fun recall(slot: SeatMemorySlot): VoiceResult
}
