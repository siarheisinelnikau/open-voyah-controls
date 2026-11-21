package org.openvoyah.controls.domain.display

/**
 * What is known about the experimental strip that Android itself reserves for the launcher's left panel.
 * [code] is the agent's own token for a refusal or failure; it is shown as it is, because it names the exact
 * precondition that did not hold on this firmware.
 */
internal data class WindowStripState(val phase: Phase, val widthPx: Int = 0, val code: String? = null) {
    enum class Phase {
        /** The window service is off or not connected, so there is no agent to ask. */
        SERVICE_OFF,
        /** The running agent predates the option, is not ready, or did not answer. */
        AGENT_UNAVAILABLE,
        OFF,
        /** The attempt is recorded and Android is about to be asked. */
        STARTING,
        WAITING_PANEL,
        /** Applied; the laid-out frames have not been confirmed yet. */
        CHECKING,
        ACTIVE,
        OTHER_USER,
        /** Turned off by the agent because the system process restarted while the strip was reserved. */
        BLOCKED,
        /** A precondition of this firmware does not hold; trying again will not help. */
        UNSUPPORTED,
        FAILED,
        /** An undo did not complete; only a restart of the head unit restores the native layout for certain. */
        NOT_UNDONE,
        UNKNOWN,
    }
}
