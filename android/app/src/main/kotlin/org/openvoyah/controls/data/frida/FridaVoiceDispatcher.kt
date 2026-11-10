package org.openvoyah.controls.data.frida

import android.os.Build
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
import org.openvoyah.controls.core.Firmware
import org.openvoyah.controls.core.voice.ClimatePhrases
import org.openvoyah.controls.core.voice.VoiceCommandUseCase.Request
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.domain.voice.VoiceResult

/** A button-initiated physical request completes at most once, even if its screen closes. */
class FridaVoiceDispatcher(private val bridge: FridaBridge, private val settings: VoiceSettings) {
    suspend fun dispatch(request: Request): VoiceResult {
        currentCoroutineContext().ensureActive()
        if (!inFlight.tryLock()) return VoiceResult(UiText.get(R.string.ui_199), failed = true)
        return worker.async {
            try {
                send(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure(error.message.orEmpty())
            } finally {
                inFlight.unlock()
            }
        }.await()
    }

    private fun send(request: Request): VoiceResult {
        val id = request.command().id()
        if (id in drivers) {
            bridge.switchDriver(id.substring(6))
            return sent()
        }
        if (id == "RebootTbox") {
            bridge.rebootTbox()
            return sent()
        }
        val options = settings.options(request.phrase())
        if (id == "ForcedElectroMode") options.put("profile", Firmware.identify(Build.PRODUCT))
        if (ClimatePhrases.supports(id)) options.put("climateValue", ClimatePhrases.value(id, request.phrase()))
        val reply = bridge.sendRecognizedVoice(id, options)
        return if (reply.accepted()) sent() else failure(reply.message())
    }

    private fun sent() = VoiceResult(UiText.get(R.string.voice_vehicle_sent))

    private fun failure(message: String): VoiceResult {
        val text = if ("requires Parking gear" in message) UiText.get(R.string.voice_requires_park)
            else message.ifBlank { UiText.get(R.string.ui_205) }
        return VoiceResult(text, failed = true)
    }

    private companion object {
        val drivers = setOf("DriverA", "DriverB", "DriverC")
        val inFlight = Mutex()
        // The caller can cancel its wait. A started write is never replayed or interrupted by the UI.
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
