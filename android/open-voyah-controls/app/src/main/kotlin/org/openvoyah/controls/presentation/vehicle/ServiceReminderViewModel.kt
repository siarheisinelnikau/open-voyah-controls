package org.openvoyah.controls.presentation.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openvoyah.controls.domain.vehicle.ServiceCalendarLead
import org.openvoyah.controls.domain.vehicle.ServiceCalendarSchedule
import org.openvoyah.controls.domain.vehicle.ServiceReminderLead
import org.openvoyah.controls.domain.vehicle.ServiceReminderSettings
import org.openvoyah.controls.domain.vehicle.ServiceReminderSettingsStore
import org.openvoyah.controls.domain.vehicle.ServiceSchedule

internal class ServiceReminderViewModel(private val store: ServiceReminderSettingsStore, dates: Flow<LocalDate>) : ViewModel() {
    enum class SaveStatus { IDLE, SAVING, FAILED }

    data class State(val settings: ServiceReminderSettings, val today: LocalDate) {
        val progress = settings.progress(today)
    }

    val state: StateFlow<State?> = combine(store.observe(), dates, ::State)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), null)
    private val currentSave = MutableStateFlow(SaveStatus.IDLE)
    val saveStatus = currentSave.asStateFlow()

    fun setEnabled(enabled: Boolean) = save { store.setEnabled(enabled) }

    fun setSchedule(schedule: ServiceSchedule) = save { store.setSchedule(schedule) }
    fun setLeadDistance(lead: ServiceReminderLead) = save { store.setLeadDistance(lead) }
    fun setCalendar(schedule: ServiceCalendarSchedule) = save { store.setCalendar(schedule) }
    fun setCalendarLead(lead: ServiceCalendarLead) = save { store.setCalendarLead(lead) }

    private fun save(change: suspend () -> Unit) {
        if (currentSave.value == SaveStatus.SAVING) return
        currentSave.value = SaveStatus.SAVING
        viewModelScope.launch {
            currentSave.value = try {
                change()
                SaveStatus.IDLE
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                SaveStatus.FAILED
            }
        }
    }
}
