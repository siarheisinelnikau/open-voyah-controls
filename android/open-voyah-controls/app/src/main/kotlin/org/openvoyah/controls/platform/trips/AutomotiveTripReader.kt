package org.openvoyah.controls.platform.trips

import android.car.Car
import android.car.VehicleAreaWheel
import android.car.VehicleGear as CarGear
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
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import org.openvoyah.controls.domain.trips.TripLevels
import org.openvoyah.controls.domain.trips.TripReadings
import org.openvoyah.controls.domain.trips.TripWheel
import org.openvoyah.controls.domain.vehicle.HeadlightConfirmationWindow
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.platform.vehicle.AutomotiveHeadlightReader

internal object TripCarPermissions {
    const val ENERGY = "android.car.permission.CAR_ENERGY"
    const val MILEAGE = "android.car.permission.CAR_MILEAGE_3P"
    const val TYRES = "android.car.permission.CAR_TIRES_3P"

    fun requestable(context: Context): Array<String> = arrayOf(ENERGY, MILEAGE, TYRES).filter { name ->
        val info = runCatching { context.packageManager.getPermissionInfo(name, 0) }.getOrNull()
        info?.protection == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS &&
            context.checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()
}

internal fun observeAutomotiveTripReadings(context: Context): Flow<AutomotiveTripSnapshot> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
        AutomotiveTripReader.observe(context)
    } else flowOf(AutomotiveTripSnapshot())

internal data class AutomotiveTripValue(val value: Number, val timestampNanos: Long)

internal data class AutomotiveTripSnapshot(
    val session: String? = null,
    val window: HeadlightConfirmationWindow? = null,
    val values: Map<Pair<Int, Int>, AutomotiveTripValue> = emptyMap(),
) {
    fun readings(nowNanos: Long): TripReadings {
        if (session == null || window?.isOpen == false) return TripReadings()
        fun read(id: Int, area: Int = 0, retained: Boolean = false): AutomotiveTripValue? =
            values[id to area]?.takeIf { value ->
                value.timestampNanos in 1..nowNanos && (retained || nowNanos - value.timestampNanos <= LIVE_MAX_AGE_NANOS)
            }

        fun positive(id: Int, area: Int = 0, retained: Boolean = false) = read(id, area, retained)
            ?.value?.toDouble()?.takeIf { it.isFinite() && it >= 0 }

        val energy = read(VehiclePropertyIds.EV_BATTERY_LEVEL)
        val storedWh = energy?.value?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
        val capacityWh = positive(CURRENT_BATTERY_CAPACITY, retained = true)?.takeIf { it > 0 }
        val charge = if (storedWh != null && capacityWh != null && storedWh <= capacityWh) storedWh / capacityWh * 100 else null
        val gear = when (read(VehiclePropertyIds.GEAR_SELECTION, retained = true)?.value?.toInt()) {
            CarGear.GEAR_PARK -> VehicleGear.PARK
            CarGear.GEAR_REVERSE -> VehicleGear.REVERSE
            CarGear.GEAR_NEUTRAL -> VehicleGear.NEUTRAL
            CarGear.GEAR_DRIVE, CarGear.GEAR_FIRST, CarGear.GEAR_SECOND, CarGear.GEAR_THIRD,
            CarGear.GEAR_FOURTH, CarGear.GEAR_FIFTH, CarGear.GEAR_SIXTH,
            CarGear.GEAR_SEVENTH, CarGear.GEAR_EIGHTH, CarGear.GEAR_NINTH -> VehicleGear.DRIVE
            else -> null
        }
        return TripReadings(session, gear, TripLevels(
            odometerKm = positive(VehiclePropertyIds.PERF_ODOMETER),
            chargePercent = charge,
            batteryKwh = storedWh?.div(1_000),
            fuelLitres = positive(VehiclePropertyIds.FUEL_LEVEL)?.div(1_000),
        ), WHEELS.mapNotNull { (wheel, area) ->
            positive(VehiclePropertyIds.TIRE_PRESSURE, area)?.let { wheel to it }
        }.toMap(), WHEELS.mapNotNull { (wheel, area) ->
            read(PASSIVE_SUSPENSION_HEIGHT, area)?.value?.toInt()?.let { wheel to it }
        }.toMap(), chargeTimestampNanos = energy?.timestampNanos?.takeIf { charge != null })
    }

    private companion object {
        const val LIVE_MAX_AGE_NANOS = 6_000_000_000L
        val WHEELS = mapOf(
            TripWheel.FRONT_LEFT to VehicleAreaWheel.WHEEL_LEFT_FRONT,
            TripWheel.FRONT_RIGHT to VehicleAreaWheel.WHEEL_RIGHT_FRONT,
            TripWheel.REAR_LEFT to VehicleAreaWheel.WHEEL_LEFT_REAR,
            TripWheel.REAR_RIGHT to VehicleAreaWheel.WHEEL_RIGHT_REAR,
        )
    }
}

