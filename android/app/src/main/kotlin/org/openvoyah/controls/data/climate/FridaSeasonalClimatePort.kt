package org.openvoyah.controls.data.climate

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
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
import org.openvoyah.controls.domain.climate.SeasonalClimatePlan
import org.openvoyah.controls.domain.climate.SeasonalClimatePort
import org.openvoyah.controls.domain.climate.SeasonalClimatePolicy
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
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

    override suspend fun apply(plan: SeasonalClimatePlan): ClimateApplyResult {
        currentCoroutineContext().ensureActive()
        if (!plan.preset.enabled || !approved(plan)) return ClimateApplyResult(ClimateApplyStatus.BLOCKED)
        if (!inFlight.tryLock()) return ClimateApplyResult(ClimateApplyStatus.BUSY)
        var completed = 0
        var verified = 0
        var reserved = false
        try {
            for (setpoint in plan.commands) {
                currentCoroutineContext().ensureActive()
                val saved = preferences.observe().first().getOrNull()
                val current = telemetry()
                if (saved?.preset(plan.season) != plan.preset ||
                    SeasonalClimatePolicy.assess(plan.season, plan.preset, current, SystemClock.elapsedRealtimeNanos()) !=
                        ClimateConditionState.READY) return result(ClimateApplyStatus.BLOCKED, completed)
                val command = catalog.byId(setpoint.id) ?: return result(ClimateApplyStatus.BLOCKED, completed)
                if (!reserved) {
                    if (!guard.tryReserve(plan, SystemClock.elapsedRealtimeNanos()))
                        return result(ClimateApplyStatus.RECENT, completed)
                    reserved = true
                }
                val phrase = when (setpoint.id) {
                    "SetDriverTemperature" -> "driver temperature " + setpoint.value
                    "SetPassengerTemperature" -> "passenger temperature " + setpoint.value
                    "SetFanSpeed" -> "fan speed " + setpoint.value
                    else -> ""
                }
                val outcome = dispatcher.dispatch(Request(Destination.VEHICLE, command, phrase, "")).outcome
                when (outcome) {
                    VoiceOutcome.VEHICLE_CONFIRMED -> { completed++; verified++ }
                    VoiceOutcome.VEHICLE_ACCEPTED -> completed++
                    VoiceOutcome.VEHICLE_UNCONFIRMED, VoiceOutcome.VEHICLE_SENT ->
                        return result(ClimateApplyStatus.UNCONFIRMED, completed)
                    VoiceOutcome.VEHICLE_PARTIAL -> return result(ClimateApplyStatus.PARTIAL, completed)
                    VoiceOutcome.BUSY -> return result(ClimateApplyStatus.BUSY, completed)
                    else -> return result(ClimateApplyStatus.BLOCKED, completed)
                }
            }
            return result(if (verified == plan.commands.size) ClimateApplyStatus.VERIFIED else ClimateApplyStatus.ACCEPTED, completed)
        } catch (cancelled: CancellationException) {
            // A dispatched request continues in the shared transport. Never restart the remaining group.
            Log.w(TAG, "event=seasonal_climate_request outcome=caller_cancelled completed=$completed")
            throw cancelled
        } catch (_: Exception) {
            return result(ClimateApplyStatus.UNCONFIRMED, completed)
        } finally {
            inFlight.unlock()
        }
    }

    private fun result(status: ClimateApplyStatus, completed: Int): ClimateApplyResult {
        val actual = if (completed > 0 && status in setOf(ClimateApplyStatus.BLOCKED, ClimateApplyStatus.BUSY))
            ClimateApplyStatus.PARTIAL else status
        Log.i(TAG, "event=seasonal_climate_request outcome=${actual.name.lowercase()} completed=$completed")
        return ClimateApplyResult(actual, completed)
    }

    private companion object {
        const val TAG = "OpenVoyahControls"
        val inFlight = Mutex()
        val guard = ClimateApplyGuard()
    }
}
