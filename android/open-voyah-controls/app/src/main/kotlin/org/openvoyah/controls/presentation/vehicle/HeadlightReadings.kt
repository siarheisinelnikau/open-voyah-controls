package org.openvoyah.controls.presentation.vehicle

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import org.openvoyah.controls.R
import org.openvoyah.controls.domain.vehicle.ExteriorLamp
import org.openvoyah.controls.domain.vehicle.HeadlightFeedback
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.domain.vehicle.LampActivity

internal enum class HeadlightReadingFreshness(@StringRes val caption: Int) {
    CURRENT(R.string.desktop_info_headlights_current),
    CURRENT_UNSAVED(R.string.headlight_mode_save_failed),
    LAST_CONFIRMED(R.string.desktop_info_headlights_last_confirmed),
    EARLIER(R.string.desktop_info_headlights_earlier_report),
    WAITING(R.string.desktop_info_headlights_waiting),
    UNAVAILABLE(R.string.desktop_info_headlights_empty),
}

internal data class HeadlightSwitchDisplay(val mode: HeadlightMode?, val freshness: HeadlightReadingFreshness)

internal data class LampActivityDisplay(val activity: LampActivity?, val freshness: HeadlightReadingFreshness)

@Composable
internal fun observeVisibleHeadlightSwitch(): HeadlightSwitchDisplay = observeVisibleHeadlightReading(::switchDisplay)

@Composable
internal fun observeVisibleLamp(lamp: ExteriorLamp): LampActivityDisplay {
    val project = remember(lamp) { { status: HeadlightStatus, active: Boolean -> status.lampDisplay(lamp, active) } }
    return observeVisibleHeadlightReading(project)
}

@Composable
private fun <T> observeVisibleHeadlightReading(project: (HeadlightStatus, Boolean) -> T): T {
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity) { "A headlight indicator requires an activity host" }
    val model = remember(activity) { activity.vehicleSettingsViewModel() }
    val status = model.headlightStatus.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return remember(status, lifecycle, project) {
        // Only the selected reading and its freshness affect the indicator.
        derivedStateOf(structuralEqualityPolicy()) {
            project(status.value, lifecycle.value.isAtLeast(Lifecycle.State.RESUMED))
        }
    }.value
}

private fun switchDisplay(status: HeadlightStatus, active: Boolean): HeadlightSwitchDisplay {
    val current = status.currentObservation.takeIf { active }
    val earlier = status.earlierReport.takeIf { active }
    val mode = (current ?: status.lastConfirmed)?.mode ?: earlier?.value
    val freshness = when {
        current != null && status.saveFailed -> HeadlightReadingFreshness.CURRENT_UNSAVED
        current != null -> HeadlightReadingFreshness.CURRENT
        status.lastConfirmed != null -> HeadlightReadingFreshness.LAST_CONFIRMED
        earlier != null -> HeadlightReadingFreshness.EARLIER
        status.feedback == HeadlightFeedback.Unavailable -> HeadlightReadingFreshness.UNAVAILABLE
        else -> HeadlightReadingFreshness.WAITING
    }
    return HeadlightSwitchDisplay(mode, freshness)
}

internal fun HeadlightStatus.lampDisplay(lamp: ExteriorLamp, active: Boolean): LampActivityDisplay {
    val sample = lamps[lamp]
    val current = sample?.current?.takeIf { active }
    val earlier = sample?.earlierReport?.takeIf { active }
    val activity = (current ?: sample?.lastConfirmed)?.activity ?: earlier?.value
    val freshness = when {
        current != null && sample.saveFailed -> HeadlightReadingFreshness.CURRENT_UNSAVED
        current != null -> HeadlightReadingFreshness.CURRENT
        sample?.lastConfirmed != null -> HeadlightReadingFreshness.LAST_CONFIRMED
        earlier != null -> HeadlightReadingFreshness.EARLIER
        else -> HeadlightReadingFreshness.UNAVAILABLE
    }
    return LampActivityDisplay(activity, freshness)
}

@get:StringRes
internal val ExteriorLamp.activityLabel: Int get() = when (this) {
    ExteriorLamp.HEADLIGHTS -> R.string.headlight_activity_main
    ExteriorLamp.HIGH_BEAM -> R.string.headlight_activity_high
}

@get:StringRes
internal val LampActivity.label: Int get() = when (this) {
    LampActivity.ON -> R.string.headlight_activity_on
    LampActivity.OFF -> R.string.headlight_activity_off
    LampActivity.DAYTIME_RUNNING -> R.string.headlight_mode_daytime_running
}
