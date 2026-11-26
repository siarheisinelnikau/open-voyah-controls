package org.openvoyah.controls.platform.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.openvoyah.controls.MainActivity
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.rules.RuleHandoff

/** Bridges an already-authorized app action to the normal foreground routine runner. */
internal object RoutineActionLaunch {
    const val ACTION = "org.openvoyah.controls.action.RUN_SAVED_ROUTINE"
    private const val TOKEN = "org.openvoyah.controls.extra.SAVED_ROUTINE_HANDOFF"
    private val handoff = RuleHandoff<Receipt>()

    class Receipt(val routine: NamedRoutine, val stillAllowed: () -> Boolean)

    fun request(activity: Activity, receipt: Receipt): Boolean {
        if (activity.isFinishing || activity.isDestroyed || !receipt.stillAllowed()) return false
        val token = handoff.reserve(receipt, SystemClock.elapsedRealtimeNanos()) ?: return false
        return try {
            activity.startActivity(Intent(activity, MainActivity::class.java).setAction(ACTION).putExtra(TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            true
        } catch (_: RuntimeException) {
            handoff.discard(token)
            false
        }
    }

    fun claim(intent: Intent): Receipt? {
        if (intent.action != ACTION) return null
        val token = try { intent.getStringExtra(TOKEN) } catch (_: RuntimeException) { return null } ?: return null
        intent.removeExtra(TOKEN)
        return handoff.claim(token, SystemClock.elapsedRealtimeNanos()) { it.stillAllowed() }
    }
}
