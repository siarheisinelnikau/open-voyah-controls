package org.openvoyah.controls.presentation.display

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.core.display.DisplayEventBus
import org.openvoyah.controls.core.display.PlaybackProgress
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
import org.openvoyah.controls.domain.settings.AppRoleTextSize
import org.openvoyah.controls.domain.settings.AppTextSize
import org.openvoyah.controls.platform.media.PreviewMediaSnapshot
import org.openvoyah.controls.presentation.theme.cockpitTypography

class DisplayPreviewStateTest {
    private val media = DisplayEventBus.Media("Night drive", "Demo artist", "Evening collection", true)
    private val navigation = DisplayEventBus.Navigation("Park Road", "Next Street", 400)
    private val snapshot = DisplayEventBus.Snapshot(media, navigation)

    @Test fun mediaAndRouteVisibilityAreIndependent() {
        val track = DisplayPreviewTrack("Night drive",
            DisplayPreviewTrack.Subtitle("Demo artist", DisplayPreviewMediaSubtitle.ARTIST))
        val route = "Park Road\nNext Street · 400 m"
        assertEquals(DisplayPreviewState(track, route), snapshot.toPreviewState(DisplayPreviewContent()))
        assertEquals(DisplayPreviewState(null, route), snapshot.toPreviewState(DisplayPreviewContent(showMedia = false)))
        assertEquals(DisplayPreviewState(track, null), snapshot.toPreviewState(DisplayPreviewContent(showNavigation = false)))
        assertEquals(DisplayPreviewState(null, null),
            snapshot.toPreviewState(DisplayPreviewContent(showMedia = false, showNavigation = false)))
    }

    @Test fun hiddenUpdatesLeaveTheProjectedStateEqual() {
        val routeOnly = DisplayPreviewContent(showMedia = false)
        val mediaOnly = DisplayPreviewContent(showNavigation = false)
        val newTrack = DisplayEventBus.Snapshot(DisplayEventBus.Media.EMPTY, navigation)
        val newRoute = DisplayEventBus.Snapshot(media, DisplayEventBus.Navigation.EMPTY)
        assertEquals(snapshot.toPreviewState(routeOnly), newTrack.toPreviewState(routeOnly))
        assertEquals(snapshot.toPreviewState(mediaOnly), newRoute.toPreviewState(mediaOnly))
        assertEquals(DisplayPreviewState(),
            DisplayEventBus().snapshot().toPreviewState(DisplayPreviewContent()))
    }

    @Test fun aNavigationLaunchRemainsLabeledInTheVirtualDisplay() {
        val opened = DisplayEventBus.Navigation.opened("org.example.navigator")
        assertFalse(opened.routeUpdate())
        val snapshot = DisplayEventBus.Snapshot(media, opened)
        assertEquals("org.example.navigator", snapshot.toPreviewState(DisplayPreviewContent()).route)
        assertEquals(DisplayEventBus.Navigation.EMPTY, DisplayEventBus.Navigation.opened(""))
    }

    @Test fun playbackProgressRequiresBothMediaAndProgressOptions() {
        val progress = PlaybackProgress(10_000, 1000, 3000, 1f)
        val playing = DisplayEventBus.Snapshot(media, navigation, progress)
        val enabled = DisplayPreviewContent(showPlaybackProgress = true)
        val mediaHidden = enabled.copy(showMedia = false)
        val defaults = DisplayPreviewContent()
        assertEquals(progress, playing.toPreviewState(enabled).playbackProgress)
        assertEquals(snapshot.toPreviewState(defaults), playing.toPreviewState(defaults))
        assertEquals(snapshot.toPreviewState(mediaHidden), playing.toPreviewState(mediaHidden))
    }

