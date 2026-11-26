package org.openvoyah.controls.platform.trips

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.platform.vehicle.automation.VehicleWorkflowRuntime
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsRoute

/** One explicit, non-restored capture for an active trip. Uses the GPS provider and never a network provider. */
internal class TripRouteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = VehicleWorkflowRuntime.get(this).trips
    private var token: String? = null
    private var capture: Job? = null
    private val manager by lazy { getSystemService(LocationManager::class.java) }
    private var openedAtNanos = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val active = token ?: return
            if (location.provider != LocationManager.GPS_PROVIDER || location.elapsedRealtimeNanos < openedAtNanos ||
                !location.hasAccuracy() || location.accuracy !in 0f..50f) return
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                end(TripGpsState.UNAVAILABLE)
                return
            }
            runtime.recordGps(active, location.latitude, location.longitude, location.speed.takeIf { location.hasSpeed() }?.toDouble(),
                location.elapsedRealtimeNanos)
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) end(TripGpsState.UNAVAILABLE)
        }

        override fun onProviderEnabled(provider: String) = Unit

        @Deprecated("Compatibility callback for Android 9")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            end(TripGpsState.OFF)
            return START_NOT_STICKY
        }
        val requested = intent?.getStringExtra(TOKEN)
        if (requested == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (requested == token) return START_NOT_STICKY
        if (token != null) stopCapture()
        token = requested
        try {
            startForeground(NOTIFICATION_ID, notification())
        } catch (_: RuntimeException) {
            end(TripGpsState.UNAVAILABLE)
            return START_NOT_STICKY
        }
        capture = scope.launch {
            val allowed = runtime.routeAllowed(requested)
            if (!allowed.first() || !startCapture()) {
                end(TripGpsState.UNAVAILABLE)
                return@launch
            }
            val timer = launch {
                delay(MAX_SESSION_MILLIS)
                end(TripGpsState.LIMIT_REACHED)
            }
            try {
                allowed.first { !it }
                stopSelf()
            } finally {
                timer.cancel()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(): Boolean = try {
        val gps = manager
        if (gps == null || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            !gps.isProviderEnabled(LocationManager.GPS_PROVIDER)) false
        else {
            openedAtNanos = SystemClock.elapsedRealtimeNanos()
            gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 0f, listener, Looper.getMainLooper())
            true
        }
    } catch (_: RuntimeException) {
        false
    }

    private fun end(outcome: TripGpsState) {
        token?.let { runtime.stopGps(it, outcome) }
        stopCapture()
        stopSelf()
    }

    private fun stopCapture() {
        capture?.cancel()
        capture = null
        runCatching { manager?.removeUpdates(listener) }
        token = null
    }

    override fun onDestroy() {
        token?.let { runtime.stopGps(it) }
        stopCapture()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.trip_gps_title), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, ControlsNavigation.intent(this, ControlsRoute.TRIPS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 0, Intent(this, TripRouteService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_controls_mark)
            .setContentTitle(getString(R.string.trip_gps_title))
            .setContentText(getString(R.string.trip_gps_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.trip_gps_stop), stop).build())
            .build()
    }

    companion object {
        fun start(context: Context, token: String) {
            context.startForegroundService(Intent(context, TripRouteService::class.java).putExtra(TOKEN, token))
        }

        private const val NOTIFICATION_ID = 441
        private const val CHANNEL = "trip_gps"
        private const val TOKEN = "trip_route_token"
        private const val STOP = "org.openvoyah.controls.action.STOP_TRIP_ROUTE"
        private const val MAX_SESSION_MILLIS = 6 * 60 * 60 * 1_000L
    }
}
