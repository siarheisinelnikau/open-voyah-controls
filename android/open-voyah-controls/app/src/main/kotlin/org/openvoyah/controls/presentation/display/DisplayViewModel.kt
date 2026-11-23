package org.openvoyah.controls.presentation.display

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.core.display.DisplayEventBus
import org.openvoyah.controls.core.display.DisplaySettings
import org.openvoyah.controls.core.display.NavigationFeed
import org.openvoyah.controls.data.display.DisplayPreviewPreferences
import org.openvoyah.controls.domain.display.DisplayPreviewArrangement
import org.openvoyah.controls.domain.display.DisplayPreviewContent
import org.openvoyah.controls.domain.display.DisplayPreviewDistanceUnits
import org.openvoyah.controls.domain.display.DisplayPreviewMediaSubtitle
import org.openvoyah.controls.domain.display.DisplayPreviewPalette
import org.openvoyah.controls.domain.display.DisplayPreviewPaneBalance
import org.openvoyah.controls.domain.display.DisplayPreviewPaneOrder
import org.openvoyah.controls.domain.display.DisplayPreviewRoadNames
import org.openvoyah.controls.domain.display.PreviewMediaAction
import org.openvoyah.controls.domain.display.PreviewMediaFallback
import org.openvoyah.controls.domain.display.PreviewSeekStep
import org.openvoyah.controls.domain.settings.AppRoleTextSize
import org.openvoyah.controls.platform.media.PreviewMediaController
import org.openvoyah.controls.platform.media.PreviewMediaSources

