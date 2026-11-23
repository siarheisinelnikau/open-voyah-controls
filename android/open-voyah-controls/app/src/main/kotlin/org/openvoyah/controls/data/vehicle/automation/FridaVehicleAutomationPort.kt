package org.openvoyah.controls.data.vehicle.automation

import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Destination
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Request
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.data.frida.FridaVoiceDispatcher
import org.openvoyah.controls.data.frida.ReviewedVoiceCommands
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.domain.vehicle.automation.DrivingMode
import org.openvoyah.controls.domain.vehicle.automation.DrivingModeRecord
import org.openvoyah.controls.domain.vehicle.automation.HeadlightGearAction
import org.openvoyah.controls.domain.vehicle.automation.HeadlightGearDecision
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationCapabilities
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationOutcome
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationPort
import org.openvoyah.controls.domain.vehicle.automation.VehicleIdentity
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.domain.voice.VoiceOutcome

/** Reuses the native command planner and its individual, exact-firmware admission. */
internal class FridaVehicleAutomationPort(
    context: Context,
    private val telemetry: () -> VehicleTelemetry,
    private val lighting: () -> HeadlightStatus,
) : VehicleAutomationPort {
    private val app = context.applicationContext
    private val identity = VehicleIdentity(Build.PRODUCT, Build.FINGERPRINT, Build.VERSION.SDK_INT,
        Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
    private val reviewed = ReviewedVoiceCommands(app)
    private val dispatcher = FridaVoiceDispatcher(FridaBridge(app), VoiceSettings(app), reviewed)
    private val preferences = LocalVehicleAutomationPreferences(app)
    private val history = LocalDrivingModeHistory(app)
    private val catalog by lazy { VoiceSettings.catalog() }

    override fun capabilities(identity: VehicleIdentity?): VehicleAutomationCapabilities {
        if (identity != this.identity) return VehicleAutomationCapabilities()
        return VehicleAutomationCapabilities(
            headlightCommands.filterValues(::approved).keys,
            modeCommands.filterValues(::approved).keys,
        )
    }

    override suspend fun setHeadlight(decision: HeadlightGearDecision): VehicleAutomationOutcome {
        currentCoroutineContext().ensureActive()
        val settings = preferences.observe().first().getOrNull() ?: return VehicleAutomationOutcome.BLOCKED
        val current = telemetry()
        if (decision.action !in capabilities(current.identity).headlightActions ||
            !decision.isCurrent(settings.headlights, current, lighting(), SystemClock.elapsedRealtimeNanos())) {
            return VehicleAutomationOutcome.BLOCKED
        }
        return send(headlightCommands.getValue(decision.action), "headlight")
    }

    override suspend fun restoreDrivingMode(record: DrivingModeRecord, telemetry: VehicleTelemetry): VehicleAutomationOutcome {
        currentCoroutineContext().ensureActive()
        preferences.observe().first().getOrNull() ?: return VehicleAutomationOutcome.BLOCKED
        if (history.observe().first().getOrNull() != record) return VehicleAutomationOutcome.BLOCKED
        val current = this.telemetry()
        val now = SystemClock.elapsedRealtimeNanos()
        // Restore is a confirmed manual action; the memory switch only controls the automatic save.
        if (current.identity != record.identity || current.identity != identity ||
            current.sessionId != telemetry.sessionId || current.sessionStartedAtElapsedNanos != telemetry.sessionStartedAtElapsedNanos ||
            current.drivingMode != telemetry.drivingMode || !current.allowsDrivingModeRestore(now) ||
            current.drivingMode?.isCurrentIn(current, now) != true ||
            record.mode !in capabilities(current.identity).drivingModes) return VehicleAutomationOutcome.BLOCKED
        return send(modeCommands.getValue(record.mode), "driving_mode")
    }

    private fun approved(command: String): Boolean = reviewed.profileFor(command) != null && catalog.byId(command) != null

    private suspend fun send(id: String, kind: String): VehicleAutomationOutcome {
        val command = catalog.byId(id) ?: return VehicleAutomationOutcome.BLOCKED
        val result = dispatcher.dispatch(Request(Destination.VEHICLE, command, "", ""))
        val outcome = when (result.outcome) {
            VoiceOutcome.VEHICLE_CONFIRMED -> VehicleAutomationOutcome.CONFIRMED
            VoiceOutcome.VEHICLE_ACCEPTED, VoiceOutcome.VEHICLE_SENT -> VehicleAutomationOutcome.REQUESTED
            VoiceOutcome.VEHICLE_UNCONFIRMED, VoiceOutcome.VEHICLE_PARTIAL -> VehicleAutomationOutcome.UNCONFIRMED
            else -> VehicleAutomationOutcome.BLOCKED
        }
        ControlsLog.info("vehicle_automation_dispatch", "kind", kind, "command", id, "outcome", outcome)
        return outcome
    }

    private companion object {
        val headlightCommands = mapOf(HeadlightGearAction.AUTOMATIC to "AutoMode", HeadlightGearAction.LOW_BEAM to "LowBeam",
            HeadlightGearAction.POSITION to "PositionLamp", HeadlightGearAction.OFF to "LampOff")
        val modeCommands = mapOf(DrivingMode.ECO to "EcoMode", DrivingMode.COMFORT to "ComfortMode", DrivingMode.SPORT to "SportMode",
            DrivingMode.OFFROAD to "OffroadMode", DrivingMode.INDIVIDUAL to "IndividualMode", DrivingMode.SNOW to "SnowMode")
    }
}
