package org.openvoyah.controls

import android.content.Context
import android.test.AndroidTestCase
import org.json.JSONObject
import org.openvoyah.controls.data.display.DisplayPreviewPreferences
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
import org.openvoyah.controls.domain.settings.AppRoleTextSize

class DisplayPreviewPreferencesTest : AndroidTestCase() {
    fun testDefaultsAndPortablePartialRestore() {
        val store = context.applicationContext.getSharedPreferences(DisplayPreviewPreferences.STORE, Context.MODE_PRIVATE)
        val key = "content"
        val original = store.getString(key, null)
        try {
            check(store.edit().remove(key).commit())
            val local = DisplayPreviewPreferences(context)
            assertEquals(DisplayPreviewContent(), local.load())
            local.update { it.copy(showMedia = false, showPlaybackProgress = true) }

            val another = DisplayPreviewPreferences(context)
            assertEquals(DisplayPreviewContent(showMedia = false, showPlaybackProgress = true), another.load())
            val originalArchive = another.exportSettings()
            assertTrue(originalArchive.getBoolean("showPlaybackProgress"))
            val partial = DisplayPreviewPreferences.validateSettings(JSONObject().put("showNavigation", false))
            assertFalse(partial.has("showMedia"))
            assertFalse(partial.has("showPlaybackProgress"))
            another.restoreSettings(partial)
            assertEquals(DisplayPreviewContent(showMedia = false, showNavigation = false, showPlaybackProgress = true), local.load())
            local.restoreSettings(JSONObject().put("showMedia", true).put("showPlaybackProgress", false))
            assertEquals(DisplayPreviewContent(showNavigation = false, showPlaybackProgress = false), another.load())
            local.restoreSettings(originalArchive)
            assertEquals(DisplayPreviewContent(showMedia = false, showPlaybackProgress = true), another.load())
        } finally {
            assertTrue(store.edit().putString(key, original).commit())
        }
    }

    fun testArchiveRejectsUnknownFieldsAndNonBooleanValues() {
        val invalid = listOf(
            JSONObject().put("showMedia", "false"),
            JSONObject().put("showNavigation", JSONObject.NULL),
            JSONObject().put("showPlaybackProgress", 1),
            JSONObject().put("showAlbumArtwork", "true"),
            JSONObject().put("showTransportControls", JSONObject.NULL),
            JSONObject().put("preferredMediaPackage", false),
            JSONObject().put("preferredMediaPackage", "com.example.player/.PlayerActivity"),
            JSONObject().put("arrangement", "manual"),
            JSONObject().put("arrangement", 1),
            JSONObject().put("paneOrder", "alternate"),
            JSONObject().put("paneOrder", JSONObject.NULL),
            JSONObject().put("seekStepSeconds", "30"),
            JSONObject().put("seekStepSeconds", 30.0),
            JSONObject().put("seekStepSeconds", 15),
            JSONObject().put("mediaSubtitle", "titleOnly"),
            JSONObject().put("mediaSubtitle", JSONObject.NULL),
            JSONObject().put("showCurrentRoad", "false"),
            JSONObject().put("showCurrentRoad", JSONObject.NULL),
            JSONObject().put("showNextRoad", "true"),
            JSONObject().put("showTurnDistance", 1),
            JSONObject().put("hideEmptyPanes", JSONObject.NULL),
            JSONObject().put("palette", "night"),
            JSONObject().put("palette", true),
            JSONObject().put("paneBalance", "left"),
            JSONObject().put("paneBalance", JSONObject.NULL),
            JSONObject().put("distanceUnits", "auto"),
            JSONObject().put("distanceUnits", 0),
            JSONObject().put("roadNames", "translated"),
            JSONObject().put("roadNames", JSONObject.NULL),
            JSONObject().put("textSize", "smallest"),
            JSONObject().put("textSize", 2),
            JSONObject().put("mediaFallback", "skip"),
            JSONObject().put("mediaFallback", JSONObject.NULL),
            JSONObject().put("previewMedia", true),
        )
        for (settings in invalid) {
            assertTrue("Invalid preview settings accepted: $settings",
                runCatching { DisplayPreviewPreferences.validateSettings(settings) }.isFailure)
        }
    }

