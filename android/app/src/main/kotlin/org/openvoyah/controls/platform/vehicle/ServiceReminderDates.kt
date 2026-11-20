package org.openvoyah.controls.platform.vehicle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.time.LocalDate
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import org.openvoyah.controls.diagnostics.ControlsLog

/** Reads the local date on subscription and when Android reports time or date changes. */
internal fun serviceReminderDates(context: Context): Flow<LocalDate> {
    val app = context.applicationContext
    return callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(LocalDate.now())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(LocalDate.now())
        awaitClose { app.unregisterReceiver(receiver) }
    }.conflate().catch { error ->
        if (error !is SecurityException) throw error
        ControlsLog.warn("service_reminder_date", "outcome", "updates_unavailable")
        emit(LocalDate.now())
    }.distinctUntilChanged()
}
