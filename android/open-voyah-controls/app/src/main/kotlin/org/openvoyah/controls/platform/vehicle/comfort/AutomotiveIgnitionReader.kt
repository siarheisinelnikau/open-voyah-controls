package org.openvoyah.controls.platform.vehicle.comfort

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.ArraySet
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import org.openvoyah.controls.domain.vehicle.HeadlightConfirmationWindow
import org.openvoyah.controls.domain.vehicle.comfort.IgnitionSamples
import org.openvoyah.controls.domain.vehicle.comfort.IgnitionStatus
import org.openvoyah.controls.platform.vehicle.AutomotiveHeadlightReader

internal fun observeIgnitionStatus(app: Context): Flow<IgnitionStatus> = when {
    Build.VERSION.SDK_INT < 29 || !app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) ->
        flowOf(IgnitionStatus.Unavailable)
    app.checkSelfPermission("android.car.permission.CAR_POWERTRAIN") != PackageManager.PERMISSION_GRANTED ->
        flowOf(IgnitionStatus.PermissionDenied)
    else -> AutomotiveIgnitionReader.observe(app)
}

@RequiresApi(29)
@OptIn(ExperimentalCoroutinesApi::class)
private object AutomotiveIgnitionReader {
    private const val PROPERTY = VehiclePropertyIds.IGNITION_STATE

    fun observe(app: Context): Flow<IgnitionStatus> = AutomotiveHeadlightReader.connections(app).flatMapLatest { connection ->
        if (connection?.window?.isOpen != true) return@flatMapLatest flowOf(IgnitionStatus.Waiting)
        val manager = connection.car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        if (manager == null) flowOf(IgnitionStatus.Waiting)
        else observe(manager, connection.window)
    }.onStart { emit(IgnitionStatus.Waiting) }.catch { failure ->
        if (failure is CancellationException || (failure !is RuntimeException && failure !is LinkageError)) throw failure
        emit(if (failure is SecurityException) IgnitionStatus.PermissionDenied else IgnitionStatus.Unavailable)
    }.flowOn(Dispatchers.IO)

    @Suppress("DEPRECATION")
    private fun observe(manager: CarPropertyManager, window: HeadlightConfirmationWindow): Flow<IgnitionStatus> = callbackFlow {
        val property = manager.getPropertyList(ArraySet<Int>(1).apply { add(PROPERTY) }).singleOrNull { it.propertyId == PROPERTY }
        if (property == null || property.access != CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ ||
            property.propertyType != Int::class.javaObjectType || !property.isGlobalProperty ||
            property.changeMode != CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE) {
            trySend(IgnitionStatus.Unavailable)
            close()
            return@callbackFlow
        }
        trySend(IgnitionStatus.Waiting)
        val samples = IgnitionSamples(SystemClock.elapsedRealtimeNanos())
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(event: CarPropertyValue<*>) {
                if (!window.isOpen || event.propertyId != PROPERTY || event.areaId != 0) return
                val value = if (event.status == CarPropertyValue.STATUS_AVAILABLE) event.value as? Int else null
                synchronized(samples) {
                    samples.accept(value, event.timestamp, SystemClock.elapsedRealtimeNanos())?.let { trySend(it) }
                }
            }

            override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
        }
        try {
            if (!window.isOpen || !manager.registerCallback(callback, PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)) {
                trySend(IgnitionStatus.Unavailable)
                close()
            }
            awaitClose()
        } finally {
            runCatching { manager.unregisterCallback(callback, PROPERTY) }
        }
    }
}
