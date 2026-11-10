package org.openvoyah.controls.data.frida

import android.content.Context
import org.json.JSONObject
import org.openvoyah.controls.data.display.AppWindowPreferences
import org.openvoyah.controls.domain.display.AppDisplayDefaults
import org.openvoyah.controls.domain.display.AppWindowProfile

internal class FridaWindowControls(context: Context) {
    data class Result(val status: Status, val width: Int = 0, val dpi: Int = 0, val left: Int = 0)

    enum class Status {
        APPLIED, DENSITY_ONLY, NO_TASK, UNSUPPORTED_WINDOW, UNSUPPORTED_TASK, NO_SPACE, UNAVAILABLE, FAILED, SAVED
    }

    enum class Connection { DISCONNECTED, DISABLED, ENABLED }
    enum class DefaultsDelivery { SAVED, CENTRAL_ONLY, NEXT_LAUNCH }

    private val context = context.applicationContext
    private val bridge = FridaBridge(this.context)
    private val profiles = AppWindowPreferences(this.context)

    fun supports(component: String): Boolean = FridaBridge.windowHookSupported() && AppWindowProfile.eligible(component)
    fun supportsDisplayDefaults(): Boolean = FridaBridge.windowHookSupported()

    fun connection(): Connection = when {
        !FridaBridge.wasConfigured(context) || !bridge.windowReviewed() -> Connection.DISCONNECTED
        !FridaBridge.windowHookEnabled(context) -> Connection.DISABLED
        else -> Connection.ENABLED
    }

    fun load(component: String): AppWindowProfile = profiles.load(component)
    fun loadDisplayDefaults(): AppDisplayDefaults = profiles.loadDefaults()

    /** A failed delivery still leaves the validated defaults saved for the next connection. */
    fun saveDisplayDefaults(defaults: AppDisplayDefaults): DefaultsDelivery {
        profiles.saveDefaults(defaults)
        val synced = try {
            bridge.syncWindowProfiles()
        } catch (_: java.io.IOException) {
            false
        }
        return when {
            !synced -> DefaultsDelivery.SAVED
            defaults.secondaryDpi != null && !bridge.passengerWindowReviewed() -> DefaultsDelivery.CENTRAL_ONLY
            else -> DefaultsDelivery.NEXT_LAUNCH
        }
    }

    fun enable() = bridge.setWindowHook(true)

    fun save(profile: AppWindowProfile, reset: Boolean): Result {
        if (reset) profiles.reset(profile.component) else profiles.save(profile)
        if (connection() != Connection.ENABLED) return Result(Status.SAVED)
        return try {
            val reply = JSONObject(bridge.applyWindowProfile(profile.component, reset))
            val status = when (reply.optString("status")) {
                "applied" -> Status.APPLIED
                "density_only" -> Status.DENSITY_ONLY
                "no_task" -> Status.NO_TASK
                "unsupported_window" -> Status.UNSUPPORTED_WINDOW
                "unsupported_task", "no_display" -> Status.UNSUPPORTED_TASK
                "no_space" -> Status.NO_SPACE
                else -> Status.FAILED
            }
            Result(status, reply.optInt("width"), reply.optInt("dpi"), reply.optInt("left"))
        } catch (_: java.io.IOException) {
            Result(Status.UNAVAILABLE)
        }
    }
}
