package org.openvoyah.controls.presentation.dock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.dock.QuickActionHeadlightContent
import org.openvoyah.controls.domain.dock.QuickActionHeadlightTap
import org.openvoyah.controls.domain.vehicle.ExteriorLamp
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.LampActivity
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitMotion
import org.openvoyah.controls.presentation.theme.LocalCockpitMotion
import org.openvoyah.controls.presentation.theme.rememberPressMotion
import org.openvoyah.controls.presentation.vehicle.HeadlightReadingFreshness
import org.openvoyah.controls.presentation.vehicle.activityLabel
import org.openvoyah.controls.presentation.vehicle.headlightModeLabel
import org.openvoyah.controls.presentation.vehicle.label
import org.openvoyah.controls.presentation.vehicle.observeVisibleHeadlightSwitch
import org.openvoyah.controls.presentation.vehicle.observeVisibleLamp

@Composable
internal fun DockHeadlightIndicator(
    content: QuickActionHeadlightContent,
    tap: QuickActionHeadlightTap,
    stacked: Boolean,
    selected: Boolean,
    modifier: Modifier,
    enlargedRail: Boolean,
    open: () -> Unit,
) {
    val (state, shortReading, freshness) = dockLightReading(content)
    val icon = if (content == QuickActionHeadlightContent.HIGH_BEAM) CockpitIcon.HIGH_BEAM else CockpitIcon.LIGHT
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val captionStyle = typography.labelSmall.copy(letterSpacing = 0.sp)
    val readingHeight = with(LocalDensity.current) { typography.labelLarge.lineHeight.toDp() + captionStyle.lineHeight.toDp() }
    val shape = MaterialTheme.shapes.medium
    val destination = if (tap == QuickActionHeadlightTap.SOURCES) R.string.headlight_sources_title else R.string.headlight_mode_title
    val title = UiText.get(R.string.quick_actions_open).format(UiText.get(destination))
    val shortCaption = when (freshness) {
        HeadlightReadingFreshness.LAST_CONFIRMED -> UiText.get(R.string.quick_actions_headlight_last)
        HeadlightReadingFreshness.EARLIER -> UiText.get(R.string.quick_actions_headlight_earlier)
        HeadlightReadingFreshness.CURRENT_UNSAVED -> UiText.get(R.string.quick_actions_headlight_unsaved)
        else -> null
    }
    val emphasis = when {
        freshness == HeadlightReadingFreshness.CURRENT_UNSAVED -> colors.error
        selected -> colors.onPrimaryContainer
        freshness == HeadlightReadingFreshness.CURRENT -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val press = rememberPressMotion()
    val spec = if (LocalCockpitMotion.current.enabled) CockpitMotion.change<Color>() else snap<Color>()
    val fill by animateColorAsState(if (selected) colors.primaryContainer else colors.surfaceContainerHigh, spec, label = "indicator fill")
    val edge by animateColorAsState(if (selected) colors.primary else colors.outlineVariant, spec, label = "indicator edge")
    val ink by animateColorAsState(emphasis, spec, label = "indicator reading")
    val hit = modifier.then(press.modifier).widthIn(min = 48.dp).heightIn(min = 48.dp).clip(shape)
        .background(fill)
        .border(1.dp, edge, shape)
        .clickable(interactionSource = press.source, indication = LocalIndication.current, role = Role.Button, onClick = open)
        .semantics {
            contentDescription = title
            stateDescription = state
        }
        .then(if (enlargedRail) Modifier.enlargeDockShortcut(rail = true) else Modifier)
        .padding(horizontal = if (stacked) 3.dp else 8.dp, vertical = if (stacked) 4.dp else 2.dp)
    val reading: @Composable () -> Unit = {
        // The state description of the button carries the reading; its drawn text stays out of the accessibility tree.
        LiveReading(shortReading to shortCaption, Modifier.heightIn(min = readingHeight).clearAndSetSemantics {},
            contentAlignment = Alignment.Center) { (text, caption) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(text, style = typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (caption != null) Text(caption, style = captionStyle, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (stacked) Column(hit, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically)) {
        CockpitGlyph(icon, Modifier.size(20.dp))
        reading()
    } else Row(hit, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        CockpitGlyph(icon, Modifier.size(20.dp))
        reading()
    }
}

/**
 * Crossfades a reading when it changes; with transitions off it changes at once. Readings that arrive just after the
 * block appeared change at once too: its source is still delivering the first value, and fading a placeholder out on
 * every visit would only delay the real reading.
 */
@Composable
internal fun <T> LiveReading(
    value: T,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    contentKey: (T) -> Any? = { it },
    content: @Composable (T) -> Unit,
) {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(CockpitMotion.ENTER.toLong())
        settled = true
    }
    if (!settled || !LocalCockpitMotion.current.enabled) {
        Box(modifier, contentAlignment = contentAlignment) { content(value) }
        return
    }
    AnimatedContent(value, modifier, transitionSpec = { fadeIn(CockpitMotion.change()).togetherWith(fadeOut(CockpitMotion.exit())) },
        contentAlignment = contentAlignment, label = "live reading", contentKey = contentKey) { content(it) }
}

private data class DockLightReading(val description: String, val shortReading: String, val freshness: HeadlightReadingFreshness)

@Composable
private fun dockLightReading(content: QuickActionHeadlightContent): DockLightReading {
    val summary = UiText.get(R.string.headlight_activity_summary)
    val lamp = when (content) {
        QuickActionHeadlightContent.SWITCH -> null
        QuickActionHeadlightContent.HEADLIGHTS -> ExteriorLamp.HEADLIGHTS
        QuickActionHeadlightContent.HIGH_BEAM -> ExteriorLamp.HIGH_BEAM
    }
    if (lamp == null) {
        val (mode, freshness) = observeVisibleHeadlightSwitch()
        val caption = UiText.get(freshness.caption)
        val state = mode?.let { summary.format(UiText.get(headlightModeLabel(it)), caption) } ?: caption
        return DockLightReading(state, mode?.let { UiText.get(it.dockLabel) } ?: "—", freshness)
    }
    val (activity, freshness) = observeVisibleLamp(lamp)
    val label = UiText.get(lamp.activityLabel)
    val caption = when (freshness) {
        HeadlightReadingFreshness.LAST_CONFIRMED -> R.string.desktop_info_headlights_lamps_last_confirmed
        HeadlightReadingFreshness.UNAVAILABLE -> R.string.headlight_activity_unavailable
        else -> freshness.caption
    }
    val reading = activity?.let { summary.format(label, UiText.get(it.label)) } ?: label
    val shortReading = when (activity) {
        LampActivity.DAYTIME_RUNNING -> UiText.get(R.string.quick_actions_headlight_daytime)
        null -> "—"
        else -> UiText.get(activity.label)
    }
    return DockLightReading(summary.format(reading, UiText.get(caption)), shortReading, freshness)
}

private val HeadlightMode.dockLabel: Int get() = when (this) {
    HeadlightMode.LOW_BEAM -> R.string.quick_actions_headlight_low
    HeadlightMode.POSITION -> R.string.quick_actions_headlight_position
    HeadlightMode.ON -> R.string.headlight_activity_on
    HeadlightMode.OFF -> R.string.headlight_activity_off
    HeadlightMode.DAYTIME_RUNNING -> R.string.quick_actions_headlight_daytime
    HeadlightMode.AUTOMATIC -> R.string.quick_actions_headlight_auto
}
