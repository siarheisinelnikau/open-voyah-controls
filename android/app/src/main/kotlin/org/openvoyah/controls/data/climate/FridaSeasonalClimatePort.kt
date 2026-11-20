package org.openvoyah.controls.data.climate

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Destination
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Request
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.data.frida.FridaVoiceDispatcher
import org.openvoyah.controls.data.frida.ReviewedVoiceCommands
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.domain.climate.ClimateApplyGuard
import org.openvoyah.controls.domain.climate.ClimateApplyResult
import org.openvoyah.controls.domain.climate.ClimateApplyStatus
import org.openvoyah.controls.domain.climate.ClimateConditionState
import org.openvoyah.controls.domain.climate.ClimateSetpointCommand
import org.openvoyah.controls.domain.climate.SeasonalClimatePlan
import org.openvoyah.controls.domain.climate.SeasonalClimatePort
import org.openvoyah.controls.domain.climate.SeasonalClimatePolicy
import org.openvoyah.controls.domain.climate.SeasonalClimatePreset
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetrySession
import org.openvoyah.controls.domain.voice.VoiceOutcome

/** Uses the existing per-command firmware gate, power check and no-replay transport. */
internal class FridaSeasonalClimatePort(context: Context, private val telemetry: () -> VehicleTelemetry? = { null }) : SeasonalClimatePort {
    private val app = context.applicationContext
    private val reviewed = ReviewedVoiceCommands(app)
    private val dispatcher = FridaVoiceDispatcher(FridaBridge(app), VoiceSettings(app), reviewed)
    private val preferences = LocalSeasonalClimatePreferences(app)
    private val catalog by lazy { VoiceSettings.catalog() }

    override fun approved(plan: SeasonalClimatePlan): Boolean = plan.commands.all {
        reviewed.profileFor(it.id) != null && catalog.byId(it.id) != null
    }

    override suspend fun apply(plan: SeasonalClimatePlan, session: VehicleTelemetrySession): ClimateApplyResult {
        currentCoroutineContext().ensureActive()
        if (!plan.preset.enabled || !approved(plan)) return ClimateApplyResult(ClimateApplyStatus.BLOCKED)
        if (!inFlight.tryLock()) return ClimateApplyResult(ClimateApplyStatus.BUSY)
        try {
            val result = sendSeasonalClimatePlan(plan, session, telemetry, SystemClock::elapsedRealtimeNanos, guard,
                readPreset = { preferences.observe().first().getOrNull()?.preset(plan.season) }) dispatch@{ setpoint, stillAllowed ->
                val command = catalog.byId(setpoint.id) ?: return@dispatch VoiceOutcome.VEHICLE_BLOCKED
                val phrase = when (setpoint.id) {
                    "SetDriverTemperature" -> "driver temperature " + setpoint.value
                    "SetPassengerTemperature" -> "passenger temperature " + setpoint.value
                    "SetFanSpeed" -> "fan speed " + setpoint.value
                    else -> ""
                }
                dispatcher.dispatch(Request(Destination.VEHICLE, command, phrase, ""), stillAllowed).outcome
            }
            Log.i(TAG, "event=seasonal_climate_request outcome=${result.status.name.lowercase()} completed=${result.completedCommands}")
            return result
        } catch (cancelled: CancellationException) {
            // A dispatched request continues in the shared transport. Never restart the remaining group.
            Log.w(TAG, "event=seasonal_climate_request outcome=caller_cancelled")
            throw cancelled
        } finally {
            inFlight.unlock()
        }
    }

    private companion object {
        const val TAG = "OpenVoyahControls"
        val inFlight = Mutex()
        val guard = ClimateApplyGuard()
    }
}

/** Recheck the original session for every setpoint, including at the transport's publish boundary. */
internal suspend fun sendSeasonalClimatePlan(
    plan: SeasonalClimatePlan,
    session: VehicleTelemetrySession,
    telemetry: () -> VehicleTelemetry?,
    nowNanos: () -> Long,
    guard: ClimateApplyGuard,
    readPreset: suspend () -> SeasonalClimatePreset?,
    dispatch: suspend (ClimateSetpointCommand, () -> Boolean) -> VoiceOutcome,
): ClimateApplyResult {
    var completed = 0
    var verified = 0
    fun result(status: ClimateApplyStatus): ClimateApplyResult {
        val actual = if (completed > 0 && (status == ClimateApplyStatus.BLOCKED || status == ClimateApplyStatus.BUSY))
            ClimateApplyStatus.PARTIAL else status
        return ClimateApplyResult(actual, completed)
    }
    val caller = currentCoroutineContext()
    val stillAllowed = {
        val current = telemetry()
        caller[Job]?.isActive != false && session.matches(current) &&
            SeasonalClimatePolicy.assess(plan.season, plan.preset, current, nowNanos()) == ClimateConditionState.READY
    }
    val commands = plan.commands
    try {
        for ((index, setpoint) in commands.withIndex()) {
            caller.ensureActive()
            if (readPreset() != plan.preset || !stillAllowed()) return result(ClimateApplyStatus.BLOCKED)
            if (index == 0 && !guard.tryReserve(plan, nowNanos())) return result(ClimateApplyStatus.RECENT)
            when (dispatch(setpoint, stillAllowed)) {
                VoiceOutcome.VEHICLE_CONFIRMED -> { completed++; verified++ }
                VoiceOutcome.VEHICLE_ACCEPTED -> completed++
                VoiceOutcome.VEHICLE_UNCONFIRMED, VoiceOutcome.VEHICLE_SENT -> return result(ClimateApplyStatus.UNCONFIRMED)
                VoiceOutcome.VEHICLE_PARTIAL -> return result(ClimateApplyStatus.PARTIAL)
                VoiceOutcome.BUSY -> return result(ClimateApplyStatus.BUSY)
                else -> return result(ClimateApplyStatus.BLOCKED)
            }
        }
        return result(if (verified == commands.size) ClimateApplyStatus.VERIFIED else ClimateApplyStatus.ACCEPTED)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return result(ClimateApplyStatus.UNCONFIRMED)
    }
}
