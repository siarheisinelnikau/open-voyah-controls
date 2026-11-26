package org.openvoyah.controls.presentation.trips

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.data.trips.TripDetails
import org.openvoyah.controls.domain.trips.RecordedTrip
import org.openvoyah.controls.platform.trips.TripCarPermissions
import org.openvoyah.controls.platform.trips.TripGpsState
import org.openvoyah.controls.platform.trips.TripRouteService
import org.openvoyah.controls.platform.trips.TripRuntime
import org.openvoyah.controls.platform.vehicle.automation.VehicleWorkflowRuntime
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch

internal class TripsViewModel(val runtime: TripRuntime) : ViewModel() {
    fun startRoute(tripId: String, activity: ComponentActivity) {
        viewModelScope.launch {
            val token = withContext(NonCancellable) { runtime.prepareGps(tripId) } ?: return@launch
            if (!isActive || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                runtime.stopGps(token, TripGpsState.UNAVAILABLE)
                return@launch
            }
            try {
                TripRouteService.start(activity, token)
            } catch (_: RuntimeException) {
                runtime.stopGps(token, TripGpsState.UNAVAILABLE)
            }
        }
    }
}

@Composable
internal fun TripsDestination(activity: ComponentActivity, back: () -> Unit) {
    val model = remember(activity) {
        ViewModelProvider(activity, viewModelFactory {
            initializer { TripsViewModel(VehicleWorkflowRuntime.get(activity.applicationContext).trips) }
        })[TripsViewModel::class.java]
    }
    val runtime = model.runtime
    val state by runtime.state.collectAsStateWithLifecycle(initialValue = runtime.snapshot)
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = state.active?.takeIf { it.id == selectedId } ?: state.completed.firstOrNull { it.id == selectedId }
    var delete by remember { mutableStateOf(false) }
    var consentId by remember { mutableStateOf<String?>(null) }
    var permissionTripId by remember { mutableStateOf<String?>(null) }
    var preciseLocationDenied by remember { mutableStateOf(false) }
    var permissionResult by remember { mutableIntStateOf(0) }
    var count by rememberSaveable { mutableIntStateOf(10) }
    val availablePermissions = remember(activity, permissionResult) { TripCarPermissions.requestable(activity) }
    val locationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val id = permissionTripId
        permissionTripId = null
        if (id != null && activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            model.startRoute(id, activity)
        } else if (id != null) preciseLocationDenied = true
    }
    val carRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionResult++
        runtime.refreshPermissions()
    }

    if (selected != null) {
        BackHandler { selectedId = null }
        TripDetailsScreen(selected, state.detailsRevision, runtime) { selectedId = null }
        return
    }

    CockpitDetail(stringResource(R.string.trips_title), null, back) {
        if (!state.ready || state.failed) CockpitFeedback(
            stringResource(if (state.failed) R.string.trips_store_error else R.string.trips_loading), error = state.failed,
        )
        CockpitColumns(first = {
            CockpitSection(stringResource(R.string.trip_current), CockpitIcon.CAR) {
                val current = state.active
                if (current == null) {
                    Text(stringResource(R.string.trip_none))
                    CockpitAction(stringResource(R.string.trip_start), onClick = { runtime.startManually() }, enabled = state.ready)
                } else {
                    CockpitChoice(tripDuration(current.durationMillis), tripDistance(current.distanceKm)) { selectedId = current.id }
                    CockpitAction(stringResource(R.string.trip_finish), onClick = { runtime.finishManually() })
                    when (state.gps) {
                        TripGpsState.WAITING, TripGpsState.RECORDING -> {
                            CockpitSwitch(R.string.trip_gps_title, checked = true) { runtime.stopGps() }
                            CockpitFeedback(stringResource(if (state.gps == TripGpsState.WAITING) R.string.trip_gps_waiting
                                else R.string.trip_gps_recording))
                        }
                        else -> {
                            CockpitSwitch(R.string.trip_gps_title, checked = false) {
                                preciseLocationDenied = false
                                consentId = current.id
                            }
                            val feedback = when {
                                preciseLocationDenied -> R.string.trip_gps_precise_required
                                state.gps == TripGpsState.UNAVAILABLE -> R.string.trip_gps_unavailable
                                state.gps == TripGpsState.LIMIT_REACHED -> R.string.trip_gps_limit
                                else -> null
                            }
                            feedback?.let { CockpitFeedback(stringResource(it)) }
                        }
                    }
                }
                CockpitSwitch(R.string.trips_automatic, checked = state.automatic, enabled = state.ready) { runtime.setAutomatic(it) }
                if (state.automatic && state.live.gear == null) {
                    CockpitFeedback(stringResource(R.string.trips_waiting_gear))
                }
                if (state.automatic && !state.hasBackgroundLease) {
                    CockpitFeedback(stringResource(R.string.trips_foreground_only))
                }
            }
            CockpitSection(stringResource(R.string.trips_completed), CockpitIcon.APPS) {
                if (state.completed.isEmpty()) Text(stringResource(R.string.trips_empty))
                state.completed.take(count).forEach { trip ->
                    CockpitChoice(tripDate(trip.startedAtMillis),
                        stringResource(R.string.trip_summary, tripDuration(trip.durationMillis), tripDistance(trip.distanceKm))) {
                        selectedId = trip.id
                    }
                }
                if (state.completed.size > count) {
                    CockpitAction(stringResource(R.string.trips_show_more), { count += 10 })
                }
                if (state.completed.isNotEmpty()) {
                    CockpitAction(stringResource(R.string.trips_clear), { delete = true }, destructive = true)
                }
            }
        }, second = {
            CockpitSection(stringResource(R.string.trip_live), CockpitIcon.ENERGY) {
                if (availablePermissions.isNotEmpty()) {
                    CockpitChoice(R.string.trip_permissions, onClick = { carRequest.launch(availablePermissions) })
                }
                TripMetric.entries.filter { it != TripMetric.CURRENT }.forEach { metric ->
                    val (value, detail) = metricText(metric, state)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(metric.title), style = MaterialTheme.typography.titleMedium)
                        Text(value, style = MaterialTheme.typography.bodyLarge)
                        if (detail != null) {
                            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        })
    }

    if (delete) {
        CockpitAlertDialog(
            title = { Text(stringResource(R.string.trips_clear)) },
            onDismissRequest = { delete = false },
            text = { Text(stringResource(R.string.trips_clear_message)) },
            dismissButton = { CockpitAction(stringResource(R.string.trips_cancel), { delete = false }) },
            confirmButton = {
                CockpitAction(stringResource(R.string.trips_clear), { delete = false; runtime.removeCompleted() }, destructive = true)
            },
        )
    }
    consentId?.let { tripId ->
        CockpitAlertDialog(
            title = { Text(stringResource(R.string.trip_gps_allow)) },
            onDismissRequest = { consentId = null },
            text = { Text(stringResource(R.string.trip_gps_consent)) },
            dismissButton = { CockpitAction(stringResource(R.string.trips_cancel), { consentId = null }) },
            confirmButton = {
                CockpitAction(stringResource(R.string.trip_gps_allow), {
                    consentId = null
                    if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        model.startRoute(tripId, activity)
                    } else {
                        permissionTripId = tripId
                        locationRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }, primary = true)
            },
        )
    }
}

@Composable
private fun TripDetailsScreen(trip: RecordedTrip, revision: Long, runtime: TripRuntime, back: () -> Unit) {
    var details by remember(trip.id) { mutableStateOf<TripDetails?>(null) }
    var loaded by remember(trip.id) { mutableStateOf(false) }
    LaunchedEffect(trip.id, if (trip.finishedAtMillis == null) revision else 0) {
        details = runtime.details(trip.id)
        loaded = true
    }
    CockpitDetail(tripDate(trip.startedAtMillis), stringResource(if (trip.finishedAtMillis == null) R.string.trip_current
        else R.string.trips_completed_one), back) {
        CockpitColumns(first = {
            CockpitSection(stringResource(R.string.trip_summary_title), CockpitIcon.CAR) {
                Text(stringResource(R.string.trip_summary, tripDuration(trip.durationMillis), tripDistance(trip.distanceKm)))
                val startCharge = trip.start.chargePercent?.let { tripAmount(R.string.trip_percent, it) }
                val endCharge = trip.latest.chargePercent?.let { tripAmount(R.string.trip_percent, it) }
                val startFuel = trip.start.fuelLitres?.let { tripAmount(R.string.trip_litres, it) }
                val endFuel = trip.latest.fuelLitres?.let { tripAmount(R.string.trip_litres, it) }
                val missing = stringResource(R.string.trip_unknown)
                Text(stringResource(R.string.trip_charge_range, startCharge ?: missing, endCharge ?: missing))
                Text(stringResource(R.string.trip_fuel_range, startFuel ?: missing, endFuel ?: missing))
                if (trip.interrupted) CockpitFeedback(stringResource(R.string.trip_interrupted))
            }
            CockpitSection(stringResource(R.string.trip_usage_title), CockpitIcon.ENERGY) {
                val usage = details?.usage
                when {
                    !loaded -> Text(stringResource(R.string.trips_loading))
                    usage == null -> CockpitFeedback(stringResource(R.string.trips_store_error), error = true)
                    else -> TripUsageCharts(usage)
                }
            }
        }, second = {
            CockpitSection(stringResource(R.string.trip_gps_title), CockpitIcon.DISPLAY) {
                val route = details?.route
                when {
                    !loaded -> Text(stringResource(R.string.trips_loading))
                    route == null -> CockpitFeedback(stringResource(R.string.trips_store_error), error = true)
                    else -> TripRouteChart(route)
                }
            }
        })
    }
}

private fun tripDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