    @Test fun selectedSessionNeverInheritsProgressFromTheAutomaticTrack() {
        val progress = PlaybackProgress(10_000, 1000, 3000, 1f)
        val automatic = DisplayEventBus.Snapshot(media, navigation, progress)
        val selected = PreviewMediaSnapshot("com.player", DisplayEventBus.Media("Selected", "", "", true),
            null, null, 1L, emptySet())
        val content = DisplayPreviewContent(showPlaybackProgress = true, preferredMediaPackage = "com.player")
        val projected = automatic.toPreviewState(content, selected)
        assertEquals(DisplayPreviewTrack("Selected"), projected.media)
        assertEquals(null, projected.playbackProgress)
        assertEquals(automatic.toPreviewState(content).route, projected.route)
        assertEquals(automatic.toPreviewState(content.copy(showMedia = false)),
            automatic.toPreviewState(content.copy(showMedia = false), selected))
    }

    @Test fun strictPlayerSuppressesTheAutomaticFeedAndAnyStaleFallbackSession() {
        val progress = PlaybackProgress(10_000, 1000, 3000, 1f)
        val feed = DisplayEventBus.Snapshot(media, navigation, progress)
        val fallback = PreviewMediaSnapshot("com.player.fallback", media, progress, null, 9L, setOf(PreviewMediaAction.NEXT))
        val options = DisplayPreviewContent(preferredMediaPackage = "com.player.selected",
            showPlaybackProgress = true, showAlbumArtwork = true, mediaFallback = PreviewMediaFallback.PREFERRED_ONLY)
        val strict = feed.toPreviewState(options, fallback)
        assertTrue(strict.preferredPlayerUnavailable)
        assertEquals("com.player.selected", strict.strictPlayerPackage)
        assertEquals(DisplayPreviewTrack(""), strict.media)
        assertEquals(null, strict.playbackProgress)
        assertEquals(null, strict.albumArtwork)
        assertEquals(strict, feed.toPreviewState(options))

        val hidden = feed.toPreviewState(options.copy(hideEmptyPanes = true), fallback)
        assertEquals(null, hidden.media)
        assertFalse(hidden.preferredPlayerUnavailable)

        val selected = fallback.copy(packageName = "com.player.selected")
        val matching = feed.toPreviewState(options, selected)
        assertFalse(matching.preferredPlayerUnavailable)
        assertEquals("Night drive", matching.media?.title)
        assertEquals(progress, matching.playbackProgress)
        assertEquals(progress, feed.toPreviewState(options.copy(preferredMediaPackage = "")).playbackProgress)
    }

    @Test fun previewTextSizeOverridesAppSizeWhileTheDeviceFontScaleStillApplies() {
        val normal = cockpitTypography(AppTextSize.STANDARD)
        val largeApp = cockpitTypography(AppTextSize.EXTRA_LARGE, bold = true)
        assertEquals(largeApp, previewTypography(largeApp, AppRoleTextSize.FOLLOW))
        val standardPreview = previewTypography(largeApp, AppRoleTextSize.STANDARD)
        assertEquals(normal.titleLarge.fontSize.value, standardPreview.titleLarge.fontSize.value, .001f)
        assertEquals(normal.bodyMedium.lineHeight.value, standardPreview.bodyMedium.lineHeight.value, .001f)
        assertEquals(largeApp.titleLarge.fontWeight, standardPreview.titleLarge.fontWeight)
        val larger = previewTypography(normal, AppRoleTextSize.EXTRA_LARGE)
        val nativeDensity = previewCanvasDensity(240, 1.3f)
        val fallbackDensity = previewCanvasDensity(effectivePreviewDpi(0, 240), 1.3f)
        val nativeWidth = previewContentWidth(780, nativeDensity)
        val fallbackWidth = previewContentWidth(780, fallbackDensity)
        assertEquals(nativeWidth, fallbackWidth, .001f)
        val standardScale = previewTextScale(standardPreview, nativeDensity)
        val largeScale = previewTextScale(larger, nativeDensity)
        assertTrue(standardScale > 1f)
        assertTrue(largeScale > standardScale)
        assertEquals(largeScale, previewTextScale(larger, fallbackDensity), .001f)
        val arrangement = DisplayPreviewArrangement.AUTO
        assertEquals(arrangement.usesSideBySide(nativeWidth, largeScale, artwork = true),
            arrangement.usesSideBySide(fallbackWidth, largeScale, artwork = true))
        assertTrue(previewPaneHeight(false, largeScale, showsProgress = true, bothPanes = false) >
            previewPaneHeight(false, standardScale, showsProgress = true, bothPanes = false))
        assertEquals(AppRoleTextSize.EXTRA_LARGE,
            snapshot.toPreviewState(DisplayPreviewContent(textSize = AppRoleTextSize.EXTRA_LARGE)).textSize)
    }

