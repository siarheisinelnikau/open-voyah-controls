package org.openvoyah.controls.platform.vehicle.comfort

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

internal data class AmbientLightReading(val lux: Float, val receivedAtNanos: Long)

/** Shared Android illuminance sensor. A new interactive session requires a new sample. */
internal object AmbientLight {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var shared: StateFlow<AmbientLightReading?>? = null

    fun available(context: Context): Boolean = lightSensor(context) != null

    @Synchronized fun readings(context: Context): StateFlow<AmbientLightReading?> = shared ?: observe(context.applicationContext)
        .stateIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), null)
        .also { shared = it }

    private fun lightSensor(context: Context): Sensor? = context.getSystemService(SensorManager::class.java)
        ?.getDefaultSensor(Sensor.TYPE_LIGHT)

    private fun observe(app: Context) = callbackFlow<AmbientLightReading?> {
        val manager = app.getSystemService(SensorManager::class.java)
        val sensor = lightSensor(app)
        val power = app.getSystemService(PowerManager::class.java)
        if (manager == null || sensor == null || power == null) {
            close()
            return@callbackFlow
        }
        var listener: SensorEventListener? = null

        fun unregister() {
            val previous = listener
            listener = null
            if (previous != null) manager.unregisterListener(previous, sensor)
            trySend(null)
        }

        fun register() {
            if (!power.isInteractive || listener != null) return
            val started = SystemClock.elapsedRealtimeNanos()
            val next = object : SensorEventListener {
                private var lastTimestamp = Long.MIN_VALUE

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

                override fun onSensorChanged(event: SensorEvent) {
                    if (listener !== this || !power.isInteractive || event.sensor != sensor) return
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (event.timestamp !in started..now || event.timestamp <= lastTimestamp) return
                    lastTimestamp = event.timestamp
                    val value = event.values.firstOrNull()
                    trySend(value?.takeIf { event.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE &&
                        it.isFinite() && it >= 0f && it <= sensor.maximumRange }?.let { AmbientLightReading(it, now) })
                }
            }
            listener = next
            if (!manager.registerListener(next, sensor, SensorManager.SENSOR_DELAY_NORMAL)) unregister()
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> unregister()
                    Intent.ACTION_SCREEN_ON -> register()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            register()
            awaitClose()
        } finally {
            unregister()
            app.unregisterReceiver(receiver)
        }
    }.conflate().flowOn(Dispatchers.Main.immediate)
}
