package org.openvoyah.controls.data.frida

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.openvoyah.controls.domain.display.WindowProfileDelivery
import org.openvoyah.controls.data.frida.WindowDiagnostics.Outcome as DiagnosticOutcome
import org.openvoyah.controls.domain.display.WindowProfileDelivery.Outcome as DeliveryOutcome

internal class WindowProfileReceipt private constructor(
    val delivery: WindowProfileDelivery,
    val diagnosticOutcome: DiagnosticOutcome,
) {
    companion object {
        private val invalid = WindowProfileReceipt(DeliveryOutcome.UNCONFIRMED, DiagnosticOutcome.INVALID_REPLY)

        @JvmStatic
        fun parse(raw: String): WindowProfileReceipt {
            return try {
                val tokens = JSONTokener(raw)
                val reply = tokens.nextValue() as? JSONObject ?: return invalid
                if (tokens.nextClean() != '\u0000' || tokens.more()) return invalid
                decodeFields(reply.opt("status"), reply.opt("width"), reply.opt("dpi"), reply.opt("left"))
            } catch (_: JSONException) {
                invalid
            }
        }

        fun decodeFields(status: Any?, width: Any? = null, dpi: Any? = null, left: Any? = null): WindowProfileReceipt =
            when (status) {
                "applied" -> if (width is Int && width > 0 && dpi is Int && dpi > 0 && left is Int && left >= 0) {
                    WindowProfileReceipt(WindowProfileDelivery.Applied(width, dpi, left), DiagnosticOutcome.APPLIED)
                } else invalid
                "density_only" -> if (dpi is Int && dpi > 0) {
                    WindowProfileReceipt(WindowProfileDelivery.DensityOnly(dpi), DiagnosticOutcome.DENSITY_ONLY)
                } else invalid
                "no_task" -> WindowProfileReceipt(DeliveryOutcome.NEXT_LAUNCH, DiagnosticOutcome.NO_TASK)
                "unsupported_window" ->
                    WindowProfileReceipt(DeliveryOutcome.UNSUPPORTED_WINDOW, DiagnosticOutcome.UNSUPPORTED_WINDOW)
                "unsupported_task" ->
                    WindowProfileReceipt(DeliveryOutcome.UNSUPPORTED_TASK, DiagnosticOutcome.UNSUPPORTED_TASK)
                "no_display" -> WindowProfileReceipt(DeliveryOutcome.UNSUPPORTED_TASK, DiagnosticOutcome.NO_DISPLAY)
                "no_space" -> WindowProfileReceipt(DeliveryOutcome.NO_SPACE, DiagnosticOutcome.NO_SPACE)
                "density_rejected" -> WindowProfileReceipt(DeliveryOutcome.FAILED, DiagnosticOutcome.DENSITY_REJECTED)
                "placement_changed" -> WindowProfileReceipt(DeliveryOutcome.FAILED, DiagnosticOutcome.PLACEMENT_CHANGED)
                "failed" -> WindowProfileReceipt(DeliveryOutcome.FAILED, DiagnosticOutcome.FAILED)
                "no_profile" -> WindowProfileReceipt(DeliveryOutcome.UNCONFIRMED, DiagnosticOutcome.NO_PROFILE)
                "rollback_failed" -> WindowProfileReceipt(DeliveryOutcome.UNCONFIRMED, DiagnosticOutcome.ROLLBACK_FAILED)
                else -> invalid
            }
    }
}
