package org.openvoyah.controls.data.display

import android.content.Context
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject
import org.openvoyah.controls.domain.display.DisplayPreviewArrangement
import org.openvoyah.controls.domain.display.DisplayPreviewContent
import org.openvoyah.controls.domain.display.DisplayPreviewDistanceUnits
import org.openvoyah.controls.domain.display.DisplayPreviewMediaSubtitle
import org.openvoyah.controls.domain.display.DisplayPreviewPalette
import org.openvoyah.controls.domain.display.DisplayPreviewPaneBalance
import org.openvoyah.controls.domain.display.DisplayPreviewPaneOrder
import org.openvoyah.controls.domain.display.DisplayPreviewRoadNames
import org.openvoyah.controls.domain.display.PreviewMediaFallback
import org.openvoyah.controls.domain.display.PreviewSeekStep
import org.openvoyah.controls.domain.display.validMediaPackage
import org.openvoyah.controls.domain.settings.AppRoleTextSize

internal class DisplayPreviewPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun load(): DisplayPreviewContent {
        val defaults = DisplayPreviewContent()
        val value = preferences.all[CONTENT] as? String ?: return defaults
        return try { JSONObject(value).mergeInto(defaults) }
        catch (_: JSONException) { defaults }
    }

    fun update(change: (DisplayPreviewContent) -> DisplayPreviewContent): DisplayPreviewContent = synchronized(lock) {
        val previous = load()
        val next = change(previous)
        require(validMediaPackage(next.preferredMediaPackage))
        if (next != previous) {
            val original = preferences.all[CONTENT] as? String
            if (!preferences.edit().putString(CONTENT, next.encode().toString()).commit()) {
                preferences.edit().putString(CONTENT, original).commit()
                throw IOException("Display preview preferences could not be written")
            }
        }
        next
    }

    fun exportSettings(): JSONObject = load().encode()

    fun restoreSettings(value: JSONObject) {
        val verified = validateSettings(value)
        update { verified.mergeInto(it) }
    }

    companion object {
        const val STORE = "display_preview"
        private const val CONTENT = "content"
        private const val MEDIA = "showMedia"
        private const val NAVIGATION = "showNavigation"
        private const val PLAYBACK_PROGRESS = "showPlaybackProgress"
        private const val ALBUM_ARTWORK = "showAlbumArtwork"
        private const val TRANSPORT_CONTROLS = "showTransportControls"
        private const val MEDIA_PACKAGE = "preferredMediaPackage"
        private const val ARRANGEMENT = "arrangement"
        private const val PANE_ORDER = "paneOrder"
        private const val SEEK_STEP = "seekStepSeconds"
        private const val MEDIA_SUBTITLE = "mediaSubtitle"
        private const val NEXT_ROAD = "showNextRoad"
        private const val TURN_DISTANCE = "showTurnDistance"
        private const val HIDE_EMPTY_PANES = "hideEmptyPanes"
        private const val PALETTE = "palette"
        private const val PANE_BALANCE = "paneBalance"
        private const val DISTANCE_UNITS = "distanceUnits"
        private const val ROAD_NAMES = "roadNames"
        private const val CURRENT_ROAD = "showCurrentRoad"
        private const val TEXT_SIZE = "textSize"
        private const val MEDIA_FALLBACK = "mediaFallback"
        private val lock = Any()

        @JvmStatic
        fun validateSettings(value: JSONObject): JSONObject {
            val verified = JSONObject()
            for (field in value.keys()) {
                val choice = value.get(field)
                when (field) {
                    MEDIA, NAVIGATION, PLAYBACK_PROGRESS, ALBUM_ARTWORK, TRANSPORT_CONTROLS,
                    CURRENT_ROAD, NEXT_ROAD, TURN_DISTANCE, HIDE_EMPTY_PANES -> require(choice is Boolean)
                    MEDIA_PACKAGE -> require(choice is String && validMediaPackage(choice))
                    ARRANGEMENT -> require(choice is String && DisplayPreviewArrangement.find(choice) != null)
                    PANE_ORDER -> require(choice is String && DisplayPreviewPaneOrder.find(choice) != null)
                    SEEK_STEP -> require(choice is Int && PreviewSeekStep.find(choice) != null)
                    MEDIA_SUBTITLE -> require(choice is String && DisplayPreviewMediaSubtitle.find(choice) != null)
                    PALETTE -> require(choice is String && DisplayPreviewPalette.find(choice) != null)
                    PANE_BALANCE -> require(choice is String && DisplayPreviewPaneBalance.find(choice) != null)
                    DISTANCE_UNITS -> require(choice is String && DisplayPreviewDistanceUnits.find(choice) != null)
                    ROAD_NAMES -> require(choice is String && DisplayPreviewRoadNames.find(choice) != null)
                    TEXT_SIZE -> require(choice is String && textSize(choice) != null)
                    MEDIA_FALLBACK -> require(choice is String && PreviewMediaFallback.find(choice) != null)
                    else -> error("Unknown display preview option")
                }
                verified.put(field, choice)
            }
            return verified
        }

        private fun JSONObject.mergeInto(current: DisplayPreviewContent) = DisplayPreviewContent(
            showMedia = opt(MEDIA) as? Boolean ?: current.showMedia,
            showNavigation = opt(NAVIGATION) as? Boolean ?: current.showNavigation,
            showPlaybackProgress = opt(PLAYBACK_PROGRESS) as? Boolean ?: current.showPlaybackProgress,
            showAlbumArtwork = opt(ALBUM_ARTWORK) as? Boolean ?: current.showAlbumArtwork,
            showTransportControls = opt(TRANSPORT_CONTROLS) as? Boolean ?: current.showTransportControls,
            preferredMediaPackage = (opt(MEDIA_PACKAGE) as? String)?.takeIf(::validMediaPackage)
                ?: current.preferredMediaPackage,
            arrangement = DisplayPreviewArrangement.find(opt(ARRANGEMENT) as? String) ?: current.arrangement,
            paneOrder = DisplayPreviewPaneOrder.find(opt(PANE_ORDER) as? String) ?: current.paneOrder,
            seekStep = PreviewSeekStep.find(opt(SEEK_STEP) as? Int) ?: current.seekStep,
            mediaSubtitle = DisplayPreviewMediaSubtitle.find(opt(MEDIA_SUBTITLE) as? String) ?: current.mediaSubtitle,
            showNextRoad = opt(NEXT_ROAD) as? Boolean ?: current.showNextRoad,
            showTurnDistance = opt(TURN_DISTANCE) as? Boolean ?: current.showTurnDistance,
            hideEmptyPanes = opt(HIDE_EMPTY_PANES) as? Boolean ?: current.hideEmptyPanes,
            palette = DisplayPreviewPalette.find(opt(PALETTE) as? String) ?: current.palette,
            paneBalance = DisplayPreviewPaneBalance.find(opt(PANE_BALANCE) as? String) ?: current.paneBalance,
            distanceUnits = DisplayPreviewDistanceUnits.find(opt(DISTANCE_UNITS) as? String) ?: current.distanceUnits,
            roadNames = DisplayPreviewRoadNames.find(opt(ROAD_NAMES) as? String) ?: current.roadNames,
            showCurrentRoad = opt(CURRENT_ROAD) as? Boolean ?: current.showCurrentRoad,
            textSize = textSize(opt(TEXT_SIZE) as? String) ?: current.textSize,
            mediaFallback = PreviewMediaFallback.find(opt(MEDIA_FALLBACK) as? String) ?: current.mediaFallback,
        )

        private fun DisplayPreviewContent.encode() = JSONObject().put(MEDIA, showMedia).put(NAVIGATION, showNavigation)
            .put(PLAYBACK_PROGRESS, showPlaybackProgress).put(ALBUM_ARTWORK, showAlbumArtwork)
            .put(TRANSPORT_CONTROLS, showTransportControls).put(MEDIA_PACKAGE, preferredMediaPackage)
            .put(ARRANGEMENT, arrangement.storedValue).put(PANE_ORDER, paneOrder.storedValue).put(SEEK_STEP, seekStep.seconds)
            .put(MEDIA_SUBTITLE, mediaSubtitle.storedValue).put(NEXT_ROAD, showNextRoad)
            .put(TURN_DISTANCE, showTurnDistance).put(HIDE_EMPTY_PANES, hideEmptyPanes)
            .put(PALETTE, palette.storedValue).put(PANE_BALANCE, paneBalance.storedValue)
            .put(DISTANCE_UNITS, distanceUnits.storedValue)
            .put(ROAD_NAMES, roadNames.storedValue).put(CURRENT_ROAD, showCurrentRoad)
            .put(TEXT_SIZE, textSize.preferenceId).put(MEDIA_FALLBACK, mediaFallback.storedValue)

        private fun textSize(value: String?): AppRoleTextSize? = AppRoleTextSize.entries.firstOrNull { it.preferenceId == value }
    }
}
