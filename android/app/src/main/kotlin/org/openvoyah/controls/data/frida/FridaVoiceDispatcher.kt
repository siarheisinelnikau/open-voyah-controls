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
import org.openvoyah.controls.domain.voice.VoiceOutcome
import org.openvoyah.controls.domain.voice.VoiceResult

/** A button-initiated physical request completes at most once, even if its screen closes. */
internal class FridaVoiceDispatcher(
    private val bridge: FridaBridge,
    private val settings: VoiceSettings,
    private val reviewed: ReviewedVoiceCommands,
) {
    suspend fun dispatch(request: Request): VoiceResult {
        currentCoroutineContext().ensureActive()
        if (!inFlight.tryLock()) return result(R.string.ui_199, VoiceOutcome.BUSY, failed = true)
        return worker.async {
            try {
                send(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                refusal(error.message.orEmpty()) ?: unknownResult()
            } finally {
                inFlight.unlock()
            }
        }.await()
    }

    private fun send(request: Request): VoiceResult {
        val id = request.command().id()
        val profile = reviewed.profileFor(id)?.takeIf { request.destination() == Destination.VEHICLE }
            ?: return result(R.string.voice_vehicle_approval_required, VoiceOutcome.VEHICLE_APPROVAL_REQUIRED, failed = true)
        if (id in drivers) return direct { bridge.switchDriver(id.substring(6)) }
        if (id == "RebootTbox") return direct { bridge.rebootTbox() }
        val planPhrase = request.phrase().takeIf { id in phraseDrivenPlans }.orEmpty()
        val options = settings.options(planPhrase).put("profile", profile)
        if (ClimatePhrases.supports(id)) options.put("climateValue", ClimatePhrases.value(id, request.phrase()))
        val reply = try {
            bridge.sendRecognizedVoice(id, options)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Publishing and reply failures are already reported as unknown by the bridge.
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

    private fun direct(write: () -> Unit): VoiceResult = try {
        write()
        result(R.string.voice_vehicle_sent, VoiceOutcome.VEHICLE_SENT)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: OverseaProfiles.Refused) {
        refused(error.label)
    } catch (error: Exception) {
        refusal(error.message.orEmpty()) ?: unknownResult()
    }

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
        val phraseDrivenPlans = setOf("OpenSunroofWithPercent", "OpenSunshadeWithPercent", "SaveModeWithSOC")
        val inFlight = Mutex()
        // The caller can cancel its wait. A started write is never replayed or interrupted by the UI.
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
