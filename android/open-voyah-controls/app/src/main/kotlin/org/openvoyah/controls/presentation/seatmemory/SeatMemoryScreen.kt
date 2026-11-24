package org.openvoyah.controls.presentation.seatmemory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.seatmemory.SeatMemoryAction
import org.openvoyah.controls.domain.seatmemory.SeatMemorySlot
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitReveal
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction

@Composable
internal fun SeatMemoryScreen(
    state: SeatMemoryViewModel.State,
    factoryGuide: SeatMemoryPrompt.Factory?,
    active: Boolean,
    factoryLabel: Int?,
    back: () -> Unit,
    refresh: () -> Unit,
    action: (SeatMemorySlot, SeatMemoryAction) -> Unit,
    setOpenFactorySeatsOnTap: (Boolean) -> Unit,
    dismissPrompt: () -> Unit,
    confirmRecall: (SeatMemoryPrompt.Recall) -> Unit,
    openFactory: (SeatMemoryPrompt.Factory) -> Unit,
) {
    val prompt = factoryGuide ?: state.recallPrompt
    val enabled = active && state.selectedSlot != null && !state.working && prompt == null
    CockpitDetail(text(R.string.seat_memory_title), text(R.string.seat_memory_description), back, horizontalPadding = 14.dp) {
        DriverMemoryCards(enabled, action)
        Column {
            CockpitSwitch(
                R.string.seat_memory_open_factory_on_tap, state.settings?.openFactorySeatsOnTap == true,
                enabled = enabled, change = setOpenFactorySeatsOnTap,
            )
            Text(
                text(R.string.seat_memory_open_factory_on_tap_hint),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val feedback = when {
            factoryGuide != null -> null
            state.notice != null -> text(state.notice)
            state.working && prompt == null -> text(R.string.seat_memory_working)
            else -> state.outcome?.message
        }
        // A hidden reveal emits nothing, so an empty message leaves no extra gap in the spaced page column.
        CockpitReveal(feedback != null) {
            CockpitFeedback(feedback, error = state.notice != null || state.outcome?.failed == true)
        }
        if (state.notice == R.string.seat_memory_load_failed) {
            CockpitAction(text(R.string.seat_memory_retry), refresh, enabled = active && !state.working)
        }
    }

    if (active) {
        when (prompt) {
            is SeatMemoryPrompt.Factory -> FactoryMemoryDialog(prompt, factoryLabel, !state.working, dismissPrompt, openFactory)
            is SeatMemoryPrompt.Recall -> CockpitAlertDialog(
                onDismissRequest = dismissPrompt,
                title = { Text(seatMemoryText(R.string.seat_memory_recall_title, driverTitle(prompt.slot))) },
                text = { Text(text(R.string.seat_memory_confirm)) },
                dismissButton = { CockpitTextAction(text(R.string.seat_memory_cancel), dismissPrompt) },
                confirmButton = {
                    CockpitAction(text(R.string.seat_memory_recall), { confirmRecall(prompt) },
                        enabled = !state.working, primary = true)
                },
            )
            null -> Unit
        }
    }
}

@Composable
private fun FactoryMemoryDialog(
    prompt: SeatMemoryPrompt.Factory,
    factoryLabel: Int?,
    enabled: Boolean,
    dismiss: () -> Unit,
    openFactory: (SeatMemoryPrompt.Factory) -> Unit,
) {
    val save = prompt.action == SeatMemoryAction.SAVE
    val title = seatMemoryText(
        if (save) R.string.seat_memory_save_driver else R.string.seat_memory_restore_driver, driverTitle(prompt.slot),
    )
    val message = seatMemoryText(
        if (save) R.string.seat_memory_factory_save else R.string.seat_memory_factory_restore, prompt.slot.number,
    )
    val openLabel = factoryLabel?.takeUnless { prompt.launchFailed }
    CockpitAlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (openLabel != R.string.seat_memory_seats_open) {
                    Text(text(R.string.seat_memory_factory_path))
                }
                Text(message)
                if (prompt.launchFailed) CockpitFeedback(text(R.string.seat_memory_open_failed), error = true)
            }
        },
        dismissButton = if (openLabel != null) ({ CockpitTextAction(text(R.string.seat_memory_cancel), dismiss) }) else null,
        confirmButton = {
            if (openLabel != null) {
                CockpitAction(text(openLabel), { openFactory(prompt) }, enabled = enabled, primary = true)
            } else {
                CockpitAction(text(R.string.seat_memory_close), dismiss, primary = true)
            }
        },
    )
}

internal fun driverTitle(slot: SeatMemorySlot): String = seatMemoryText(R.string.seat_memory_slot, slot.number)
internal fun seatMemoryText(id: Int, argument: Any): String = text(id).format(Locale.ROOT, argument)
private fun text(id: Int): String = UiText.get(id)
