package org.openvoyah.controls.presentation.apps

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.display.DisplayEventBus.Navigation
import org.openvoyah.controls.data.apps.DesktopInfoPreferences
import org.openvoyah.controls.data.apps.DesktopInfoSources
import org.openvoyah.controls.data.apps.DesktopMediaContent
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.DesktopHeadlightContent
import org.openvoyah.controls.domain.apps.DesktopInfoCard
import org.openvoyah.controls.domain.apps.DesktopInfoTile
import org.openvoyah.controls.domain.apps.DesktopMediaOpenTarget
import org.openvoyah.controls.domain.apps.DesktopMediaStyle
import org.openvoyah.controls.domain.apps.DesktopRouteContent
import org.openvoyah.controls.domain.apps.DesktopRouteOpenTarget
import org.openvoyah.controls.domain.display.DisplayPreviewDistanceUnits
import org.openvoyah.controls.domain.vehicle.ExteriorLamp
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.domain.vehicle.ServiceUrgency
import org.openvoyah.controls.platform.apps.openDesktopMediaPlayer
import org.openvoyah.controls.platform.apps.openDesktopNavigator
import org.openvoyah.controls.presentation.display.formatDistance
import org.openvoyah.controls.presentation.dock.LiveReading
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout
import org.openvoyah.controls.presentation.vehicle.HeadlightReadingFreshness
import org.openvoyah.controls.presentation.vehicle.activityLabel
import org.openvoyah.controls.presentation.vehicle.headlightModeLabel
import org.openvoyah.controls.presentation.vehicle.label
import org.openvoyah.controls.presentation.vehicle.lampDisplay
import org.openvoyah.controls.presentation.vehicle.observeVisibleHeadlightSwitch
import org.openvoyah.controls.presentation.vehicle.serviceSummary
import org.openvoyah.controls.presentation.vehicle.vehicleSettingsViewModel
import org.openvoyah.controls.presentation.vehicle.comfort.ComfortActionCard
import org.openvoyah.controls.presentation.vehicle.comfort.ComfortCardAction

@Composable
internal fun rememberDesktopInfoCards(desktop: AppGridDesktop): List<DesktopInfoTile> {
    val app = LocalContext.current.applicationContext
    val preferences = remember(app) { DesktopInfoPreferences(app) }
    return key(desktop) {
        val selected = remember(preferences, desktop) {
            preferences.observe().map { it.getOrNull()?.tiles(desktop).orEmpty() }.distinctUntilChanged()
        }
        val cards by selected.visibleState(emptyList())
        cards
    }
}

/**
 * One desktop card as a cell of the app grid. [visible] is true while its page is on screen: pages are composed while
 * they slide in, so a card subscribes to its source only once it can be seen.
 */
