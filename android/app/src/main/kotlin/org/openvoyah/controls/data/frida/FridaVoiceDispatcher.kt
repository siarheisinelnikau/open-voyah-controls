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
import org.openvoyah.controls.domain.voice.VoiceResult

/** A button-initiated physical request completes at most once, even if its screen closes. */
internal class FridaVoiceDispatcher(
    private val bridge: FridaBridge,
    private val settings: VoiceSettings,
    private val reviewed: ReviewedVoiceCommands,
) {
    suspend fun dispatch(request: Request): VoiceResult {
        currentCoroutineContext().ensureActive()
        if (!inFlight.tryLock()) return VoiceResult(UiText.get(R.string.ui_199), failed = true)
        return worker.async {
            try {
                send(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                blocked(error.message.orEmpty())
            } finally {
                inFlight.unlock()
            }
        }.await()
    }

    private fun send(request: Request): VoiceResult {
        val id = request.command().id()
        val profile = reviewed.profileFor(id)?.takeIf { request.destination() == Destination.VEHICLE }
            ?: return result(R.string.voice_vehicle_approval_required, failed = true)
        if (id in drivers) return direct { bridge.switchDriver(id.substring(6)) }
        if (id == "RebootTbox") return direct { bridge.rebootTbox() }
        val planPhrase = request.phrase().takeIf { id in phraseDrivenPlans }.orEmpty()
        val options = settings.options(planPhrase).put("profile", profile)
        if (ClimatePhrases.supports(id)) options.put("climateValue", ClimatePhrases.value(id, request.phrase()))
        val reply = bridge.sendRecognizedVoice(id, options)
        return when (reply.status()) {
            "accepted" -> result(R.string.voice_vehicle_accepted)
            "verified" -> result(R.string.voice_vehicle_verified)
            "partial" -> result(R.string.voice_vehicle_partial, failed = true)
            "unknown" -> result(R.string.voice_vehicle_unknown, failed = true)
            "blocked" -> blocked(reply.message())
            else -> result(R.string.voice_vehicle_unknown, failed = true)
        }
    }

    private fun direct(write: () -> Unit): VoiceResult = try {
        write()
        result(R.string.voice_vehicle_sent)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        result(blockedLabel(error.message.orEmpty()) ?: R.string.voice_vehicle_unknown, failed = true)
    }

    private fun blockedLabel(message: String): Int? = when {
        "requires Parking gear" in message -> R.string.voice_requires_park
        "requires zero vehicle speed" in message -> R.string.voice_requires_stationary
        "Vehicle state unavailable" in message -> R.string.voice_vehicle_state_unavailable
        "not approved for this head unit" in message -> R.string.voice_vehicle_approval_required
        message == UiText.get(R.string.voice_refresh_bridge) -> R.string.voice_refresh_bridge
        else -> null
    }

    private fun blocked(message: String) = result(blockedLabel(message) ?: R.string.voice_vehicle_blocked, failed = true)

    private fun result(label: Int, failed: Boolean = false) = VoiceResult(UiText.get(label), failed)

    private companion object {
        val drivers = setOf("DriverA", "DriverB", "DriverC")
        val phraseDrivenPlans = setOf("OpenSunroofWithPercent", "OpenSunshadeWithPercent", "SaveModeWithSOC")
        val inFlight = Mutex()
        // The caller can cancel its wait. A started write is never replayed or interrupted by the UI.
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
