package org.openvoyah.controls.data.frida

import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.keyboard.KeyboardAvailability
import org.openvoyah.controls.domain.keyboard.KeyboardFallback
import org.openvoyah.controls.domain.keyboard.KeyboardOutcome
import org.openvoyah.controls.domain.keyboard.KeyboardPort
import org.openvoyah.controls.domain.keyboard.KeyboardResult
import org.openvoyah.controls.domain.keyboard.KeyboardStatus

/** Keyboard choice through the provisioned root channel and the window agent's keyboard policy. */
internal class RootKeyboardPort(context: Context) : KeyboardPort {
    private val app = context.applicationContext
    private val bridge by lazy { FridaBridge(app) }

    override fun availability(): KeyboardAvailability = when {
        !FridaBridge.windowHookSupported() -> KeyboardAvailability.UNSUPPORTED
        !FridaBridge.wasConfigured(app) || !bridge.windowReviewed() -> KeyboardAvailability.SETUP_REQUIRED
        !FridaBridge.windowHookEnabled(app) -> KeyboardAvailability.WINDOW_SERVICE_OFF
        else -> try {
            if (bridge.keyboardPolicyReady()) KeyboardAvailability.READY else KeyboardAvailability.WINDOW_SERVICE_OFF
        } catch (_: ProductionRootAccess.Refused) {
            KeyboardAvailability.SETUP_REQUIRED
        } catch (_: IOException) {
            KeyboardAvailability.WINDOW_SERVICE_OFF
        }
    }

    override fun read(): KeyboardResult {
        val result = run("read", KeyboardSelection.readScript())
        val choice = result.status.choice ?: return result
        // The agent ignores a keyboard that is gone; dropping the stored choice keeps the page honest.
        if (installed(KeyboardSelection.packageOf(choice))) return result
        return run("release", KeyboardSelection.releaseScript(choice))
    }

    override fun select(id: String): KeyboardResult {
        // A choice that the agent cannot apply would only be undone by the firmware, so it is not attempted.
        if (availability() != KeyboardAvailability.READY) throw IOException("Keyboard policy is not running")
        return run("select", KeyboardSelection.selectScript(id))
    }

    override fun restoreFactory(): KeyboardResult = run("factory", KeyboardSelection.factoryScript())

    private fun run(path: String, script: String): KeyboardResult {
        ControlsLog.span("keyboard", "path", path).use { span ->
            try {
                val snapshot = KeyboardSelection.parse(bridge.keyboardCommand(script))
                span.outcome(snapshot.outcome.token)
                return KeyboardResult(outcome(snapshot.outcome), status(snapshot))
            } catch (error: Exception) {
                span.failed(error)
                throw error
            }
        }
    }

    private fun installed(packageName: String): Boolean = try {
        app.packageManager.getApplicationInfo(packageName, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        fun outcome(outcome: KeyboardSelection.Outcome): KeyboardOutcome = when (outcome) {
            KeyboardSelection.Outcome.APPLIED -> KeyboardOutcome.APPLIED
            KeyboardSelection.Outcome.NOT_CONFIRMED -> KeyboardOutcome.NOT_CONFIRMED
            KeyboardSelection.Outcome.NOT_ENABLED -> KeyboardOutcome.NOT_ENABLED
            KeyboardSelection.Outcome.FACTORY -> KeyboardOutcome.FACTORY
            KeyboardSelection.Outcome.READ -> KeyboardOutcome.READ
        }

        fun status(snapshot: KeyboardSelection.Snapshot) = KeyboardStatus(
            choice = snapshot.choice,
            active = snapshot.active,
            passenger = snapshot.passenger,
            fallback = when (snapshot.fallback?.reason) {
                KeyboardSelection.Reason.CRASHED -> KeyboardFallback.CRASHED
                KeyboardSelection.Reason.UNAVAILABLE -> KeyboardFallback.UNAVAILABLE
                null -> null
            },
            fallbackId = snapshot.fallback?.id,
        )
    }
}
