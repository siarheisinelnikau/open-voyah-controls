package org.openvoyah.controls.platform.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log

internal fun copyHeadlightReport(context: Context, label: String, report: String): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    return try {
        clipboard.setPrimaryClip(ClipData.newPlainText(label, report))
        true
    } catch (failure: RuntimeException) {
        Log.w("OpenVoyahControls", "event=headlight_report outcome=copy_failed failure=${failure.javaClass.simpleName}")
        false
    }
}