@Composable
internal fun LazyGridItemScope.DesktopInfoCardItem(
    tile: DesktopInfoTile,
    visible: Boolean,
    showOutlines: Boolean,
    navigate: (ControlsRoute) -> Unit,
    enabled: Boolean = true,
) {
    val routeAction = if (tile.card == DesktopInfoCard.ROUTE) when (tile.routeOpenTarget) {
        DesktopRouteOpenTarget.DISPLAY -> R.string.virtual_display
        DesktopRouteOpenTarget.NAVIGATOR -> R.string.desktop_info_route_open_navigator_action
        DesktopRouteOpenTarget.NONE -> null
    } else null
    // A card hidden by a search leaves at once, so the results take its place without waiting for it to fade.
    val modifier = gridItemMotion(leave = false).fillMaxWidth().heightIn(min = 116.dp).alpha(if (enabled) 1f else 0.5f)
        .semantics {
            testTagsAsResourceId = true
            routeAction?.let { onClick(label = UiText.get(it), action = null) }
        }.testTag(tile.key)
    val shape = MaterialTheme.shapes.medium
    val colors = MaterialTheme.colorScheme
    val border = if (showOutlines) BorderStroke(1.dp, colors.outlineVariant) else null
    val comfortAction = when (tile.card) {
        DesktopInfoCard.POWER_HOLD -> ComfortCardAction.POWER_HOLD
        DesktopInfoCard.CAR_WASH -> ComfortCardAction.CAR_WASH
        else -> null
    }
    if (comfortAction != null) {
        Surface(modifier = modifier, shape = shape, color = colors.surfaceContainerHigh, border = border) {
            ComfortActionCard(comfortAction, interactive = visible && enabled)
        }
        return
    }
    val destination = when (tile.card) {
        DesktopInfoCard.SERVICE -> ControlsRoute.SERVICE_REMINDER
        DesktopInfoCard.HEADLIGHTS -> ControlsRoute.VEHICLE
        DesktopInfoCard.MEDIA -> ControlsRoute.DISPLAY.takeUnless { tile.mediaOpenTarget == DesktopMediaOpenTarget.NONE }
        DesktopInfoCard.ROUTE -> ControlsRoute.DISPLAY.takeUnless { tile.routeOpenTarget == DesktopRouteOpenTarget.NONE }
        else -> null
    }
    val details = if (visible && tile.card != DesktopInfoCard.PHONE) DesktopInfoDetails(tile) else TileDetails()
    val mediaActionLabel = if (tile.card == DesktopInfoCard.MEDIA && destination != null) {
        if (details.playerPackage == null) R.string.desktop_info_media_open_display_action
        else R.string.desktop_info_media_open_player_action
    } else null
    val content: @Composable () -> Unit = {
        if (tile.card == DesktopInfoCard.PHONE) DesktopPhoneFavoritesCard(tile.phoneFavorites, visible)
        else DesktopInfoContent(tile, details, destination != null, mediaActionLabel)
    }
    // While apps are being moved, a card stays in place but does not open anything.
    if (destination != null && enabled) {
        val showDestination = { navigate(destination) }
        val playerPackage = details.playerPackage
        val open = when {
            tile.card == DesktopInfoCard.MEDIA && playerPackage != null ->
                externalAppCardOpen(showDestination) { activity, stillActive ->
                    openDesktopMediaPlayer(activity, playerPackage, stillActive)
                }
            tile.card == DesktopInfoCard.ROUTE && tile.routeOpenTarget == DesktopRouteOpenTarget.NAVIGATOR ->
                externalAppCardOpen(showDestination, ::openDesktopNavigator)
            tile.card == DesktopInfoCard.MEDIA || tile.card == DesktopInfoCard.ROUTE -> {
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                val showWhileResumed = { if (lifecycle.currentState == Lifecycle.State.RESUMED) showDestination() }
                showWhileResumed
            }
            else -> showDestination
        }
        Surface(onClick = open, modifier = modifier, shape = shape,
            color = colors.surfaceContainerHigh, border = border, content = content)
    } else {
        Surface(modifier = modifier, shape = shape, color = colors.surfaceContainerHigh, border = border, content = content)
    }
}

private data class TileDetails(
    val value: String? = null, val caption: String? = null, val attention: Boolean = false, val artwork: Bitmap? = null,
    val lampRows: List<LampTileRow> = emptyList(),
    val playerPackage: String? = null,
    val route: RouteTileDetails? = null,
)

private data class RouteTileDetails(val nextRoad: String?, val distance: String?, val currentRoad: String?)

private data class LampTileRow(
    val summary: String, val caption: String?, val live: Boolean, val saveFailed: Boolean,
)

@Composable
private fun DesktopInfoDetails(tile: DesktopInfoTile): TileDetails {
    val app = LocalContext.current.applicationContext
    val sources = remember(app) { DesktopInfoSources(app) }
    return when (tile.card) {
        DesktopInfoCard.CLOCK -> key(tile.clockFormat) {
            val time by remember(sources, tile.clockFormat) { sources.clock(tile.clockFormat) }.visibleState(null)
            TileDetails(time)
        }
        DesktopInfoCard.SERVICE -> {
            val progress by remember(sources) { sources.service() }.visibleState(null)
            progress?.let { TileDetails(it.serviceSummary(), attention = it.urgency >= ServiceUrgency.DUE) }
                ?: TileDetails(UiText.get(R.string.desktop_info_service_empty))
        }
        DesktopInfoCard.MEDIA -> DesktopMediaTileDetails(sources, tile.mediaStyle, tile.preferredMediaPackage,
            identifyPlayer = tile.mediaOpenTarget == DesktopMediaOpenTarget.PLAYER)
        DesktopInfoCard.HEADLIGHTS -> HeadlightTileDetails(tile.headlightContent)
        DesktopInfoCard.PHONE -> TileDetails()
        DesktopInfoCard.ROUTE -> observeRouteTileDetails(sources, tile.routeContent)
        DesktopInfoCard.POWER_HOLD, DesktopInfoCard.CAR_WASH -> TileDetails()
    }
}

@Composable
private fun observeRouteTileDetails(sources: DesktopInfoSources, content: DesktopRouteContent): TileDetails {
    val route by remember(sources) { sources.route() }.visibleState(Navigation.EMPTY)
    val next = route.nextRoad().takeIf(String::isNotBlank)
    val distance = DisplayPreviewDistanceUnits.METRIC.formatDistance(route.meters()).takeIf(String::isNotBlank)
    val current = route.road().takeIf { content.showCurrentRoad && it.isNotBlank() }
    return if (next == null && distance == null && current == null) TileDetails()
    else TileDetails(route = RouteTileDetails(next, distance, current))
}

