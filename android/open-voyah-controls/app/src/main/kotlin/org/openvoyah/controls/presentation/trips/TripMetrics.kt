package org.openvoyah.controls.presentation.trips

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.openvoyah.controls.R
import org.openvoyah.controls.domain.trips.TripWheel
import org.openvoyah.controls.platform.trips.TripJournalState

internal enum class TripMetric(val title: Int) {
    CURRENT(R.string.trip_current),
    LEVELS(R.string.trip_levels),
    USAGE(R.string.trip_usage_last),
    TYRES(R.string.trip_tyres),
    ODOMETER(R.string.trip_odometer),
    SUSPENSION(R.string.trip_suspension),
}

@Composable
internal fun metricText(metric: TripMetric, state: TripJournalState): Pair<String, String?> {
    val unknown = stringResource(R.string.trip_unknown)
    val live = state.live
    return when (metric) {
        TripMetric.CURRENT -> state.active?.let { tripDuration(it.durationMillis) to tripDistance(it.distanceKm) }
            ?: (stringResource(R.string.trip_none) to null)
        TripMetric.LEVELS ->
            stringResource(R.string.trip_charge_range_one, live.levels.chargePercent?.let { tripAmount(R.string.trip_percent, it) } ?: unknown) to
                stringResource(R.string.trip_fuel_range_one, live.levels.fuelLitres?.let { tripAmount(R.string.trip_litres, it) } ?: unknown)
        TripMetric.USAGE ->
            (state.latestUsage.electricKwh?.let { tripAmount(R.string.trip_kwh, it) } ?: unknown) to
                (state.latestUsage.fuelLitres?.let { tripAmount(R.string.trip_litres, it) } ?: unknown)
        TripMetric.TYRES -> {
            if (live.tyreKpa.isEmpty()) return unknown to null
            val values = TripWheel.entries.map { wheel ->
                live.tyreKpa[wheel]?.let { tripAmount(R.string.trip_kpa, it) } ?: stringResource(R.string.trip_missing_short)
            }
            stringResource(R.string.trip_front_wheels, values[0], values[1]) to
                stringResource(R.string.trip_rear_wheels, values[2], values[3])
        }
        TripMetric.ODOMETER -> tripDistance(live.levels.odometerKm) to null
        TripMetric.SUSPENSION -> {
            if (live.suspensionMm.isEmpty()) return unknown to null
            val values = TripWheel.entries.map { wheel ->
                live.suspensionMm[wheel]?.let { stringResource(R.string.trip_mm, it) } ?: stringResource(R.string.trip_missing_short)
            }
            stringResource(R.string.trip_front_wheels, values[0], values[1]) to
                stringResource(R.string.trip_rear_wheels, values[2], values[3])
        }
    }
}

@Composable
internal fun tripDistance(km: Double?): String =
    km?.let { tripAmount(R.string.trip_km, it) } ?: stringResource(R.string.trip_unknown)

@Composable
internal fun tripAmount(format: Int, value: Double): String = stringResource(format, value)

@Composable
internal fun tripDuration(millis: Long): String {
    val minutes = millis / 60_000
    return if (minutes >= 60) stringResource(R.string.trip_hours_and_minutes, minutes / 60, minutes % 60)
    else stringResource(R.string.trip_minutes_and_seconds, minutes, millis / 1_000 % 60)
}
