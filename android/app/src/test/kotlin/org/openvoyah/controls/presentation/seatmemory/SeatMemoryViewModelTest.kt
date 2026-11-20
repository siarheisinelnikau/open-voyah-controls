package org.openvoyah.controls.presentation.seatmemory

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.R
import org.openvoyah.controls.domain.seatmemory.SeatMemoryAction
import org.openvoyah.controls.domain.seatmemory.SeatMemoryRecall
import org.openvoyah.controls.domain.seatmemory.SeatMemorySettings
import org.openvoyah.controls.domain.seatmemory.SeatMemorySettingsStore
import org.openvoyah.controls.domain.seatmemory.SeatMemorySlot
import org.openvoyah.controls.domain.voice.VoiceResult

class SeatMemoryViewModelTest {
    private class Store : SeatMemorySettingsStore {
        private var data = SeatMemorySettings()
        var failSelect = false
        var failShortcutPreference = false
        override fun load() = data
        override fun select(slot: SeatMemorySlot): SeatMemorySettings {
            check(!failSelect)
            data = data.copy(selectedSlot = slot)
            return data
        }
        override fun rename(slot: SeatMemorySlot, name: String): SeatMemorySettings {
            data = data.rename(slot, name)
            return data
        }
        override fun setOpenFactorySeatsOnTap(enabled: Boolean): SeatMemorySettings {
            check(!failShortcutPreference)
            data = data.copy(openFactorySeatsOnTap = enabled)
            return data
        }
    }

    private class Vehicle(private val approved: Set<SeatMemorySlot>) : SeatMemoryRecall {
        val entered = CompletableDeferred<SeatMemorySlot>()
        val reply = CompletableDeferred<VoiceResult>()
        val requests = mutableListOf<SeatMemorySlot>()

        override fun approvedSlots() = approved
        override suspend fun recall(slot: SeatMemorySlot): VoiceResult {
            requests += slot
            entered.complete(slot)
            return reply.await()
        }
    }

    private suspend fun SeatMemoryViewModel.selected(): SeatMemorySlot = withTimeout(3_000) {
        requireNotNull(state.first { !it.working && it.selectedSlot != null }.selectedSlot)
    }

    private fun model(scope: CoroutineScope, store: Store, vehicle: Vehicle) =
        SeatMemoryViewModel(store, vehicle, io = Dispatchers.Unconfined, scope = scope)

    @Test fun savePreparesFactoryControlsForTheTappedDriverAndRemembersTheNumber() = runBlocking {
        val store = Store()
        val vehicle = Vehicle(setOf(SeatMemorySlot.TWO))
        val model = model(this, store, vehicle)
        model.refresh()
        model.selected()
        store.rename(SeatMemorySlot.TWO, "Weekend")
        val guide = requireNotNull(model.prepare(SeatMemorySlot.TWO, SeatMemoryAction.SAVE))
        assertEquals(SeatMemorySlot.TWO, guide.slot)
        assertEquals(SeatMemoryAction.SAVE, guide.action)
        assertFalse(guide.launchFailed)
        assertEquals(SeatMemorySlot.TWO, model.selected())
        assertNull(model.state.value.recallPrompt)

        val reopened = model(this, store, vehicle)
        reopened.refresh()
        assertEquals(SeatMemorySlot.TWO, reopened.selected())
        assertEquals("Weekend", store.load().slotNames[SeatMemorySlot.TWO])
        assertEquals(emptyList<SeatMemorySlot>(), vehicle.requests)
    }

    @Test fun unapprovedAndDismissedRestoresNeverDispatch() = runBlocking {
        val vehicle = Vehicle(setOf(SeatMemorySlot.TWO))
        val model = model(this, Store(), vehicle)
        model.refresh()
        model.selected()
        val guide = requireNotNull(model.prepare(SeatMemorySlot.ONE, SeatMemoryAction.RESTORE))
        assertEquals(SeatMemorySlot.ONE, guide.slot)
        assertEquals(SeatMemoryAction.RESTORE, guide.action)
        assertNull(model.state.value.recallPrompt)
        model.confirmRecall(SeatMemoryPrompt.Recall(SeatMemorySlot.ONE))

        assertNull(model.prepare(SeatMemorySlot.TWO, SeatMemoryAction.RESTORE))
        assertEquals(SeatMemorySlot.TWO, model.selected())
        val prompt = requireNotNull(model.state.value.recallPrompt)
        assertEquals(SeatMemorySlot.TWO, prompt.slot)
        model.confirmRecall(SeatMemoryPrompt.Recall(SeatMemorySlot.THREE))
        model.dismissPrompt()
        model.confirmRecall(prompt)
        assertNull(model.state.value.recallPrompt)
        assertEquals(emptyList<SeatMemorySlot>(), vehicle.requests)
    }

    @Test fun failedSelectionKeepsThePriorHighlightAndTheExplicitRestorePrompt() = runBlocking {
        val store = Store()
        val vehicle = Vehicle(setOf(SeatMemorySlot.TWO))
        val model = model(this, store, vehicle)
        model.refresh()
        model.selected()
        store.failSelect = true
        model.prepare(SeatMemorySlot.TWO, SeatMemoryAction.RESTORE)
        assertEquals(SeatMemorySlot.ONE, model.selected())
        assertEquals(R.string.seat_memory_save_failed, model.state.value.notice)
        val prompt = requireNotNull(model.state.value.recallPrompt)
        assertEquals(SeatMemorySlot.TWO, prompt.slot)
        assertEquals(emptyList<SeatMemorySlot>(), vehicle.requests)
        model.confirmRecall(prompt)
        assertEquals(SeatMemorySlot.TWO, withTimeout(3_000) { vehicle.entered.await() })
        vehicle.reply.complete(VoiceResult("Restore accepted"))
        model.selected()
        assertEquals(listOf(SeatMemorySlot.TWO), vehicle.requests)
    }

