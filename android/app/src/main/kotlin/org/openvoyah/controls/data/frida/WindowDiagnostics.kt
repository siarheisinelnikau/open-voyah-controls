package org.openvoyah.controls.data.frida

import android.os.SystemClock
import android.util.Log

/** Emits one allowlisted log entry for an explicit window-service operation. */
internal object WindowDiagnostics {
    private const val TAG = "OpenVoyahControls"

    enum class Operation { APPLY_PROFILE, RESET_PROFILE, SYNC_DEFAULTS, ENABLE_SERVICE, DISABLE_SERVICE }

    enum class Stage {
        PREFLIGHT, ACCESS_CHECK, READINESS_CHECK, PUBLISH_PROFILES, REQUEST_ROUND_TRIP, AGENT_REPLY, SERVICE_TRANSITION,
    }

    enum class Outcome(val warning: Boolean = false) {
        APPLIED, DENSITY_ONLY, NO_TASK, UNSUPPORTED_WINDOW, UNSUPPORTED_TASK, NO_DISPLAY, NO_SPACE,
        PROFILES_PUBLISHED, SERVICE_CONFIGURED, RUNTIME_UNAVAILABLE, SERVICE_DISABLED, ANDROID_UNSUPPORTED,
        FIRMWARE_UNREVIEWED, DISPLAY_DPI_UNAVAILABLE, AGENT_NOT_READY(true), INVALID_TARGET(true),
        NO_PROFILE(true), PLACEMENT_CHANGED(true), DENSITY_REJECTED(true), ROLLBACK_FAILED(true),
        INVALID_REPLY(true), UNCONFIRMED(true), FAILED(true),
    }

    @JvmStatic
    fun start(operation: Operation): Attempt = Attempt(operation, SystemClock::elapsedRealtime) { outcome, line ->
        if (outcome.warning) Log.w(TAG, line) else Log.i(TAG, line)
    }

    class Attempt(
        private val operation: Operation,
        private val clock: () -> Long,
        private val write: (Outcome, String) -> Unit,
    ) : AutoCloseable {
        private val start = clock()
        private var stage = Stage.PREFLIGHT
        private var outcome = Outcome.FAILED

        fun enter(stage: Stage) {
            this.stage = stage
            outcome = if (stage == Stage.REQUEST_ROUND_TRIP) Outcome.UNCONFIRMED else Outcome.FAILED
        }

        fun complete(outcome: Outcome) {
            this.outcome = outcome
        }

        fun received(outcome: Outcome) {
            stage = Stage.AGENT_REPLY
            complete(outcome)
        }

        override fun close() {
            val elapsed = (clock() - start).coerceAtLeast(0L)
            write(outcome, "event=window_service operation=${operation.name.lowercase()} " +
                "stage=${stage.name.lowercase()} outcome=${outcome.name.lowercase()} elapsed_ms=$elapsed")
        }
    }
}
