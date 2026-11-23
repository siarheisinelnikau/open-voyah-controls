package org.openvoyah.controls.platform.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.openvoyah.controls.diagnostics.ControlsLog

/** Places a diagnostic report on the clipboard. The report text is never written to the log. */
internal fun copyReportToClipboard(context: Context, label: String, report: String): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    return try {
        clipboard.setPrimaryClip(ClipData.newPlainText(label, report))
        true
    } catch (failure: RuntimeException) {
        ControlsLog.warn("report_clipboard", "outcome", "copy_failed", "failure", failure)
        false
    }
}
