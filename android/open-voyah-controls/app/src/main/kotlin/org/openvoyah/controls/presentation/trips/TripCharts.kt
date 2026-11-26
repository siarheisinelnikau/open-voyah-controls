package org.openvoyah.controls.presentation.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import org.openvoyah.controls.R
import org.openvoyah.controls.domain.trips.TripRoutePoint
import org.openvoyah.controls.domain.trips.TripUsagePoint

@Composable
internal fun TripUsageCharts(usage: List<TripUsagePoint>) {
    val electricity = remember(usage) { netUsage(usage, TripUsagePoint::batteryKwh) }
    val fuel = remember(usage) { netUsage(usage, TripUsagePoint::fuelLitres, fuel = true) }
    if (electricity.isEmpty() && fuel.isEmpty()) Text(stringResource(R.string.trip_usage_empty))
    if (electricity.isNotEmpty()) {
        UsageChart(stringResource(R.string.trip_electric_usage), electricity, R.string.trip_kwh)
    }
    if (fuel.isNotEmpty()) UsageChart(stringResource(R.string.trip_fuel_usage), fuel, R.string.trip_litres)
}

private data class UsageDot(val time: Long, val value: Double, val startsSegment: Boolean)

/** Segment after unavailable readings or a refill. Between gaps, integrate only observed changes. */
private fun netUsage(
    points: List<TripUsagePoint>,
    measurement: (TripUsagePoint) -> Double?,
    fuel: Boolean = false,
): List<UsageDot> {
    var previous: Double? = null
    var total = 0.0
    val result = buildList {
        for (point in points) {
            val before = previous
            val current = measurement(point)
            previous = current
            if (current == null) continue
            val change = before?.minus(current)
            val starts = change == null || fuel && change < -0.5
            if (!starts) total += if (fuel) change.coerceAtLeast(0.0) else change
            add(UsageDot(point.elapsedMillis, total, starts))
        }
    }
    return result.takeIf { dots -> dots.any { !it.startsSegment } }.orEmpty()
}

@Composable
private fun UsageChart(title: String, dots: List<UsageDot>, amountFormat: Int) {
    val colors = MaterialTheme.colorScheme
    val first = dots.first().time
    val interval = (dots.last().time - first).coerceAtLeast(1)
    val low = minOf(0.0, dots.minOf { it.value })
    val high = maxOf(0.0, dots.maxOf { it.value })
    val range = (high - low).coerceAtLeast(0.01)
    val summary = stringResource(R.string.trip_chart_range, tripAmount(amountFormat, low), tripAmount(amountFormat, high),
        tripDuration(interval))
    Text(title, style = MaterialTheme.typography.titleMedium)
    Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = summary }) {
        val pad = 4.dp.toPx()
        val width = (size.width - 2 * pad).coerceAtLeast(1f)
        val height = (size.height - 2 * pad).coerceAtLeast(1f)
        val zero = size.height - pad - ((-low) / range * height).toFloat()
        drawLine(colors.outlineVariant, Offset(pad, zero), Offset(size.width - pad, zero), 1.dp.toPx())
        var last = Offset.Zero
        for (dot in dots) {
            val point = Offset(pad + ((dot.time - first).toDouble() / interval * width).toFloat(),
                size.height - pad - ((dot.value - low) / range * height).toFloat())
            if (!dot.startsSegment) drawLine(colors.primary, last, point, 2.dp.toPx(), StrokeCap.Round)
            last = point
        }
    }
    Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
}

@Composable
internal fun TripRouteChart(route: List<TripRoutePoint>) {
    if (route.isEmpty()) {
        Text(stringResource(R.string.trip_route_empty))
        return
    }
    val colors = MaterialTheme.colorScheme
    val projected = remember(route) { project(route) }
    val speedColors = listOf(colors.secondary, colors.primary, colors.tertiary, colors.outline)
    val label = stringResource(R.string.trip_route_chart)
    Canvas(Modifier.fillMaxWidth().height(224.dp).semantics { contentDescription = label }) {
        val pad = 10.dp.toPx()
        val width = projected.maxOf { it.x } - projected.minOf { it.x }
        val height = projected.maxOf { it.y } - projected.minOf { it.y }
        val scale = minOf(
            (size.width - 2 * pad).coerceAtLeast(1f) / width.coerceAtLeast(0.00001f),
            (size.height - 2 * pad).coerceAtLeast(1f) / height.coerceAtLeast(0.00001f),
        )
        val left = projected.minOf { it.x } + width / 2
        val bottom = projected.minOf { it.y } + height / 2
        var previous = Offset.Zero
        for ((index, point) in projected.withIndex()) {
            val location = Offset(size.width / 2 + (point.x - left) * scale, size.height / 2 - (point.y - bottom) * scale)
            val sample = route[index]
            val color = speedColors[speedBand(sample.speedKph)]
            if (index == 0 || sample.startsSegment) drawCircle(color, 2.dp.toPx(), location)
            else drawLine(color, previous, location, 3.dp.toPx(), StrokeCap.Round)
            previous = location
        }
    }
    listOf(R.string.trip_speed_slow, R.string.trip_speed_medium, R.string.trip_speed_fast, R.string.trip_speed_unknown)
        .forEachIndexed { index, res -> SpeedLegend(stringResource(res), speedColors[index]) }
}

private fun speedBand(kph: Double?): Int = when {
    kph == null -> 3
    kph < 30 -> 0
    kph < 70 -> 1
    else -> 2
}

private fun project(route: List<TripRoutePoint>): List<Offset> {
    val origin = route.first()
    val longitudeScale = cos(Math.toRadians(route.map { it.latitude }.average())).coerceAtLeast(0.001)
    return route.map { point ->
        val longitude = (point.longitude - origin.longitude + 540) % 360 - 180
        Offset((longitude * longitudeScale).toFloat(), (point.latitude - origin.latitude).toFloat())
    }
}

@Composable
private fun SpeedLegend(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
}