    fun testPreviewDetailsRoundTripAndLegacyArchiveKeepsSavedDetails() {
        val store = context.applicationContext.getSharedPreferences(DisplayPreviewPreferences.STORE, Context.MODE_PRIVATE)
        val key = "content"
        val original = store.getString(key, null)
        try {
            val legacy = JSONObject().put("arrangement", "stacked")
            check(store.edit().putString(key, legacy.toString()).commit())
            val preview = DisplayPreviewPreferences(context)
            val defaults = DisplayPreviewContent(arrangement = DisplayPreviewArrangement.STACKED)
            assertEquals(defaults, preview.load())

            val saved = preview.update { it.copy(mediaSubtitle = DisplayPreviewMediaSubtitle.ALBUM,
                showCurrentRoad = false, showNextRoad = false, showTurnDistance = false, hideEmptyPanes = true,
                palette = DisplayPreviewPalette.DAYLIGHT, paneBalance = DisplayPreviewPaneBalance.MEDIA,
                distanceUnits = DisplayPreviewDistanceUnits.IMPERIAL, roadNames = DisplayPreviewRoadNames.LATIN,
                textSize = AppRoleTextSize.EXTRA_LARGE, mediaFallback = PreviewMediaFallback.PREFERRED_ONLY) }
            val archive = DisplayPreviewPreferences.validateSettings(preview.exportSettings())
            assertEquals("album", archive.getString("mediaSubtitle"))
            assertFalse(archive.getBoolean("showCurrentRoad"))
            assertFalse(archive.getBoolean("showNextRoad"))
            assertFalse(archive.getBoolean("showTurnDistance"))
            assertTrue(archive.getBoolean("hideEmptyPanes"))
            assertEquals("daylight", archive.getString("palette"))
            assertEquals("media", archive.getString("paneBalance"))
            assertEquals("imperial", archive.getString("distanceUnits"))
            assertEquals("latin", archive.getString("roadNames"))
            assertEquals("extra_large", archive.getString("textSize"))
            assertEquals("preferredOnly", archive.getString("mediaFallback"))
            assertEquals(saved, DisplayPreviewPreferences(context).load())

            preview.restoreSettings(legacy)
            assertEquals(saved, preview.load())
            preview.restoreSettings(JSONObject().put("mediaSubtitle", "none").put("showNextRoad", true))
            assertEquals(saved.copy(mediaSubtitle = DisplayPreviewMediaSubtitle.NONE, showNextRoad = true), preview.load())
            preview.restoreSettings(JSONObject().put("paneBalance", "navigation"))
            assertEquals(saved.copy(mediaSubtitle = DisplayPreviewMediaSubtitle.NONE, showNextRoad = true,
                paneBalance = DisplayPreviewPaneBalance.NAVIGATION), preview.load())
            preview.restoreSettings(JSONObject().put("mediaSubtitle", "artist").put("showTurnDistance", true)
                .put("hideEmptyPanes", false).put("palette", "cockpit").put("paneBalance", "equal")
                .put("distanceUnits", "metric").put("roadNames", "original").put("showCurrentRoad", true)
                .put("textSize", "follow").put("mediaFallback", "automatic"))
            assertEquals(defaults, preview.load())
            preview.restoreSettings(archive)
            assertEquals(saved, DisplayPreviewPreferences(context).load())
        } finally {
            assertTrue(store.edit().putString(key, original).commit())
        }
    }

    fun testArrangementOrderAndSeekIntervalRoundTripAndLegacySettingsKeepDefaults() {
        val store = context.applicationContext.getSharedPreferences(DisplayPreviewPreferences.STORE, Context.MODE_PRIVATE)
        val key = "content"
        val original = store.getString(key, null)
        try {
            val legacy = JSONObject().put("showPlaybackProgress", true)
            check(store.edit().putString(key, legacy.toString()).commit())
            val preview = DisplayPreviewPreferences(context)
            assertEquals(DisplayPreviewContent(showPlaybackProgress = true), preview.load())

            val custom = preview.update { it.copy(arrangement = DisplayPreviewArrangement.STACKED,
                paneOrder = DisplayPreviewPaneOrder.ROUTE_FIRST, seekStep = PreviewSeekStep.THIRTY) }
            val archive = DisplayPreviewPreferences.validateSettings(preview.exportSettings())
            assertEquals("stacked", archive.getString("arrangement"))
            assertEquals("routeFirst", archive.getString("paneOrder"))
            assertEquals(30, archive.getInt("seekStepSeconds"))
            assertEquals(custom, DisplayPreviewPreferences(context).load())

            preview.restoreSettings(legacy)
            preview.restoreSettings(JSONObject().put("arrangement", "sideBySide"))
            assertEquals(custom.copy(arrangement = DisplayPreviewArrangement.SIDE_BY_SIDE), preview.load())
            preview.restoreSettings(JSONObject().put("seekStepSeconds", 5))
            assertEquals(custom.copy(arrangement = DisplayPreviewArrangement.SIDE_BY_SIDE, seekStep = PreviewSeekStep.FIVE),
                preview.load())
            preview.restoreSettings(archive)
            assertEquals(custom, DisplayPreviewPreferences(context).load())
        } finally {
            assertTrue(store.edit().putString(key, original).commit())
        }
    }

    fun testPlayerPreferencesArePortableEvenWhenThePackageIsAbsent() {
        val store = context.applicationContext.getSharedPreferences(DisplayPreviewPreferences.STORE, Context.MODE_PRIVATE)
        val key = "content"
        val original = store.getString(key, null)
        try {
            check(store.edit().remove(key).commit())
            val preview = DisplayPreviewPreferences(context)
            val player = DisplayPreviewContent(showAlbumArtwork = true, preferredMediaPackage = "org.example.savedplayer")
            preview.update { player }
            val exported = preview.exportSettings()
            assertTrue(exported.getBoolean("showAlbumArtwork"))
            assertFalse(exported.getBoolean("showTransportControls"))
            assertEquals(player.preferredMediaPackage, exported.getString("preferredMediaPackage"))

            preview.restoreSettings(JSONObject().put("showPlaybackProgress", true))
            assertEquals(player.copy(showPlaybackProgress = true), DisplayPreviewPreferences(context).load())
            preview.restoreSettings(JSONObject().put("showTransportControls", true).put("preferredMediaPackage", ""))
            assertEquals(player.copy(showPlaybackProgress = true, showTransportControls = true, preferredMediaPackage = ""),
                DisplayPreviewPreferences(context).load())
            preview.restoreSettings(exported)
            assertEquals(player, DisplayPreviewPreferences(context).load())
        } finally {
            assertTrue(store.edit().putString(key, original).commit())
        }
    }
}
