package org.openvoyah.controls.presentation.vehicle

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import org.openvoyah.controls.data.vehicle.HeadlightMonitoringPreferences

/** Keeps the shared monitor subscribed across internal destinations while Controls is foreground. */
@Composable
internal fun KeepHeadlightsObserved(activity: ComponentActivity) {
    val preferences = remember(activity) { HeadlightMonitoringPreferences(activity.applicationContext) }
    LaunchedEffect(activity, preferences) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            observeHeadlightsWhileOpen(preferences.observeWhileControlsOpen()) { activity.vehicleSettingsViewModel() }
        }
    }
}

internal suspend fun observeHeadlightsWhileOpen(enabled: Flow<Boolean>, model: () -> VehicleSettingsViewModel) {
    enabled.distinctUntilChanged().collectLatest { observe ->
        if (observe) model().headlightStatus.collect()
    }
}