    @Test fun arrangementAndOrderAffectTheProjectionOnlyWhenBothPanesAreVisible() {
        val both = DisplayPreviewContent(arrangement = DisplayPreviewArrangement.STACKED,
            paneOrder = DisplayPreviewPaneOrder.ROUTE_FIRST, showAlbumArtwork = true)
        val state = snapshot.toPreviewState(both)
        assertEquals(DisplayPreviewArrangement.STACKED, state.arrangement)
        assertEquals(DisplayPreviewPaneOrder.ROUTE_FIRST, state.paneOrder)
        assertTrue(state.reserveArtworkSpace)
        val mediaOnly = both.copy(showNavigation = false)
        assertEquals(snapshot.toPreviewState(DisplayPreviewContent(showNavigation = false)),
            snapshot.toPreviewState(mediaOnly))
    }

    @Test fun mediaSubtitleUsesOnlyTheChosenFieldOfTheDisplayedSession() {
        val defaults = DisplayPreviewContent()
        val album = defaults.copy(mediaSubtitle = DisplayPreviewMediaSubtitle.ALBUM)
        val titleOnly = defaults.copy(mediaSubtitle = DisplayPreviewMediaSubtitle.NONE)
        assertEquals(DisplayPreviewTrack("Night drive",
            DisplayPreviewTrack.Subtitle("Evening collection", DisplayPreviewMediaSubtitle.ALBUM)),
            snapshot.toPreviewState(album).media)
        assertEquals(DisplayPreviewTrack("Night drive"), snapshot.toPreviewState(titleOnly).media)

        val selected = PreviewMediaSnapshot("com.player", DisplayEventBus.Media("Selected", "Another artist", " ", false),
            null, null, 1L, emptySet())
        assertEquals(DisplayPreviewTrack("Selected"), snapshot.toPreviewState(album, selected).media)
        assertEquals(DisplayPreviewTrack("Selected",
            DisplayPreviewTrack.Subtitle("Another artist", DisplayPreviewMediaSubtitle.ARTIST)),
            snapshot.toPreviewState(defaults, selected).media)
    }

    @Test fun nextRoadAndDistanceCanBeHiddenIndependently() {
        val roadHidden = DisplayPreviewContent(showNextRoad = false)
        val distanceHidden = DisplayPreviewContent(showTurnDistance = false)
        assertEquals("Park Road\n400 m", snapshot.toPreviewState(roadHidden).route)
        assertEquals("Park Road\nNext Street", snapshot.toPreviewState(distanceHidden).route)

        val bothHidden = roadHidden.copy(showTurnDistance = false)
        val changed = DisplayEventBus.Snapshot(media, DisplayEventBus.Navigation("Park Road", "Another road", 0))
        assertEquals("Park Road", snapshot.toPreviewState(bothHidden).route)
        assertEquals(snapshot.toPreviewState(bothHidden), changed.toPreviewState(bothHidden))
        assertEquals("Park Road\n0 m", changed.toPreviewState(roadHidden).route)
        val unknownDistance = DisplayEventBus.Snapshot(media, DisplayEventBus.Navigation("Park Road", "", -1))
        assertEquals("Park Road", unknownDistance.toPreviewState(roadHidden).route)
    }