@Composable
private fun DesktopMediaTileDetails(
    sources: DesktopInfoSources,
    style: DesktopMediaStyle,
    preferredPackage: String,
    identifyPlayer: Boolean,
): TileDetails {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val needsSession = style == DesktopMediaStyle.COVER || preferredPackage.isNotEmpty() || identifyPlayer
    if (needsSession && !lifecycle.isAtLeast(Lifecycle.State.RESUMED)) return TileDetails()
    return key(style, preferredPackage, identifyPlayer) {
        val feed = remember(sources, style, preferredPackage, identifyPlayer) {
            sources.media(style, preferredPackage, identifyPlayer = identifyPlayer)
        }
        val media by feed.visibleState(null)
        mediaDetails(media)
    }
}

@Composable
private fun externalAppCardOpen(
    openDisplay: () -> Unit,
    launch: suspend (activity: Activity, stillActive: () -> Boolean) -> Boolean,
): () -> Unit {
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (activity == null) return {
        if (lifecycle.currentState == Lifecycle.State.RESUMED) openDisplay()
    }
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf<Job?>(null) }
    val fallback by rememberUpdatedState(openDisplay)
    LifecycleResumeEffect(lifecycle) { onPauseOrDispose { opening?.cancel() } }
    return {
        if (lifecycle.currentState == Lifecycle.State.RESUMED && opening?.isActive != true) {
            opening = scope.launch {
                val opened = launch(activity) { lifecycle.currentState == Lifecycle.State.RESUMED }
                if (!opened && lifecycle.currentState == Lifecycle.State.RESUMED) fallback()
            }
        }
    }
}

@Composable
private fun HeadlightTileDetails(content: DesktopHeadlightContent): TileDetails {
    if (content == DesktopHeadlightContent.SWITCH_POSITION) {
        val (mode, freshness) = observeVisibleHeadlightSwitch()
        val caption = UiText.get(freshness.caption)
        return TileDetails(mode?.let { UiText.get(headlightModeLabel(it)) } ?: caption,
            caption = caption.takeIf { mode != null }, attention = freshness == HeadlightReadingFreshness.CURRENT_UNSAVED)
    }
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity) { "A headlight card requires an activity host" }
    val model = remember(activity) { activity.vehicleSettingsViewModel() }
    val status by model.headlightStatus.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return TileDetails(lampRows = lampTileRows(status, lifecycle.isAtLeast(Lifecycle.State.RESUMED)))
}

private fun lampTileRows(status: HeadlightStatus, active: Boolean): List<LampTileRow> = ExteriorLamp.entries.map { lamp ->
    val (shown, freshness) = status.lampDisplay(lamp, active)
    val activity = shown?.label ?: R.string.desktop_info_headlights_lamps_unconfirmed
    val saveFailed = freshness == HeadlightReadingFreshness.CURRENT_UNSAVED
    val current = freshness == HeadlightReadingFreshness.CURRENT || saveFailed
    val caption = when (freshness) {
        HeadlightReadingFreshness.CURRENT_UNSAVED, HeadlightReadingFreshness.EARLIER -> freshness.caption
        HeadlightReadingFreshness.LAST_CONFIRMED -> R.string.desktop_info_headlights_lamps_last_confirmed
        else -> null
    }
    LampTileRow(UiText.get(R.string.headlight_activity_summary).format(UiText.get(lamp.activityLabel), UiText.get(activity)),
        caption?.let { UiText.get(it) }, current, saveFailed)
}

@Composable
private fun DesktopInfoContent(tile: DesktopInfoTile, details: TileDetails, clickable: Boolean, mediaActionLabel: Int?) {
    val card = tile.card
    val heading = if (card == DesktopInfoCard.HEADLIGHTS && tile.headlightContent == DesktopHeadlightContent.LAMP_ACTIVITY) {
        R.string.desktop_info_headlights_content_lamps
    } else card.label()
    val layout = LocalCockpitLayout.current
    val padding = Modifier.padding(horizontal = layout.spacing(13.dp), vertical = layout.spacing(10.dp))
    val artwork = details.artwork
    if (artwork == null) {
        Column(padding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DesktopInfoHeading(heading, clickable)
            DesktopInfoText(card, details, mediaActionLabel)
        }
        return
    }
    BoxWithConstraints(padding) {
        val artBesideText = maxWidth >= 182.dp
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DesktopInfoHeading(heading, clickable, artwork.takeUnless { artBesideText })
            if (artBesideText) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MediaThumbnail(artwork, Modifier.size(58.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DesktopInfoText(card, details, mediaActionLabel)
                    }
                }
            } else DesktopInfoText(card, details, mediaActionLabel)
        }
    }
}

