package org.openvoyah.controls.presentation.vehicle.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoorState
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignal
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignalKind
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout
import org.openvoyah.controls.presentation.vehicle.comfort.VehicleIgnitionStatus

/** Read-only cockpit summary. Missing, unavailable and never-observed values are never filled in. */
@Composable
internal fun VehicleStatusSection(telemetry: VehicleTelemetry?, modifier: Modifier = Modifier) {
    val layout = LocalCockpitLayout.current
    val gear = telemetry?.freshSignal(VehicleSignalKind.GEAR, telemetry.gear)
    val speed = telemetry?.freshSignal(VehicleSignalKind.SPEED, telemetry.speedKph)
    val outside = telemetry?.freshSignal(VehicleSignalKind.OUTSIDE_TEMPERATURE, telemetry.outsideCelsius)
    val mode = telemetry?.freshSignal(VehicleSignalKind.DRIVING_MODE, telemetry.drivingMode)
    val doors = telemetry?.takeIf { VehicleSignalKind.DOORS !in it.unavailable }?.doors
    FlowRow(modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(layout.spacing(18.dp)),
        verticalArrangement = Arrangement.spacedBy(layout.spacing(6.dp))) {
        Text(text(R.string.vehicle_status_title), style = MaterialTheme.typography.labelLarge)
        listOf(
            text(R.string.vehicle_status_gear) to gearLabel(gear),
            text(R.string.vehicle_status_speed) to speedLabel(speed),
            text(R.string.vehicle_status_outside) to outsideLabel(outside),
            text(R.string.vehicle_status_doors) to doorsLabel(doors),
        ).forEach { (label, value) ->
            Text("$label: $value", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        VehicleIgnitionStatus()
        val latest = listOfNotNull(gear?.receivedAtMillis, speed?.receivedAtMillis, outside?.receivedAtMillis,
            mode?.receivedAtMillis, doors?.values?.maxOfOrNull { it.receivedAtMillis }).maxOrNull()
        if (latest != null) Text(text(R.string.vehicle_status_updated).format(time(latest)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun gearLabel(signal: VehicleSignal<VehicleGear>?): String {
    val gear = signal?.value ?: return text(R.string.vehicle_status_waiting)
    return text(when (gear) {
        VehicleGear.PARK -> R.string.rules_park
        VehicleGear.REVERSE -> R.string.rules_reverse
        VehicleGear.NEUTRAL -> R.string.rules_neutral
        VehicleGear.DRIVE -> R.string.rules_drive
    })
}

private fun speedLabel(signal: VehicleSignal<Float>?): String {
    val speed = signal?.value?.takeIf { it.isFinite() && it >= 0f } ?: return text(R.string.vehicle_status_waiting)
    return text(R.string.vehicle_status_speed_value).format(speed.roundToInt())
}

private fun outsideLabel(signal: VehicleSignal<Float>?): String {
    val celsius = signal?.value?.takeIf { it.isFinite() } ?: return text(R.string.vehicle_status_waiting)
    return text(R.string.vehicle_status_outside_value).format(celsius.roundToInt())
}

private fun doorsLabel(doors: Map<VehicleDoor, VehicleSignal<VehicleDoorState>>?): String {
    val known = VehicleDoor.entries.mapNotNull { doors?.get(it)?.value }
    if (known.isEmpty()) return text(R.string.vehicle_status_waiting)
    val open = known.count { it == VehicleDoorState.OPEN }
    return when {
        open > 0 -> text(R.string.vehicle_status_doors_open).format(open)
        known.size == VehicleDoor.entries.size -> text(R.string.vehicle_status_doors_closed)
        else -> text(R.string.vehicle_status_doors_partial).format(known.size, VehicleDoor.entries.size)
    }
}

private fun <T> VehicleTelemetry.freshSignal(kind: VehicleSignalKind, signal: VehicleSignal<T>?): VehicleSignal<T>? =
    signal?.takeIf { kind !in unavailable }

private fun time(millis: Long): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    .withLocale(Locale.forLanguageTag(UiText.language())).withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(millis))

private fun text(id: Int): String = UiText.get(id)