    @Test fun currentRoadVisibilityIsIndependentOfNextRoadAndDistance() {
        val hideCurrent = DisplayPreviewContent(showCurrentRoad = false)
        assertEquals("Next Street · 400 m", snapshot.toPreviewState(hideCurrent).route)
        assertEquals("400 m", snapshot.toPreviewState(hideCurrent.copy(showNextRoad = false)).route)
        assertEquals("Next Street", snapshot.toPreviewState(hideCurrent.copy(showTurnDistance = false)).route)

        val changed = DisplayEventBus.Snapshot(media, DisplayEventBus.Navigation("Another road", "Next Street", 400))
        assertEquals(snapshot.toPreviewState(hideCurrent), changed.toPreviewState(hideCurrent))
    }

    @Test fun emptyRouteDistinguishesHiddenDetailsFromMissingUpdates() {
        val hideCurrent = DisplayPreviewContent(showMedia = false, showCurrentRoad = false)
        val currentRoadOnly = DisplayEventBus.Snapshot(media, DisplayEventBus.Navigation("Park Road", "", -1))
        val unavailable = DisplayEventBus.Snapshot(media, DisplayEventBus.Navigation.EMPTY)

        assertEquals(DisplayPreviewState(media = null, routeDetailsHidden = true), currentRoadOnly.toPreviewState(hideCurrent))
        assertEquals(DisplayPreviewState(media = null), unavailable.toPreviewState(hideCurrent))
        val noDetails = hideCurrent.copy(showNextRoad = false, showTurnDistance = false)
        assertEquals(DisplayPreviewState(media = null, routeDetailsHidden = true), unavailable.toPreviewState(noDetails))
        assertEquals(DisplayPreviewState(media = null, route = null, blankWhenIdle = true),
            currentRoadOnly.toPreviewState(hideCurrent.copy(hideEmptyPanes = true)))
        assertEquals(DisplayPreviewState(media = null, route = null),
            currentRoadOnly.toPreviewState(hideCurrent.copy(showNavigation = false)))
    }

    @Test fun imperialUnitsUseRealDistancesAndTheUiLocale() {
        val imperial = DisplayPreviewContent(distanceUnits = DisplayPreviewDistanceUnits.IMPERIAL)
        assertEquals("Park Road\nNext Street · 1312 ft", snapshot.toPreviewState(imperial).route)
        val far = DisplayEventBus.Snapshot(media, DisplayEventBus.Navigation("", "Next Street", 2_000))
        assertEquals("Next Street · 1.2 mi", far.toPreviewState(imperial, locale = Locale.ENGLISH).route)
        assertEquals("Next Street · 1,2 mi", far.toPreviewState(imperial, locale = Locale.forLanguageTag("ru")).route)
        assertEquals("Next Street · 2000 m", far.toPreviewState(DisplayPreviewContent()).route)
        assertEquals("Next Street", far.toPreviewState(imperial.copy(showTurnDistance = false)).route)
        val immediate = DisplayEventBus.Snapshot(media, DisplayEventBus.Navigation("Park Road", "", 0))
        assertEquals("Park Road\n0 ft", immediate.toPreviewState(imperial).route)
        val unknown = DisplayEventBus.Snapshot(media, DisplayEventBus.Navigation("Park Road", "", -1))
        assertEquals("Park Road", unknown.toPreviewState(imperial).route)
        assertEquals(far.toPreviewState(imperial),
            DisplayEventBus.Snapshot(media, DisplayEventBus.Navigation("", "Next Street", 1_999)).toPreviewState(imperial))
    }