internal class DisplayViewModel(
    private val events: DisplayEventBus,
    private val settings: DisplaySettings,
    private val navigation: NavigationFeed,
    private val previewSettings: DisplayPreviewPreferences,
    private val mediaPreview: PreviewMediaController,
    private val mediaSources: PreviewMediaSources,
    private val locale: Locale,
) : ViewModel() {
    data class State(
        val density: Int,
        val clusterMedia: Boolean,
        val navigationEnabled: Boolean,
        val previewContent: DisplayPreviewContent,
        val changingNavigation: Boolean = false,
        val message: String? = null,
        val savingPreview: Boolean = false,
        val previewSaveFailed: Boolean = false,
        val mediaCommandUnavailable: Boolean = false,
    )

    data class Player(val packageName: String, val actions: Set<PreviewMediaAction>, val revision: Long)

    private val current = MutableStateFlow(
        State(settings.density(), settings.clusterMedia(), navigation.enabled(), previewSettings.load()),
    )
    val state = current.asStateFlow()
    private val snapshots = callbackFlow {
        val stop = events.observe { trySend(it) }
        trySend(events.snapshot())
        awaitClose { stop.run() }
    }.conflate()
    private val previewContent = state.map { it.previewContent }.distinctUntilChanged()
    private val activeMedia = mediaPreview.state
    val player = combine(previewContent, activeMedia) { content, media ->
        media?.takeIf { content.showMedia && content.mediaFallback.permits(content.preferredMediaPackage, it.packageName) }
            ?.let { Player(it.packageName, it.actions, if (it.actions.isEmpty()) 0L else it.revision) }
    }.distinctUntilChanged()
    val previewStates = combine(snapshots, previewContent, activeMedia) { snapshot, content, selected ->
        snapshot.toPreviewState(content, selected, locale)
    }.distinctUntilChanged()

    fun previewSnapshot(): DisplayPreviewState =
        events.snapshot().toPreviewState(current.value.previewContent, activeMedia.value, locale)

    suspend fun observeMediaPreview() {
        try { previewContent.collect(mediaPreview::configure) }
        finally { mediaPreview.close() }
    }

    suspend fun availableMediaSources() = withContext(Dispatchers.IO) {
        mediaSources.list(current.value.previewContent.preferredMediaPackage)
    }

    suspend fun mediaSourceLabel(packageName: String): String = withContext(Dispatchers.IO) { mediaSources.label(packageName) }

    fun refresh() = current.update {
        it.copy(
            density = settings.density(),
            clusterMedia = settings.clusterMedia(),
            navigationEnabled = navigation.enabled(),
            previewContent = if (it.savingPreview) it.previewContent else previewSettings.load(),
            mediaCommandUnavailable = false,
        )
    }

    fun showPreviewMedia(show: Boolean) = changePreviewContent { it.copy(showMedia = show) }

    fun showPreviewNavigation(show: Boolean) = changePreviewContent { it.copy(showNavigation = show) }

    fun showPlaybackProgress(show: Boolean) = changePreviewContent { it.copy(showPlaybackProgress = show) }
    fun showAlbumArtwork(show: Boolean) = changePreviewContent { it.copy(showAlbumArtwork = show) }
    fun showTransportControls(show: Boolean) = changePreviewContent { it.copy(showTransportControls = show) }
    fun preferredMediaSource(packageName: String) = changePreviewContent { it.copy(preferredMediaPackage = packageName) }
    fun mediaFallback(fallback: PreviewMediaFallback) = changePreviewContent { it.copy(mediaFallback = fallback) }
    fun previewArrangement(arrangement: DisplayPreviewArrangement) = changePreviewContent { it.copy(arrangement = arrangement) }
    fun previewPaneOrder(order: DisplayPreviewPaneOrder) = changePreviewContent { it.copy(paneOrder = order) }
    fun seekStep(step: PreviewSeekStep) = changePreviewContent { it.copy(seekStep = step) }
    fun mediaSubtitle(subtitle: DisplayPreviewMediaSubtitle) = changePreviewContent { it.copy(mediaSubtitle = subtitle) }
    fun showCurrentRoad(show: Boolean) = changePreviewContent { it.copy(showCurrentRoad = show) }
    fun showNextRoad(show: Boolean) = changePreviewContent { it.copy(showNextRoad = show) }
    fun showTurnDistance(show: Boolean) = changePreviewContent { it.copy(showTurnDistance = show) }
    fun hideEmptyPanes(hide: Boolean) = changePreviewContent { it.copy(hideEmptyPanes = hide) }
    fun previewPalette(palette: DisplayPreviewPalette) = changePreviewContent { it.copy(palette = palette) }
    fun previewPaneBalance(balance: DisplayPreviewPaneBalance) = changePreviewContent { it.copy(paneBalance = balance) }
    fun previewDistanceUnits(units: DisplayPreviewDistanceUnits) = changePreviewContent { it.copy(distanceUnits = units) }
    fun previewRoadNames(names: DisplayPreviewRoadNames) = changePreviewContent { it.copy(roadNames = names) }
    fun previewTextSize(size: AppRoleTextSize) = changePreviewContent { it.copy(textSize = size) }

    fun transport(action: PreviewMediaAction, revision: Long, displayedStep: PreviewSeekStep) {
        val state = current.value
        val options = state.previewContent
        if (!options.showMedia || !options.showTransportControls || state.savingPreview) return
        val sent = (action.seekDirection == 0 || options.seekStep == displayedStep) &&
            mediaPreview.execute(action, revision, options.preferredMediaPackage, options.mediaFallback, displayedStep)
        current.update { it.copy(mediaCommandUnavailable = !sent) }
    }

    private fun changePreviewContent(change: (DisplayPreviewContent) -> DisplayPreviewContent) {
        if (current.value.savingPreview) return
        current.update { it.copy(savingPreview = true, previewSaveFailed = false, mediaCommandUnavailable = false) }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { previewSettings.update(change) }
                current.update { it.copy(previewContent = saved, savingPreview = false) }
            } catch (_: IOException) {
                current.update { it.copy(savingPreview = false, previewSaveFailed = true) }
            }
        }
    }

    fun density(value: Int) {
        settings.density(value)
        current.update { it.copy(density = value) }
    }

    fun clusterMedia(enabled: Boolean) {
        settings.clusterMedia(enabled)
        current.update { it.copy(clusterMedia = enabled) }
    }

    fun navigationEnabled(enabled: Boolean) {
        if (current.value.changingNavigation) return
        current.update { it.copy(changingNavigation = true, message = null) }
        navigation.setEnabled(enabled) { message ->
            val actual = navigation.enabled()
            if (!actual) events.publishNavigation(DisplayEventBus.Navigation.EMPTY)
            current.update { it.copy(navigationEnabled = actual, changingNavigation = false, message = message) }
        }
    }

    fun report(message: String) = current.update { it.copy(message = message) }
}
