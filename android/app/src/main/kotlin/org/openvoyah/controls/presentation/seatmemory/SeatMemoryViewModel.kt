package org.openvoyah.controls.presentation.seatmemory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.domain.seatmemory.SeatMemoryAction
import org.openvoyah.controls.domain.seatmemory.SeatMemoryRecall
import org.openvoyah.controls.domain.seatmemory.SeatMemorySettings
import org.openvoyah.controls.domain.seatmemory.SeatMemorySettingsStore
import org.openvoyah.controls.domain.seatmemory.SeatMemorySlot
import org.openvoyah.controls.domain.voice.VoiceResult

internal sealed interface SeatMemoryPrompt {
    class Factory(
        val slot: SeatMemorySlot,
        val action: SeatMemoryAction,
        val launchFailed: Boolean = false,
    ) : SeatMemoryPrompt
    class Recall(val slot: SeatMemorySlot) : SeatMemoryPrompt
}

internal class SeatMemoryViewModel(
    private val store: SeatMemorySettingsStore,
    private val vehicle: SeatMemoryRecall,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    scope: CoroutineScope? = null,
) : ViewModel() {
    data class State(
        val settings: SeatMemorySettings? = null,
        val approvedSlots: Set<SeatMemorySlot> = emptySet(),
        val working: Boolean = false,
        val recallPrompt: SeatMemoryPrompt.Recall? = null,
        val notice: Int? = null,
        val outcome: VoiceResult? = null,
    ) {
        val selectedSlot: SeatMemorySlot? get() = settings?.selectedSlot
    }

    private val tasks = scope ?: viewModelScope
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()

    fun refresh() {
        val before = mutable.value
        if (before.working) return
        mutable.value = before.copy(working = true, notice = null)
        tasks.launch {
            try {
                val (settings, approved) = withContext(io) { store.load() to vehicle.approvedSlots() }
                mutable.update { it.copy(settings = settings, approvedSlots = approved, working = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update {
                    it.copy(settings = it.settings ?: SeatMemorySettings(), approvedSlots = emptySet(),
                        working = false, notice = R.string.seat_memory_load_failed)
                }
            }
        }
    }

    /** Prepares a reviewed recall confirmation or returns the instructions for the factory screen. */
    fun prepare(slot: SeatMemorySlot, action: SeatMemoryAction): SeatMemoryPrompt.Factory? {
        val before = mutable.value
        val settings = before.settings ?: return null
        if (before.working || before.recallPrompt != null) return null
        val recall = if (action == SeatMemoryAction.RESTORE && slot in before.approvedSlots) SeatMemoryPrompt.Recall(slot) else null
        val selectionChanged = settings.selectedSlot != slot
        mutable.value = before.copy(working = selectionChanged, recallPrompt = recall, notice = null, outcome = null)
        if (selectionChanged) persist(R.string.seat_memory_save_failed) { store.select(slot) }
        return if (recall == null) SeatMemoryPrompt.Factory(slot, action) else null
    }

    fun setOpenFactorySeatsOnTap(enabled: Boolean) {
        val before = mutable.value
        val settings = before.settings ?: return
        if (before.working || before.recallPrompt != null || settings.openFactorySeatsOnTap == enabled) return
        mutable.value = before.copy(working = true, notice = null, outcome = null)
        persist(R.string.seat_memory_open_factory_on_tap_save_failed) { store.setOpenFactorySeatsOnTap(enabled) }
    }

    fun clearInteraction() { mutable.update { it.copy(recallPrompt = null, notice = null, outcome = null) } }

    fun dismissPrompt() { mutable.update { it.copy(recallPrompt = null) } }

    fun confirmRecall(prompt: SeatMemoryPrompt.Recall) {
        val before = mutable.value
        val slot = prompt.slot
        if (before.recallPrompt !== prompt || before.working || slot !in before.approvedSlots) return
        mutable.value = before.copy(working = true, recallPrompt = null, notice = null, outcome = null)
        tasks.launch {
            try {
                // The existing dispatcher rechecks exact command approval and fresh Park/zero speed.
                val outcome = withContext(io) { vehicle.recall(slot) }
                mutable.update { it.copy(working = false, outcome = outcome) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(working = false, notice = R.string.voice_vehicle_unknown) }
            }
        }
    }

    private fun persist(failure: Int, write: () -> SeatMemorySettings) {
        tasks.launch {
            try {
                val settings = withContext(io) { write() }
                mutable.update { it.copy(settings = settings, working = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(working = false, notice = failure) }
            }
        }
    }
}