    @Test fun roadNameScriptFormatsBothRoadFieldsWithoutChangingOtherContent() {
        val route = DisplayEventBus.Navigation("Щёлковское шоссе M-7", "Вуліца Ўсходняя", 400)
        val event = DisplayEventBus.Snapshot(media, route)
        val original = event.toPreviewState(DisplayPreviewContent())
        assertEquals("Щёлковское шоссе M-7\nВуліца Ўсходняя · 400 m", original.route)

        val latin = DisplayPreviewContent(roadNames = DisplayPreviewRoadNames.LATIN)
        val formatted = event.toPreviewState(latin)
        assertEquals("Shchyolkovskoe shosse M-7\nVulitsa Uskhodnyaya · 400 m", formatted.route)
        assertEquals(original.media, formatted.media)
        assertEquals(route, event.navigation())
        assertEquals("Vulitsa Uskhodnyaya · 400 m", event.toPreviewState(latin.copy(showCurrentRoad = false)).route)
        assertEquals("Shchyolkovskoe shosse M-7\n400 m", event.toPreviewState(latin.copy(showNextRoad = false)).route)
        assertEquals(null, event.toPreviewState(latin.copy(showNavigation = false)).route)
    }

    @Test fun roadNameTransliterationKeepsExistingLatinTextRoadNumbersAndCapitalization() {
        val latin = DisplayPreviewRoadNames.LATIN
        assertEquals("SHCHUKINSKAYA / A-1", latin.formatRoadName("ЩУКИНСКАЯ / A-1"))
        assertEquals("vul. Yizhakevicha (Gala) № 3", latin.formatRoadName("вул. Їжакевича (Ґала) № 3"))
        assertEquals("Chūō-dōri / 中央通り M-7", latin.formatRoadName("Chūō-dōri / 中央通り M-7"))
        assertEquals("", latin.formatRoadName(""))
    }

    @Test fun occupiedPanesUseTheAvailableSpaceWhenEmptyPanesAreHidden() {
        val options = DisplayPreviewContent(hideEmptyPanes = true, arrangement = DisplayPreviewArrangement.STACKED,
            paneOrder = DisplayPreviewPaneOrder.ROUTE_FIRST, showAlbumArtwork = true)
        val hasBoth = snapshot.toPreviewState(options)
        assertEquals(DisplayPreviewArrangement.STACKED, hasBoth.arrangement)
        assertTrue(hasBoth.reserveArtworkSpace)

        val mediaOnly = DisplayEventBus.Snapshot(media, DisplayEventBus.Navigation.EMPTY).toPreviewState(options)
        assertEquals(DisplayPreviewState(media = hasBoth.media, route = null), mediaOnly)
        val routeOnly = DisplayEventBus.Snapshot(DisplayEventBus.Media.EMPTY, navigation).toPreviewState(options)
        assertEquals(DisplayPreviewState(media = null, route = hasBoth.route), routeOnly)
        val noVisibleRoute = DisplayEventBus.Snapshot(media, DisplayEventBus.Navigation("", "Next Street", 400))
        assertEquals(mediaOnly, noVisibleRoute.toPreviewState(options.copy(showNextRoad = false, showTurnDistance = false)))
    }

    @Test fun blankIdleOutputIsIndependentOfExplicitlyHiddenPanesAndDefaultPlaceholders() {
        val idle = DisplayEventBus().snapshot()
        val defaults = DisplayPreviewContent()
        assertEquals(DisplayPreviewState(), idle.toPreviewState(defaults))
        val hideEmpty = defaults.copy(hideEmptyPanes = true)
        val blank = DisplayPreviewState(null, null, blankWhenIdle = true)
        assertEquals(blank, idle.toPreviewState(hideEmpty))
        assertEquals(blank, idle.toPreviewState(hideEmpty.copy(showMedia = false)))
        assertEquals(DisplayPreviewState(null, null),
            idle.toPreviewState(hideEmpty.copy(showMedia = false, showNavigation = false)))
    }

