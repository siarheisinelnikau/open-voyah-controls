package org.openvoyah.controls.data.frida

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.openvoyah.controls.domain.display.WindowStripState
import org.openvoyah.controls.domain.display.WindowStripState.Phase

/** Decodes the window agent's answer to an `insets` request. Anything outside its vocabulary is [Phase.UNKNOWN]. */
internal object WindowStripReceipt {
    private val unknown = WindowStripState(Phase.UNKNOWN)
    private val unsupported = setOf("unsupported_sdk", "unsupported_framework", "oem_policy", "insets_mode", "system_nav_bar",
        "unsupported_display", "left_inset_present", "ambiguous_panel", "already_reserved")
    private val failed = setOf("panel_moved", "verify_failed", "guard_unavailable", "failed", "busy")

    @JvmStatic
    fun parse(raw: String): WindowStripState = try {
        val tokens = JSONTokener(raw)
        val reply = tokens.nextValue() as? JSONObject
        if (reply == null || tokens.nextClean() != '\u0000' || tokens.more()) unknown
        else decodeFields(reply.opt("status"), reply.opt("insets"), reply.opt("width"))
    } catch (_: JSONException) {
        unknown
    }

    fun decodeFields(status: Any?, insets: Any?, width: Any? = null): WindowStripState {
        if (status != "applied" || insets !is String) return unknown
        val reserved = (width as? Int)?.takeIf { it in 1..MAX_STRIP_PX } ?: 0
        return when (insets) {
            "inactive" -> WindowStripState(Phase.OFF)
            "armed" -> WindowStripState(Phase.STARTING, reserved)
            "no_panel", "waiting_panel", "panel_lost" -> WindowStripState(Phase.WAITING_PANEL)
            "applied" -> WindowStripState(Phase.CHECKING, reserved)
            // A confirmed strip always has a width; without one the answer is not what it claims to be.
            "verified" -> if (reserved > 0) WindowStripState(Phase.ACTIVE, reserved) else unknown
            "other_user" -> WindowStripState(Phase.OTHER_USER)
            "blocked" -> WindowStripState(Phase.BLOCKED)
            "rollback_failed" -> WindowStripState(Phase.NOT_UNDONE)
            in unsupported -> WindowStripState(Phase.UNSUPPORTED, code = insets)
            in failed -> WindowStripState(Phase.FAILED, code = insets)
            else -> unknown
        }
    }

    private const val MAX_STRIP_PX = 600
}
