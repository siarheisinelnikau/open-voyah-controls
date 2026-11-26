package org.openvoyah.controls.domain.trips

import java.util.UUID
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear

internal enum class TripWheel { FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT }

internal data class TripLevels(
    val odometerKm: Double? = null,
    val chargePercent: Double? = null,
    val batteryKwh: Double? = null,
    val fuelLitres: Double? = null,
)

internal data class TripReadings(
    val session: String? = null,
    val gear: VehicleGear? = null,
    val levels: TripLevels = TripLevels(),
    val tyreKpa: Map<TripWheel, Double> = emptyMap(),
    val suspensionMm: Map<TripWheel, Int> = emptyMap(),
    val chargeTimestampNanos: Long? = null,
)

internal data class RecordedTrip(
    val id: String,
    val startedAtMillis: Long,
    val durationMillis: Long = 0,
    val start: TripLevels,
    val latest: TripLevels = start,
    val distanceKm: Double? = null,
    val invalidOdometer: Boolean = false,
    val finishedAtMillis: Long? = null,
    val interrupted: Boolean = false,
    val manual: Boolean = false,
)

internal data class TripUsagePoint(
    val elapsedMillis: Long,
    val odometerKm: Double?,
    val batteryKwh: Double?,
    val fuelLitres: Double?,
)

internal data class TripRoutePoint(
    val latitude: Double,
    val longitude: Double,
    val speedKph: Double?,
    val startsSegment: Boolean,
)

internal data class TripUsage(val electricKwh: Double? = null, val fuelLitres: Double? = null)

internal data class TripUpdate(val trip: RecordedTrip, val usage: TripUsagePoint? = null)

/** All times between samples come from the monotonic clock of this collection period. */
internal class TripTracker(
    initial: RecordedTrip?,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    var active = initial
        private set
    private var previousAt: Long? = null
    private var session: String? = null
    private var needsChartGap = initial != null

    fun observe(readings: TripReadings, wallTime: Long, elapsedTime: Long): TripUpdate? {
        val current = active
        if (current == null) {
            return if (readings.gear == VehicleGear.DRIVE || readings.gear == VehicleGear.REVERSE) {
                start(readings, wallTime, elapsedTime, manual = false)
            } else null
        }
        if (readings.session == null || (!current.manual && readings.gear == null)) {
            invalidate()
            return null
        }
        if (!current.manual && readings.gear == VehicleGear.PARK) {
            return finish(readings, wallTime, elapsedTime)
        }
        return checkpoint(readings, elapsedTime)
    }

    fun start(readings: TripReadings, wallTime: Long, elapsedTime: Long, manual: Boolean): TripUpdate? {
        if (active != null) return null
        previousAt = elapsedTime
        session = readings.session
        needsChartGap = false
        val trip = RecordedTrip(newId(), wallTime, start = readings.levels, manual = manual)
        active = trip
        return TripUpdate(trip, trip.usagePoint())
    }

    fun finish(readings: TripReadings, wallTime: Long, elapsedTime: Long): TripUpdate? {
        val continued = checkpoint(readings, elapsedTime) ?: return null
        val trip = continued.trip.copy(
            finishedAtMillis = wallTime.coerceAtLeast(continued.trip.startedAtMillis),
            interrupted = continued.trip.interrupted || readings.session == null,
        )
        invalidate()
        active = null
        return TripUpdate(trip, continued.usage)
    }

    fun invalidate() {
        previousAt = null
        session = null
        needsChartGap = active != null
    }

    private fun checkpoint(readings: TripReadings, elapsedTime: Long): TripUpdate? {
        val current = active ?: return null
        val delta = previousAt?.let { elapsedTime - it }
        val continuous = delta != null && delta in 0..MAX_OBSERVATION_GAP_MILLIS && readings.session == session
        previousAt = elapsedTime
        session = readings.session
        val start = current.start.odometerKm
        val before = current.latest.odometerKm ?: current.distanceKm?.let { distance -> start?.plus(distance) }
        val now = readings.levels.odometerKm
        val invalidOdometer = current.invalidOdometer ||
            (before != null && now != null && now < before) ||
            (start != null && now != null && now < start)
        val trip = current.copy(
            durationMillis = current.durationMillis + (delta?.takeIf { continuous } ?: 0),
            latest = readings.levels,
            distanceKm = when {
                invalidOdometer || start == null -> null
                now == null -> current.distanceKm
                else -> now - start
            },
            invalidOdometer = invalidOdometer,
            interrupted = current.interrupted || !continuous,
        )
        active = trip
        val gap = needsChartGap || !continuous
        needsChartGap = false
        return TripUpdate(trip, if (gap) TripUsagePoint(trip.durationMillis, null, null, null) else trip.usagePoint())
    }

    private fun RecordedTrip.usagePoint() =
        TripUsagePoint(durationMillis, latest.odometerKm, latest.batteryKwh, latest.fuelLitres)

    private companion object {
        const val MAX_OBSERVATION_GAP_MILLIS = 15_000L
    }
}

/** Keeps only a continuous, measured odometer window. Its synthetic edge is bounded by close readings. */
internal class RollingTripUsage {
    private val samples = ArrayDeque<TripUsagePoint>()

    fun clear() = samples.clear()

    fun observe(point: TripUsagePoint): TripUsage {
        val odometer = point.odometerKm
        val previous = samples.lastOrNull()
        if (odometer == null || (previous != null && (odometer - checkNotNull(previous.odometerKm) !in 0.0..0.5 ||
                point.elapsedMillis - previous.elapsedMillis !in 1..15_000))) {
            clear()
        }
        if (odometer == null) return TripUsage()
        if (samples.size == MAX_SAMPLES) samples.removeFirst()
        if (samples.lastOrNull()?.odometerKm == odometer) samples.removeLast()
        samples.addLast(point)
        val boundary = odometer - WINDOW_KM
        while (samples.size >= 3 && checkNotNull(samples[1].odometerKm) <= boundary) samples.removeFirst()
        val lower = samples.first()
        val upper = samples.getOrNull(1) ?: return TripUsage()
        val lowerKm = checkNotNull(lower.odometerKm)
        val upperKm = checkNotNull(upper.odometerKm)
        if (lowerKm > boundary || upperKm < boundary || upperKm == lowerKm) return TripUsage()
        val fraction = (boundary - lowerKm) / (upperKm - lowerKm)
        fun usage(read: (TripUsagePoint) -> Double?): Double? {
            if (samples.any { read(it) == null }) return null
            val start = checkNotNull(read(lower))
            val end = checkNotNull(read(upper))
            return start + (end - start) * fraction - checkNotNull(read(point))
        }
        val fuel = if (samples.zipWithNext().any { (before, after) ->
                val earlier = before.fuelLitres
                val later = after.fuelLitres
                earlier != null && later != null && later - earlier > 0.5
            }) null else usage(TripUsagePoint::fuelLitres)
        return TripUsage(usage(TripUsagePoint::batteryKwh), fuel?.takeIf { it >= 0 })
    }

    private companion object {
        const val WINDOW_KM = 2.5
        const val MAX_SAMPLES = 1_200
    }
}

internal fun validRoutePoint(latitude: Double, longitude: Double, speedMps: Double?): Boolean =
    latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        (speedMps == null || speedMps in 0.0..100.0)
