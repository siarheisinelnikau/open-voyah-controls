package org.openvoyah.controls.presentation.firmware

import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.display.WindowConnection
import org.openvoyah.controls.domain.display.WindowStripState
import org.openvoyah.controls.domain.display.WindowStripState.Phase
import org.openvoyah.controls.presentation.maintenance.WindowStripRow

/** The line under the experimental option. [text] resolves a string resource; tests pass their own. */
internal fun WindowStripState.statusText(text: (Int) -> String = UiText::get): String = when (phase) {
    Phase.SERVICE_OFF -> text(R.string.window_strip_status_service_off)
    Phase.AGENT_UNAVAILABLE -> text(R.string.window_strip_status_agent)
    Phase.OFF -> text(R.string.window_strip_status_off)
    Phase.STARTING -> text(R.string.window_strip_status_starting)
    Phase.WAITING_PANEL -> text(R.string.window_strip_status_waiting_panel)
    // Without a width there is nothing to say yet that "Starting…" does not.
    Phase.CHECKING -> if (widthPx > 0) text(R.string.window_strip_status_checking).format(widthPx)
        else text(R.string.window_strip_status_starting)
    Phase.ACTIVE -> text(R.string.window_strip_status_active).format(widthPx)
    Phase.OTHER_USER -> text(R.string.window_strip_status_other_user)
    Phase.BLOCKED -> text(R.string.window_strip_status_blocked)
    Phase.UNSUPPORTED -> text(R.string.window_strip_status_unsupported).format(code.orEmpty())
    Phase.FAILED -> text(R.string.window_strip_status_failed).format(code.orEmpty())
    Phase.NOT_UNDONE -> text(R.string.window_strip_status_not_undone)
    Phase.UNKNOWN -> text(R.string.window_strip_status_unknown)
}

/** An attempt passes through these within seconds, so the page asks again instead of leaving a stale line. */
internal val WindowStripState.settling: Boolean
    get() = phase == Phase.STARTING || phase == Phase.CHECKING || phase == Phase.WAITING_PANEL

/**
 * The option's row and whether its state is still moving. With the option off nothing is asked of the agent: a
 * head unit that never used the experiment sees no request for it.
 */
internal fun windowStripRow(
    chosen: Boolean,
    connection: WindowConnection,
    state: () -> WindowStripState,
    text: (Int) -> String = UiText::get,
): Pair<WindowStripRow, Boolean> {
    val serviceRuns = connection == WindowConnection.ENABLED
    if (!chosen) {
        val status = if (serviceRuns) R.string.window_strip_status_off else R.string.window_strip_status_service_off
        return WindowStripRow(false, text(status), changeable = serviceRuns) to false
    }
    val current = if (serviceRuns) state() else WindowStripState(Phase.SERVICE_OFF)
    // A saved choice can always be withdrawn, also while the service it needs is off.
    return WindowStripRow(true, current.statusText(text), changeable = true) to current.settling
}
