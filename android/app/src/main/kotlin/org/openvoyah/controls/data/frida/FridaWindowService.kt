package org.openvoyah.controls.data.frida

import android.content.Context
import java.io.IOException
import org.openvoyah.controls.domain.display.DisplayDefaultsDelivery
import org.openvoyah.controls.domain.display.WindowConnection
import org.openvoyah.controls.domain.display.WindowProfileChange
import org.openvoyah.controls.domain.display.WindowProfileDelivery
import org.openvoyah.controls.domain.display.WindowProfileDelivery.Outcome
import org.openvoyah.controls.domain.display.WindowProfileService

internal class FridaWindowService(context: Context) : WindowProfileService {
    private val app = context.applicationContext
    private val bridge = FridaBridge(app)

    override val supported: Boolean get() = FridaBridge.windowHookSupported()

    override fun connection(): WindowConnection = when {
        !FridaBridge.wasConfigured(app) || !bridge.windowReviewed() -> WindowConnection.DISCONNECTED
        !FridaBridge.windowHookEnabled(app) -> WindowConnection.DISABLED
        else -> WindowConnection.ENABLED
    }

    override fun enable() = bridge.setWindowHook(true)

    override fun apply(component: String, change: WindowProfileChange): WindowProfileDelivery = try {
        bridge.applyWindowProfile(component, change == WindowProfileChange.RESET)
    } catch (_: IOException) {
        Outcome.UNCONFIRMED
    }

    override fun publishDefaults(passengerRequested: Boolean): DisplayDefaultsDelivery {
        val published = try {
            bridge.syncWindowProfiles()
        } catch (_: IOException) {
            false
        }
        return when {
            !published -> DisplayDefaultsDelivery.SAVED
            passengerRequested && !bridge.passengerWindowReviewed() -> DisplayDefaultsDelivery.CENTRAL_ONLY
            else -> DisplayDefaultsDelivery.NEXT_LAUNCH
        }
    }
}
