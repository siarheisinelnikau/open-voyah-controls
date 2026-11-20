package org.openvoyah.controls.presentation.climate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openvoyah.controls.domain.climate.ClimateApplyResult
import org.openvoyah.controls.domain.climate.ClimateApplyStatus
import org.openvoyah.controls.domain.climate.ClimateConditionState
import org.openvoyah.controls.domain.climate.ClimateSeason
import org.openvoyah.controls.domain.climate.SeasonalClimateChanged
import org.openvoyah.controls.domain.climate.SeasonalClimatePlan
import org.openvoyah.controls.domain.climate.SeasonalClimatePolicy
import org.openvoyah.controls.domain.climate.SeasonalClimatePort
import org.openvoyah.controls.domain.climate.SeasonalClimatePreset
import org.openvoyah.controls.domain.climate.SeasonalClimateSettings
import org.openvoyah.controls.domain.climate.SeasonalClimateStore
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.domain.vehicle.automation.VehicleIdentity

internal data class SeasonalClimateConfirmation(
    val settings: SeasonalClimateSettings,
    val identity: VehicleIdentity,
    val sessionId: String,
    val sessionStartedAtElapsedNanos: Long,
    val plan: SeasonalClimatePlan,
)

internal data class SeasonalClimateForm(
    val saving: Boolean = false,
    val applying: Boolean = false,
    val saveFailed: Boolean = false,
    val changed: Boolean = false,
    val pending: SeasonalClimateConfirmation? = null,
    val lastResult: ClimateApplyResult? = null,
) {
    val busy: Boolean get() = saving || applying
}

internal data class SeasonalClimateUiState(
    val settings: SeasonalClimateSettings? = null,
    val loadFailed: Boolean = false,
    val telemetry: VehicleTelemetry? = null,
    val conditions: Map<ClimateSeason, ClimateConditionState> = emptyMap(),
    val approved: Set<ClimateSeason> = emptySet(),
    val form: SeasonalClimateForm = SeasonalClimateForm(),
) {
    val confirmation: SeasonalClimateConfirmation? get() = form.pending?.takeIf {
        it.settings == settings && it.identity == telemetry?.identity && it.sessionId == telemetry?.sessionId &&
            it.sessionStartedAtElapsedNanos == telemetry?.sessionStartedAtElapsedNanos &&
            conditions[it.plan.season] == ClimateConditionState.READY && it.plan.season in approved
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class SeasonalClimateViewModel(
    private val store: SeasonalClimateStore,
    telemetry: Flow<VehicleTelemetry>,
    private val port: SeasonalClimatePort,
    private val nowNanos: () -> Long,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val tasks = scope ?: viewModelScope
    private val form = MutableStateFlow(SeasonalClimateForm())
    // One expiry wakeup per received snapshot, only while this screen has a state collector.
    private val observed = telemetry.transformLatest<VehicleTelemetry, VehicleTelemetry?> { snapshot ->
        emit(snapshot)
        val firstSample = listOfNotNull(snapshot.gear?.observedAtElapsedNanos, snapshot.speedKph?.observedAtElapsedNanos,
            snapshot.outsideCelsius?.observedAtElapsedNanos).plus(snapshot.doors.values.map { it.observedAtElapsedNanos }).minOrNull()
        if (firstSample != null) {
            val untilExpiry = firstSample + 5_000_000_000 - nowNanos()
            delay((untilExpiry / 1_000_000 + 1).coerceAtLeast(1))
            emit(snapshot)
        }
    }.onStart { emit(null) }

    val state = combine(store.observe(), observed, form) { saved, snapshot, editor ->
        val settings = saved.getOrNull()
        val now = nowNanos()
        val conditions = settings?.let { configuration -> ClimateSeason.entries.associateWith {
            SeasonalClimatePolicy.assess(it, configuration.preset(it), snapshot, now)
        } }.orEmpty()
        val approved = settings?.let { configuration -> ClimateSeason.entries.filterTo(mutableSetOf()) {
            port.approved(SeasonalClimatePlan(it, configuration.preset(it)))
        } }.orEmpty()
        SeasonalClimateUiState(settings, saved.isFailure, snapshot, conditions, approved, editor)
    }.stateIn(tasks, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), SeasonalClimateUiState())

    fun savePreset(season: ClimateSeason, preset: SeasonalClimatePreset) {
        val current = state.value.settings ?: return
        val before = form.value
        if (before.busy) return
        val next = try { current.withPreset(season, preset) } catch (_: IllegalArgumentException) { return }
        if (next == current || !form.compareAndSet(before, before.copy(saving = true, saveFailed = false, changed = false, pending = null))) return
        tasks.launch {
            try {
                store.update(current, next)
                form.update { it.copy(saving = false, saveFailed = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                form.update { it.copy(saving = false, saveFailed = error !is SeasonalClimateChanged, changed = error is SeasonalClimateChanged) }
            }
        }
    }

    fun requestApply(season: ClimateSeason) {
        val current = state.value
        val settings = current.settings ?: return
        val snapshot = current.telemetry ?: return
        val preset = settings.preset(season)
        val plan = SeasonalClimatePlan(season, preset)
        if (current.form.busy || SeasonalClimatePolicy.assess(season, preset, snapshot, nowNanos()) != ClimateConditionState.READY ||
            !port.approved(plan)) return
        form.update { if (it.busy) it else it.copy(pending = SeasonalClimateConfirmation(settings, snapshot.identity,
            snapshot.sessionId, snapshot.sessionStartedAtElapsedNanos, plan), lastResult = null) }
    }

    fun dismissConfirmation() { form.update { it.copy(pending = null) } }

    /** Rechecks freshness and conditions immediately before crossing the physical boundary. */
    fun confirmApply() {
        val current = state.value
        val pending = current.confirmation ?: return dismissConfirmation()
        val snapshot = current.telemetry ?: return dismissConfirmation()
        val before = form.value
        if (before.busy || before.pending != pending) return
        if (SeasonalClimatePolicy.assess(pending.plan.season, pending.plan.preset, snapshot, nowNanos()) != ClimateConditionState.READY ||
            !port.approved(pending.plan)) return dismissConfirmation()
        if (!form.compareAndSet(before, before.copy(applying = true, pending = null, lastResult = null))) return
        tasks.launch {
            val result = try {
                val saved = store.observe().first().getOrNull()
                val latest = state.value.telemetry
                if (saved != pending.settings || latest?.identity != pending.identity || latest?.sessionId != pending.sessionId ||
                    latest?.sessionStartedAtElapsedNanos != pending.sessionStartedAtElapsedNanos ||
                    SeasonalClimatePolicy.assess(pending.plan.season, pending.plan.preset, latest, nowNanos()) != ClimateConditionState.READY ||
                    !port.approved(pending.plan)) ClimateApplyResult(ClimateApplyStatus.BLOCKED)
                else port.apply(pending.plan)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ClimateApplyResult(ClimateApplyStatus.UNCONFIRMED)
            }
            form.update { it.copy(applying = false, lastResult = result) }
        }
    }
}