    @Test fun arrangementProtectsReadablePaneWidthsAndHeight() {
        val auto = DisplayPreviewArrangement.AUTO
        val sideBySide = DisplayPreviewArrangement.SIDE_BY_SIDE
        val stacked = DisplayPreviewArrangement.STACKED
        assertFalse(auto.usesSideBySide(419f, 1f, artwork = false))
        assertTrue(auto.usesSideBySide(420f, 1f, artwork = true))
        assertTrue(sideBySide.usesSideBySide(360f, 1f, artwork = false))
        assertFalse(sideBySide.usesSideBySide(300f, 1f, artwork = false))
        assertFalse(sideBySide.usesSideBySide(360f, 1f, artwork = true))
        assertFalse(sideBySide.usesSideBySide(440f, 1.5f, artwork = false))
        assertTrue(sideBySide.usesSideBySide(500f, 1.5f, artwork = false))
        assertFalse(stacked.usesSideBySide(1200f, 1f, artwork = false))
        assertTrue(previewPaneHeight(false, 1f, false) > previewPaneHeight(true, 1f, false))
        assertTrue(previewPaneHeight(false, 1.5f, true) > previewPaneHeight(false, 1f, true))
    }

    @Test fun paneBalanceAppliesOnlyWhenBothPanesCanBeSideBySide() {
        val options = DisplayPreviewContent(paneBalance = DisplayPreviewPaneBalance.MEDIA)
        assertEquals(DisplayPreviewPaneBalance.MEDIA, snapshot.toPreviewState(options).paneBalance)
        assertEquals(DisplayPreviewPaneBalance.MEDIA,
            snapshot.toPreviewState(options.copy(paneOrder = DisplayPreviewPaneOrder.ROUTE_FIRST)).paneBalance)
        assertEquals(DisplayPreviewPaneBalance.EQUAL,
            snapshot.toPreviewState(options.copy(arrangement = DisplayPreviewArrangement.STACKED)).paneBalance)
        assertEquals(DisplayPreviewPaneBalance.EQUAL, snapshot.toPreviewState(options.copy(showMedia = false)).paneBalance)
        val noRoute = DisplayEventBus.Snapshot(media, DisplayEventBus.Navigation.EMPTY)
        assertEquals(DisplayPreviewPaneBalance.EQUAL,
            noRoute.toPreviewState(options.copy(hideEmptyPanes = true)).paneBalance)
    }

    @Test fun emphasizedPanesRespectTheMinimumReadableWidth() {
        val equal = DisplayPreviewPaneBalance.EQUAL
        val mediaEmphasis = DisplayPreviewPaneBalance.MEDIA
        val routeEmphasis = DisplayPreviewPaneBalance.NAVIGATION
        assertEquals(.5f, equal.mediaShare(620f, 1f, artwork = true), .0001f)
        assertEquals(.6f, mediaEmphasis.mediaShare(620f, 1f, artwork = true), .0001f)
        assertEquals(.4f, routeEmphasis.mediaShare(620f, 1f, artwork = true), .0001f)
        assertEquals(.51f, mediaEmphasis.mediaShare(420f, 1f, artwork = true), .0001f)
        assertEquals(.49f, routeEmphasis.mediaShare(420f, 1f, artwork = true), .0001f)
        assertEquals(.5f, routeEmphasis.mediaShare(524f, 1.5f, artwork = true), .0001f)
    }

    @Test fun daylightPaletteKeepsTextAndProgressReadableEvenWithNoPanes() {
        val hidden = DisplayPreviewContent(showMedia = false, showNavigation = false)
        assertEquals(DisplayPreviewPalette.COCKPIT, snapshot.toPreviewState(hidden).palette)
        val daylight = snapshot.toPreviewState(hidden.copy(palette = DisplayPreviewPalette.DAYLIGHT))
        assertEquals(DisplayPreviewPalette.DAYLIGHT, daylight.palette)

        val colors = DaylightPreviewColors
        assertTrue(contrast(colors.onBackground, colors.background) >= 4.5f)
        assertTrue(contrast(colors.onSurfaceVariant, colors.background) >= 4.5f)
        assertTrue(contrast(colors.primary, colors.background) >= 4.5f)
        assertTrue(contrast(colors.primary, colors.secondaryContainer) >= 3f)
        assertTrue(contrast(colors.secondaryContainer, colors.background) >= 3f)
    }

    private fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
    }
}