@Composable
private fun DesktopInfoHeading(heading: Int, clickable: Boolean, artwork: Bitmap? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (artwork != null) MediaThumbnail(artwork, Modifier.size(30.dp))
        Text(UiText.get(heading), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (clickable) CockpitGlyph(CockpitIcon.ARROW, Modifier.size(16.dp))
    }
}

@Composable
private fun MediaThumbnail(bitmap: Bitmap, modifier: Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(image, contentDescription = null, modifier = modifier.clip(MaterialTheme.shapes.small)
        .testTag("desktop-info:media-artwork"), contentScale = ContentScale.Crop)
}

@Composable
private fun DesktopInfoText(card: DesktopInfoCard, details: TileDetails, mediaActionLabel: Int?) {
    if (card == DesktopInfoCard.ROUTE) {
        DesktopRouteText(details.route)
        return
    }
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    for (lamp in details.lampRows) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(lamp.summary, style = type.bodyMedium, color = if (lamp.live) colors.onSurface else colors.onSurfaceVariant)
            lamp.caption?.let { Text(it, style = type.bodySmall, color = if (lamp.saveFailed) colors.error else colors.onSurfaceVariant) }
        }
    }
    // A new minute or track blends in. Headlight and service readings change at once: a caption that lingered while fading
    // would still claim a state the car has already left.
    val blend = card == DesktopInfoCard.CLOCK || card == DesktopInfoCard.MEDIA
    details.value?.let { value ->
        val reading: @Composable (String) -> Unit = { shown ->
            Text(shown, style = if (card == DesktopInfoCard.CLOCK) type.headlineSmall else type.titleMedium,
                color = if (details.attention) colors.error else colors.onSurface, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        if (blend) LiveReading(value, content = reading) else reading(value)
    }
    details.caption?.let { caption ->
        val note: @Composable (String) -> Unit = { shown ->
            Text(shown, style = type.bodySmall, color = colors.onSurfaceVariant, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        if (blend) LiveReading(caption, content = note) else note(caption)
    }
    mediaActionLabel?.let {
        Text(UiText.get(it), style = type.labelMedium, color = colors.primary)
    }
}

@Composable
private fun DesktopRouteText(route: RouteTileDetails?) {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    // A route that starts or ends and a road that changes blend; the distance counts down in place while driving.
    LiveReading(route, contentKey = { it == null }) { shown ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (shown == null) {
                Text(UiText.get(R.string.desktop_info_route_waiting), style = type.bodyMedium,
                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                shown.distance?.let { Text(it, style = type.titleMedium, color = colors.primary, maxLines = 1) }
                shown.nextRoad?.let { road ->
                    LiveReading(road) {
                        Text(it, style = if (shown.distance == null) type.titleMedium else type.bodyMedium,
                            color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                shown.currentRoad?.let { road ->
                    LiveReading(road) {
                        Text(UiText.get(R.string.desktop_info_route_current_road).format(it),
                            style = if (shown.distance == null && shown.nextRoad == null) type.bodyMedium else type.bodySmall,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun mediaDetails(snapshot: DesktopMediaContent?): TileDetails {
    if (snapshot == null || !snapshot.media.available()) return TileDetails(UiText.get(R.string.desktop_info_media_empty))
    val media = snapshot.media
    val artist = media.artist().takeIf(String::isNotBlank)
    val caption = if (media.playing()) artist else {
        val paused = UiText.get(R.string.desktop_info_media_paused)
        if (artist == null) paused else UiText.get(R.string.desktop_info_media_artist_paused).format(artist, paused)
    }
    return TileDetails(media.title(), caption, artwork = snapshot.artwork, playerPackage = snapshot.playerPackage)
}

@Composable
private fun <T> Flow<T>.visibleState(initial: T) = collectAsStateWithLifecycle(
    initialValue = initial, minActiveState = Lifecycle.State.RESUMED,
)

internal fun DesktopInfoCard.label(): Int = when (this) {
    DesktopInfoCard.CLOCK -> R.string.desktop_info_clock
    DesktopInfoCard.SERVICE -> R.string.desktop_info_service
    DesktopInfoCard.MEDIA -> R.string.desktop_info_media
    DesktopInfoCard.HEADLIGHTS -> R.string.desktop_info_headlights
    DesktopInfoCard.PHONE -> R.string.desktop_info_phone
    DesktopInfoCard.ROUTE -> R.string.desktop_info_route
    DesktopInfoCard.POWER_HOLD -> R.string.comfort_power_hold
    DesktopInfoCard.CAR_WASH -> R.string.comfort_car_wash
}
