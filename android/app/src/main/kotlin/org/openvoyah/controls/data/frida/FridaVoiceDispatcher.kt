package org.openvoyah.controls.data.frida

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.voice.ClimatePhrases
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Destination
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Request
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.voice.VoiceOutcome
import org.openvoyah.controls.domain.voice.VoiceResult

/** A button-initiated physical request completes at most once, even if its screen closes. */
internal class FridaVoiceDispatcher(
    private val bridge: FridaBridge,
    private val settings: VoiceSettings,
    private val reviewed: ReviewedVoiceCommands,
) {
    suspend fun dispatch(request: Request, stillAllowed: (() -> Boolean)? = null): VoiceResult {
        currentCoroutineContext().ensureActive()
        val command = loggedCommand(request.command().id())
        if (!inFlight.tryLock()) return logged(command, result(R.string.ui_199, VoiceOutcome.BUSY, failed = true))
        return worker.async {
            val outcome = try {
                send(request, stillAllowed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ControlsLog.warn("voice_vehicle_dispatch", error, "command", command, "stage", "dispatch")
                refusal(error.message.orEmpty()) ?: unknownResult()
            } finally {
                inFlight.unlock()
            }
            // Logged by the worker, so a started request is recorded even when its screen stops waiting.
            logged(command, outcome)
        }.await()
    }

    private fun send(request: Request, stillAllowed: (() -> Boolean)?): VoiceResult {
        val id = request.command().id()
        val profile = reviewed.profileFor(id)?.takeIf { request.destination() == Destination.VEHICLE }
            ?: return result(R.string.voice_vehicle_approval_required, VoiceOutcome.VEHICLE_APPROVAL_REQUIRED, failed = true)
        if (id in drivers) return direct(id) { bridge.switchDriver(id.substring(6)) }
        if (id == "RebootTbox") return direct(id) { bridge.rebootTbox() }
        val planPhrase = request.phrase().takeIf { id in phraseDrivenPlans }.orEmpty()
        val options = settings.options(planPhrase).put("profile", profile)
        if (ClimatePhrases.supports(id)) options.put("climateValue", ClimatePhrases.value(id, request.phrase()))
        val reply = try {
            if (stillAllowed == null) bridge.sendRecognizedVoice(id, options)
            else bridge.sendRecognizedVoice(id, options) { stillAllowed() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Publishing and reply failures are already reported as unknown by the bridge.
            ControlsLog.warn("voice_vehicle_dispatch", failure, "command", loggedCommand(id), "stage", "before_publish")
            return refusal(failure.message.orEmpty()) ?: if (failure is ProductionRootAccess.Refused) {
                refused(R.string.voice_vehicle_blocked)
            } else result(R.string.voice_outcome_unavailable, VoiceOutcome.UNAVAILABLE, failed = true)
        }
        return when (reply.status()) {
            "accepted" -> result(R.string.voice_vehicle_accepted, VoiceOutcome.VEHICLE_ACCEPTED)
            "verified" -> result(R.string.voice_vehicle_verified, VoiceOutcome.VEHICLE_CONFIRMED)
            "partial" -> result(R.string.voice_vehicle_partial, VoiceOutcome.VEHICLE_PARTIAL, failed = true)
            "blocked" -> refusal(reply.message()) ?: refused(R.string.voice_vehicle_blocked)
            else -> unknownResult()
        }
    }

    private fun direct(id: String, write: () -> Unit): VoiceResult = try {
        write()
        result(R.string.voice_vehicle_sent, VoiceOutcome.VEHICLE_SENT)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: OverseaProfiles.Refused) {
        ControlsLog.warn("voice_vehicle_dispatch", error, "command", loggedCommand(id), "stage", "direct")
        refused(error.label)
    } catch (error: Exception) {
        ControlsLog.warn("voice_vehicle_dispatch", error, "command", loggedCommand(id), "stage", "direct")
        refusal(error.message.orEmpty()) ?: unknownResult()
    }

    private fun logged(command: String, result: VoiceResult): VoiceResult {
        if (result.failed) ControlsLog.warn("voice_vehicle_dispatch", "command", command, "outcome", result.outcome)
        else ControlsLog.info("voice_vehicle_dispatch", "command", command, "outcome", result.outcome)
        return result
    }

    /** Catalog identifiers only; a generated per-contact identifier is never written to diagnostics. */
    private fun loggedCommand(id: String): String = id.takeIf { catalogId.matches(it) } ?: "other"

    private fun refusal(message: String): VoiceResult? = when {
        "requires Parking gear" in message -> refused(R.string.voice_requires_park)
        "requires zero vehicle speed" in message -> refused(R.string.voice_requires_stationary)
        "Vehicle state unavailable" in message -> refused(R.string.voice_vehicle_state_unavailable)
        "not approved for this head unit" in message -> refused(R.string.voice_vehicle_approval_required)
        message == UiText.get(R.string.voice_refresh_bridge) -> refused(R.string.voice_refresh_bridge)
        message == UiText.get(R.string.ui_19) || message == UiText.get(R.string.production_connect_required) -> refused(R.string.ui_19)
        message == UiText.get(R.string.production_broker_unavailable) -> refused(R.string.production_broker_unavailable)
        message == UiText.get(R.string.runtime_update_paused) -> refused(R.string.runtime_update_paused)
        message == UiText.get(R.string.production_operation_unreviewed) ||
            message == UiText.get(R.string.production_unreviewed_firmware) -> refused(R.string.voice_vehicle_approval_required)
        else -> null
    }

    private fun refused(label: Int) = result(label, when (label) {
        R.string.voice_requires_park -> VoiceOutcome.PARK_REQUIRED
        R.string.voice_requires_stationary -> VoiceOutcome.STANDSTILL_REQUIRED
        R.string.voice_vehicle_state_unavailable -> VoiceOutcome.VEHICLE_STATE_UNAVAILABLE
        R.string.voice_vehicle_approval_required -> VoiceOutcome.VEHICLE_APPROVAL_REQUIRED
        R.string.voice_refresh_bridge -> VoiceOutcome.VEHICLE_UPDATE_REQUIRED
        R.string.ui_19 -> VoiceOutcome.VEHICLE_CONNECT_REQUIRED
        R.string.production_broker_unavailable -> VoiceOutcome.VEHICLE_SETUP_REQUIRED
        R.string.runtime_update_paused -> VoiceOutcome.VEHICLE_UPDATE_PAUSED
        else -> VoiceOutcome.VEHICLE_BLOCKED
    }, failed = true)

    private fun unknownResult() = result(R.string.voice_vehicle_unknown, VoiceOutcome.VEHICLE_UNCONFIRMED, failed = true)
    private fun result(label: Int, outcome: VoiceOutcome, failed: Boolean = false) = VoiceResult(UiText.get(label), failed, outcome)

    private companion object {
        val drivers = setOf("DriverA", "DriverB", "DriverC")
        val catalogId = Regex("[A-Za-z][A-Za-z0-9]{0,63}")
        val phraseDrivenPlans = setOf("OpenSunroofWithPercent", "OpenSunshadeWithPercent", "SaveModeWithSOC")
        val inFlight = Mutex()
        // The caller can cancel its wait. A started write is never replayed or interrupted by the UI.
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
