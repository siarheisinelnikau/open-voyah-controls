package org.openvoyah.controls.presentation.vehicle

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.vehicle.ExteriorLamp
import org.openvoyah.controls.domain.vehicle.HeadlightFeedback
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.domain.vehicle.LampStatus
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitMotion
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout
import org.openvoyah.controls.presentation.theme.LocalCockpitMotion

@Composable
internal fun HeadlightStatusSection(
    status: HeadlightStatus,
    monitoring: HeadlightMonitoringState,
    setMonitoring: (Boolean) -> Unit,
    openFactory: (() -> Unit)?,
    inspectSources: () -> Unit,
) {
    val current = status.currentObservation
    val confirmed = current ?: status.lastConfirmed
    val mode = confirmed?.mode ?: status.earlierReport?.value
    val reportedAt = confirmed?.confirmedAtMillis ?: status.earlierReport?.reportedAtMillis
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val layout = LocalCockpitLayout.current
    CockpitSection(UiText.get(R.string.headlight_mode_title), CockpitIcon.LIGHT) {
        val hasLampReports = status.lamps.values.any {
            it.current != null || it.lastConfirmed != null || it.earlierReport != null
        }
        val unavailable = status.feedback == HeadlightFeedback.Unavailable
        Column(verticalArrangement = Arrangement.spacedBy(layout.spacing(3.dp))) {
            if (mode != null && reportedAt != null) {
                if (hasLampReports) {
                    Text(UiText.get(R.string.headlight_selector_label), style = type.labelLarge, color = colors.onSurfaceVariant)
                }
                val animate = LocalCockpitMotion.current.enabled
                val modeColor by animateColorAsState(if (current == null) colors.onSurfaceVariant else colors.primary,
                    if (animate) CockpitMotion.change<Color>() else snap<Color>(), label = "headlight mode color")
                // Keyed by the mode alone, so a reading that only becomes confirmed recolours in place instead of a new live-region node
                // announcing the same mode again.
                AnimatedContent(mode, transitionSpec = {
                    if (animate) ContentTransform(fadeIn(CockpitMotion.change()), fadeOut(CockpitMotion.exit()), sizeTransform = null)
                    else ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
                }, label = "headlight mode") { shown ->
                    Text(UiText.get(headlightModeLabel(shown)), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = type.headlineSmall, color = modeColor)
                }
                LightReportTime(reportedAt, lightReportLabel(current != null, confirmed != null))
            }
            if (current == null && (mode == null || unavailable)) {
                val availability = when {
                    hasLampReports && unavailable -> R.string.headlight_selector_unavailable
                    hasLampReports -> R.string.headlight_selector_waiting
                    unavailable -> R.string.headlight_mode_unavailable
                    else -> R.string.headlight_mode_waiting
                }
                Text(UiText.get(availability), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = if (mode == null) type.bodyLarge else type.bodyMedium, color = colors.onSurfaceVariant)
            }
            if (status.saveFailed) Text(UiText.get(R.string.headlight_mode_save_failed), color = colors.error)
        }
        if (hasLampReports) {
            HorizontalDivider(color = colors.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(layout.spacing(6.dp))) {
                for (lamp in ExteriorLamp.entries) LampReading(lamp, status.lamps[lamp])
            }
        }
        monitoring.whileControlsOpen?.let { enabled ->
            CockpitSwitch(UiText.get(R.string.headlight_monitoring_open), enabled, !monitoring.saving, setMonitoring)
        }
        if (monitoring.saveFailed) Text(UiText.get(R.string.headlight_monitoring_save_failed), color = colors.error)
        if (openFactory != null) CockpitAction(UiText.get(R.string.headlight_mode_open_factory), openFactory)
        CockpitChoice(UiText.get(R.string.headlight_sources_title), onClick = inspectSources)
    }
}

@Composable
private fun LampReading(lamp: ExteriorLamp, status: LampStatus?) {
    val current = status?.current
    val confirmed = current ?: status?.lastConfirmed
    val reading = confirmed?.activity ?: status?.earlierReport?.value
    val reportedAt = confirmed?.confirmedAtMillis ?: status?.earlierReport?.reportedAtMillis
    val colors = MaterialTheme.colorScheme
    val activity = reading?.label ?: R.string.headlight_activity_unavailable
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(UiText.get(R.string.headlight_activity_summary).format(UiText.get(lamp.activityLabel), UiText.get(activity)),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium,
            color = if (current == null) colors.onSurfaceVariant else colors.onSurface)
        if (reportedAt != null) LightReportTime(reportedAt, lightReportLabel(current != null, confirmed != null))
        if (status?.saveFailed == true) Text(UiText.get(R.string.headlight_mode_save_failed), color = colors.error)
    }
}

@Composable
private fun LightReportTime(atMillis: Long, @StringRes label: Int) {
    val locale = Locale.forLanguageTag(UiText.language())
    val zone = ZoneId.systemDefault()
    val time = remember(atMillis, locale, zone) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(zone)
            .format(Instant.ofEpochMilli(atMillis))
    }
    Text(UiText.get(label).format(time), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@StringRes
private fun lightReportLabel(current: Boolean, confirmed: Boolean): Int = when {
    current -> R.string.headlight_mode_confirmed
    confirmed -> R.string.headlight_mode_last_confirmed
    else -> R.string.headlight_mode_earlier_report
}

internal fun headlightModeLabel(mode: HeadlightMode): Int = when (mode) {
    HeadlightMode.AUTOMATIC -> R.string.headlight_mode_automatic
    HeadlightMode.LOW_BEAM -> R.string.headlight_mode_low_beam
    HeadlightMode.POSITION -> R.string.headlight_mode_position
    HeadlightMode.OFF -> R.string.headlight_mode_off
    HeadlightMode.ON -> R.string.headlight_mode_on
    HeadlightMode.DAYTIME_RUNNING -> R.string.headlight_mode_daytime_running
}