// Public VehiclePropertyIds values. An older car service may omit either property.
private const val CURRENT_BATTERY_CAPACITY = 0x1160030d
private const val PASSIVE_SUSPENSION_HEIGHT = 0x17400313

/** Read-only Android Automotive subscription. Android enforces the production permissions. */
@RequiresApi(Build.VERSION_CODES.Q)
@OptIn(ExperimentalCoroutinesApi::class)
private object AutomotiveTripReader {
    private data class Property(val id: Int, val type: Class<*>, val global: Boolean = true)

    private val properties = listOf(
        Property(VehiclePropertyIds.GEAR_SELECTION, Int::class.javaObjectType),
        Property(VehiclePropertyIds.PERF_ODOMETER, Float::class.javaObjectType),
        Property(VehiclePropertyIds.EV_BATTERY_LEVEL, Float::class.javaObjectType),
        Property(CURRENT_BATTERY_CAPACITY, Float::class.javaObjectType),
        Property(VehiclePropertyIds.FUEL_LEVEL, Float::class.javaObjectType),
        Property(VehiclePropertyIds.TIRE_PRESSURE, Float::class.javaObjectType, global = false),
        Property(PASSIVE_SUSPENSION_HEIGHT, Int::class.javaObjectType, global = false),
    ).associateBy { it.id }

    fun observe(context: Context): Flow<AutomotiveTripSnapshot> =
        AutomotiveHeadlightReader.connections(context).flatMapLatest { connection ->
            val manager = connection?.car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            if (connection == null || manager == null || !connection.window.isOpen) flowOf(AutomotiveTripSnapshot())
            else observe(manager, connection.window)
        }.catch { failure ->
            if (failure is CancellationException || (failure !is RuntimeException && failure !is LinkageError)) throw failure
            emit(AutomotiveTripSnapshot())
        }.flowOn(Dispatchers.IO)

    @Suppress("DEPRECATION")
    private fun observe(manager: CarPropertyManager, window: HeadlightConfirmationWindow): Flow<AutomotiveTripSnapshot> =
        callbackFlow {
            val configs = manager.getPropertyList(ArraySet<Int>().apply { addAll(properties.keys) })
                .filter { config ->
                    properties[config.propertyId]?.let {
                        it.type == config.propertyType && it.global == config.isGlobalProperty &&
                            config.access == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ
                    } ?: false
                }.associateBy { it.propertyId }
            val gate = Any()
            var active = true
            var snapshot = AutomotiveTripSnapshot(UUID.randomUUID().toString(), window)
            trySend(snapshot)
            val listener = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(event: CarPropertyValue<*>) {
                    val config = configs[event.propertyId] ?: return
                    if (config.isGlobalProperty && event.areaId != 0 ||
                        !config.isGlobalProperty && event.areaId !in config.areaIds) return
                    synchronized(gate) {
                        if (!active || !window.isOpen) return
                        val now = SystemClock.elapsedRealtimeNanos()
                        val value = (event.value as? Number)?.takeIf {
                            event.status == CarPropertyValue.STATUS_AVAILABLE && event.timestamp in 1..now &&
                                config.propertyType.isInstance(it) && it.toDouble().isFinite()
                        }
                        val key = event.propertyId to event.areaId
                        if (event.timestamp > 0 && event.timestamp < (snapshot.values[key]?.timestampNanos ?: 0)) return
                        val values = if (value == null) snapshot.values - key
                        else snapshot.values + (key to AutomotiveTripValue(value, event.timestamp))
                        snapshot = snapshot.copy(values = values)
                        trySend(snapshot)
                    }
                }

                // The legacy error callback describes failed writes. Read status is on the property event.
                override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
            }
            try {
                for (id in configs.keys) {
                    if (!window.isOpen) break
                    try {
                        manager.registerCallback(listener, id, 1f)
                    } catch (_: SecurityException) {
                        // A caller can see a supported config while its read permission is denied.
                    } catch (_: IllegalArgumentException) {
                        // Support can change after discovery.
                    }
                }
                awaitClose()
            } finally {
                synchronized(gate) { active = false }
                runCatching { manager.unregisterCallback(listener) }
            }
        }.buffer(Channel.CONFLATED)
}