    @Test fun aVoiceGuideClearsThePriorManualConfirmationWithoutDispatching() = runBlocking {
        val store = Store().apply { setOpenFactorySeatsOnTap(true) }
        val vehicle = Vehicle(setOf(SeatMemorySlot.THREE))
        val model = model(this, store, vehicle)
        model.refresh()
        model.selected()
        model.prepare(SeatMemorySlot.THREE, SeatMemoryAction.RESTORE)
        model.selected()
        val prompt = requireNotNull(model.state.value.recallPrompt)
        assertEquals(SeatMemorySlot.THREE, prompt.slot)
        model.clearInteraction()
        model.confirmRecall(prompt)
        assertNull(model.state.value.recallPrompt)
        assertEquals(emptyList<SeatMemorySlot>(), vehicle.requests)
    }

    @Test fun onlyTheCurrentConfirmationCanRestoreAReopenedSlot() = runBlocking {
        val vehicle = Vehicle(setOf(SeatMemorySlot.ONE))
        val model = model(this, Store(), vehicle)
        model.refresh()
        model.selected()
        model.prepare(SeatMemorySlot.ONE, SeatMemoryAction.RESTORE)
        val dismissed = requireNotNull(model.state.value.recallPrompt)
        model.dismissPrompt()
        model.prepare(SeatMemorySlot.ONE, SeatMemoryAction.RESTORE)
        val current = requireNotNull(model.state.value.recallPrompt)
        assertEquals(dismissed.slot, current.slot)
        assertFalse(dismissed == current)

        model.confirmRecall(dismissed)
        assertSame(current, model.state.value.recallPrompt)
        assertTrue(vehicle.requests.isEmpty())

        model.confirmRecall(current)
        assertEquals(SeatMemorySlot.ONE, withTimeout(3_000) { vehicle.entered.await() })
        vehicle.reply.complete(VoiceResult("Restore accepted"))
        model.selected()
        assertEquals(listOf(SeatMemorySlot.ONE), vehicle.requests)
    }

    @Test fun factoryShortcutPreferenceKeepsConfirmationForApprovedRecall() = runBlocking {
        val store = Store().apply { setOpenFactorySeatsOnTap(true) }
        val vehicle = Vehicle(setOf(SeatMemorySlot.TWO))
        val model = model(this, store, vehicle)
        model.refresh()
        model.selected()

        val guide = requireNotNull(model.prepare(SeatMemorySlot.ONE, SeatMemoryAction.RESTORE))
        assertEquals(SeatMemorySlot.ONE, guide.slot)
        assertEquals(SeatMemoryAction.RESTORE, guide.action)
        assertNull(model.state.value.recallPrompt)
        assertNull(model.prepare(SeatMemorySlot.TWO, SeatMemoryAction.RESTORE))
        model.selected()
        assertEquals(SeatMemorySlot.TWO, model.state.value.recallPrompt?.slot)
        assertTrue(vehicle.requests.isEmpty())
    }

    @Test fun shortcutPreferenceReloadsAndAFailedWriteLeavesItsLastSavedValue() = runBlocking {
        val store = Store()
        val vehicle = Vehicle(emptySet())
        val model = model(this, store, vehicle)
        model.refresh()
        model.selected()
        model.setOpenFactorySeatsOnTap(true)
        model.selected()
        assertTrue(model.state.value.settings?.openFactorySeatsOnTap == true)

        val reopened = model(this, store, vehicle)
        reopened.refresh()
        reopened.selected()
        assertTrue(reopened.state.value.settings?.openFactorySeatsOnTap == true)
        store.failShortcutPreference = true
        reopened.setOpenFactorySeatsOnTap(false)
        reopened.selected()
        assertEquals(R.string.seat_memory_open_factory_on_tap_save_failed, reopened.state.value.notice)
        assertTrue(reopened.state.value.settings?.openFactorySeatsOnTap == true)

        store.failShortcutPreference = false
        reopened.setOpenFactorySeatsOnTap(false)
        reopened.selected()
        assertNull(reopened.state.value.notice)
        assertFalse(store.load().openFactorySeatsOnTap)
    }

    @Test fun aConfirmedRequestRunsOnceAndShowsTheUnknownOutcome() = runBlocking {
        val vehicle = Vehicle(setOf(SeatMemorySlot.ONE))
        val model = model(this, Store(), vehicle)
        model.refresh()
        model.selected()
        model.prepare(SeatMemorySlot.ONE, SeatMemoryAction.RESTORE)
        val prompt = requireNotNull(model.state.value.recallPrompt)
        model.confirmRecall(prompt)
        assertEquals(SeatMemorySlot.ONE, withTimeout(3_000) { vehicle.entered.await() })
        assertNull(model.prepare(SeatMemorySlot.ONE, SeatMemoryAction.SAVE))
        model.confirmRecall(prompt)
        val outcome = VoiceResult("Outcome unknown", failed = true)
        vehicle.reply.complete(outcome)
        model.selected()
        model.confirmRecall(prompt)
        assertEquals(outcome, model.state.value.outcome)
        assertNull(model.state.value.recallPrompt)
        assertEquals(listOf(SeatMemorySlot.ONE), vehicle.requests)
    }
}
