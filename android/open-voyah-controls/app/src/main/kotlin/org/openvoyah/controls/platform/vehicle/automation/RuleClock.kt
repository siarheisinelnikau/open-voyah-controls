package org.openvoyah.controls.platform.vehicle.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import org.openvoyah.controls.domain.rules.RuleReading

internal fun observeRuleClock(context: Context) = callbackFlow<RuleReading?> {
    val power = context.getSystemService(PowerManager::class.java)
    var epoch = SystemClock.elapsedRealtimeNanos()

    fun publish() {
        val now = SystemClock.elapsedRealtimeNanos()
        val current = LocalDateTime.now()
        trySend(current.takeIf { power?.isInteractive == true && it.year >= 2020 }?.let {
            RuleReading((it.hour * 60 + it.minute).toDouble(), now, "local_clock:$epoch")
        })
    }

    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_TIME_TICK)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
    }
    val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_TIME_TICK) epoch = SystemClock.elapsedRealtimeNanos()
            publish()
        }
    }
    ContextCompat.registerReceiver(context, changes, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    try {
        publish()
        awaitClose()
    } finally {
        context.unregisterReceiver(changes)
    }
}
