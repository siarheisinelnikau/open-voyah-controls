package org.openvoyah.controls.domain.vehicle.automation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/** Preserve the evidence but prevent an expired value from being presented or used as current. */
internal fun VehicleTelemetry.expireVehicleSignals(
    nowElapsedNanos: Long,
    maxAgeMillis: Long = 5_000,
): VehicleTelemetry {
    require(maxAgeMillis in 0..60_000)
    val expired = buildSet {
        if (gear?.isFreshFor(this@expireVehicleSignals, nowElapsedNanos, maxAgeMillis) != true) add(VehicleSignalKind.GEAR)
        if (speedKph?.isFreshFor(this@expireVehicleSignals, nowElapsedNanos, maxAgeMillis) != true) add(VehicleSignalKind.SPEED)
        if (outsideCelsius?.isFreshFor(this@expireVehicleSignals, nowElapsedNanos, maxAgeMillis) != true)
            add(VehicleSignalKind.OUTSIDE_TEMPERATURE)
        if (drivingMode?.isFreshFor(this@expireVehicleSignals, nowElapsedNanos, maxAgeMillis) != true)
            add(VehicleSignalKind.DRIVING_MODE)
        // A group loss is conservative: a newer door must not make an older door appear current.
        // A partial fresh map remains usable for an explicit ANY_OPEN condition.
        if (doors.isEmpty() || doors.values.any { !it.isFreshFor(this@expireVehicleSignals, nowElapsedNanos, maxAgeMillis) })
            add(VehicleSignalKind.DOORS)
    }
    val unavailable = unavailable + expired
    val gearExpiredByAge = gear?.let { sample ->
        (VehicleSignalKind.GEAR !in this.unavailable || this.gearExpiredByAge) &&
            sample.isFreshFor(this, sample.observedAtElapsedNanos, maxAgeMillis = 0) &&
            nowElapsedNanos >= sample.observedAtElapsedNanos &&
            nowElapsedNanos - sample.observedAtElapsedNanos > maxAgeMillis * 1_000_000
    } == true
    return if (unavailable == this.unavailable && gearExpiredByAge == this.gearExpiredByAge) this
        else copy(unavailable = unavailable, gearExpiredByAge = gearExpiredByAge)
}

/**
 * Own only the finite deadlines of the current snapshot. A new event cancels its old deadline.
 * There is one active delay, no polling, and an unsupported/empty source schedules no work.
 * The source must emit its loss event when closing a confirmation window, as the native reader does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<VehicleTelemetry>.withVehicleSignalExpiry(
    nowElapsedNanos: () -> Long,
    maxAgeMillis: Long = 5_000,
    awaitDeadline: suspend (elapsedNanos: Long) -> Unit = { deadline -> awaitElapsedDeadline(deadline, nowElapsedNanos) },
): Flow<VehicleTelemetry> {
    require(maxAgeMillis in 0..60_000)
    return transformLatest { original ->
        val capturedAt = nowElapsedNanos()
        var current = original.expireVehicleSignals(capturedAt, maxAgeMillis)
        emit(current)
        // At most eight existing samples: gear, speed, temperature, mode and four doors.
        // Sorting/deduplication combines simultaneous expiry into one emission.
        for (deadline in current.finiteExpiryDeadlines(capturedAt, maxAgeMillis)) {
            if (current.unavailable.containsAll(VehicleSignalKind.entries)) break
            awaitDeadline(deadline)
            val expired = original.expireVehicleSignals(nowElapsedNanos(), maxAgeMillis)
            if (expired != current) {
                current = expired
                emit(expired)
            }
        }
    }.distinctUntilChanged()
}

private fun VehicleTelemetry.finiteExpiryDeadlines(nowElapsedNanos: Long, maxAgeMillis: Long): List<Long> {
    val age = maxAgeMillis * 1_000_000
    fun deadline(kind: VehicleSignalKind, sample: VehicleSignal<*>?): Long? {
        if (kind in unavailable || sample?.isFreshFor(this, nowElapsedNanos, maxAgeMillis) != true) return null
        val observed = sample.observedAtElapsedNanos
        // An uptime at the end of Long's range cannot represent a later deadline.
        if (observed > Long.MAX_VALUE - age - 1) return null
        return observed + age + 1 // isFreshFor includes the exact age boundary.
    }
    return buildList {
        deadline(VehicleSignalKind.GEAR, gear)?.let(::add)
        deadline(VehicleSignalKind.SPEED, speedKph)?.let(::add)
        deadline(VehicleSignalKind.OUTSIDE_TEMPERATURE, outsideCelsius)?.let(::add)
        deadline(VehicleSignalKind.DRIVING_MODE, drivingMode)?.let(::add)
        for (door in doors.values) deadline(VehicleSignalKind.DOORS, door)?.let(::add)
    }.distinct().sorted()
}

private suspend fun awaitElapsedDeadline(deadline: Long, nowElapsedNanos: () -> Long) {
    val now = nowElapsedNanos()
    if (now >= deadline) return
    val remaining = deadline - now
    // Coroutine delay waits at least this duration; round up to Android's millisecond precision.
    delay((remaining / 1_000_000 + if (remaining % 1_000_000 == 0L) 0 else 1).coerceAtLeast(1))
}
