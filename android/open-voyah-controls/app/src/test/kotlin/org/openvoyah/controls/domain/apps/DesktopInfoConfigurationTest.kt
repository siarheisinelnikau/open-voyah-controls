package org.openvoyah.controls.domain.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopInfoConfigurationTest {
    @Test fun phoneFavoritesKeepAnIndependentBoundedOrder() {
        val main = AppGridDesktop.MAIN
        val favorite = AppGridDesktop.FAVORITES_ONE
        val phone = DesktopInfoCard.PHONE
        val ids = listOf("Legacy.A", "contact-b", "contact-c")
        val configured = DesktopInfoConfiguration().withPhoneFavorites(main, ids)
            .withPhoneFavorites(favorite, listOf("other")).set(main, phone, DesktopInfoSize.WIDE)
        assertEquals(ids, configured.tiles(main).single().phoneFavorites)
        assertEquals(listOf("other"), configured.phoneFavoritesOf(favorite))
        assertEquals(ids, configured.set(main, phone, null).phoneFavoritesOf(main))
        assertEquals(ids, PhoneFavoriteEdit.ADD.applyTo(ids, "another"))
        assertEquals(ids, PhoneFavoriteEdit.MOVE_UP.applyTo(ids, ids.first()))
        val moved = PhoneFavoriteEdit.MOVE_UP.applyTo(ids, ids.last())
        assertEquals(listOf("Legacy.A", "contact-c", "contact-b"), moved)
        assertEquals(listOf("contact-c", "contact-b"), PhoneFavoriteEdit.REMOVE.applyTo(moved, "Legacy.A"))
        assertTrue(runCatching { configured.withPhoneFavorites(main, listOf("duplicate", "duplicate")) }.isFailure)
    }

    @Test fun desktopCardsStartHiddenAndUseIndependentDesktopSlots() {
        val main = AppGridDesktop.MAIN
        val favorite = AppGridDesktop.FAVORITES_ONE
        val clock = DesktopInfoCard.CLOCK
        val service = DesktopInfoCard.SERVICE
        val fresh = DesktopInfoConfiguration()
        assertTrue(AppGridDesktop.entries.all { fresh.tiles(it).isEmpty() })

        val configuration = fresh
            .set(main, service, DesktopInfoSize.WIDE)
            .set(main, clock, DesktopInfoSize.COMPACT)
            .set(favorite, clock, DesktopInfoSize.WIDE)

        assertEquals(listOf(clock, service), configuration.tiles(main).map { it.card })
        assertEquals(listOf(DesktopInfoTile(clock, DesktopInfoSize.WIDE)), configuration.tiles(favorite))
        assertNull(configuration.sizeOf(favorite, service))
        assertEquals(fresh, configuration.set(main, clock, null).set(main, service, null).set(favorite, clock, null))
    }

    @Test fun wideCardsUseTwoColumnsWhenTheViewportAllowsIt() {
        assertEquals(1, DesktopInfoSize.COMPACT.span(4))
        assertEquals(2, DesktopInfoSize.WIDE.span(4))
        assertEquals(1, DesktopInfoSize.WIDE.span(1))
    }

    @Test fun presentationChoicesStayIndependentOfCardVisibilityAndDesktop() {
        val main = AppGridDesktop.MAIN
        val favorite = AppGridDesktop.FAVORITES_ONE
        val configured = DesktopInfoConfiguration().withMediaStyle(main, DesktopMediaStyle.COVER)
            .withClockFormat(main, DesktopClockFormat.HOUR_24)
        assertTrue(configured.tiles(main).isEmpty())
        assertEquals(DesktopMediaStyle.TEXT, configured.mediaStyleOf(favorite))
        assertEquals(DesktopClockFormat.SYSTEM, configured.clockFormatOf(favorite))

        val visible = configured.set(main, DesktopInfoCard.MEDIA, DesktopInfoSize.COMPACT)
            .set(main, DesktopInfoCard.CLOCK, DesktopInfoSize.WIDE)
        assertEquals(listOf(DesktopInfoTile(DesktopInfoCard.CLOCK, DesktopInfoSize.WIDE, clockFormat = DesktopClockFormat.HOUR_24),
            DesktopInfoTile(DesktopInfoCard.MEDIA, DesktopInfoSize.COMPACT, mediaStyle = DesktopMediaStyle.COVER)), visible.tiles(main))
        assertEquals(configured, visible.clearTiles())
        assertEquals(configured, visible.set(main, DesktopInfoCard.MEDIA, null).set(main, DesktopInfoCard.CLOCK, null))
    }

    @Test fun headlightContentStaysIndependentOfCardVisibilityAndDesktop() {
        val main = AppGridDesktop.MAIN
        val favorite = AppGridDesktop.FAVORITES_ONE
        val headlights = DesktopInfoCard.HEADLIGHTS
        val fresh = DesktopInfoConfiguration()
        val configured = fresh.withHeadlightContent(main, DesktopHeadlightContent.LAMP_ACTIVITY)
        assertTrue(configured.tiles(main).isEmpty())
        assertEquals(DesktopHeadlightContent.SWITCH_POSITION, configured.headlightContentOf(favorite))

        val visible = configured.set(main, headlights, DesktopInfoSize.COMPACT)
            .set(favorite, headlights, DesktopInfoSize.WIDE)
        assertEquals(DesktopHeadlightContent.LAMP_ACTIVITY, visible.tiles(main).single().headlightContent)
        assertEquals(DesktopHeadlightContent.SWITCH_POSITION, visible.tiles(favorite).single().headlightContent)
        assertEquals(configured, visible.clearTiles())
        assertEquals(fresh, configured.withHeadlightContent(main, DesktopHeadlightContent.SWITCH_POSITION))
    }

    @Test fun preferredMediaAppIsIndependentOfCardStyleVisibilityAndDesktop() {
        val main = AppGridDesktop.MAIN
        val favorite = AppGridDesktop.FAVORITES_TWO
        val media = DesktopInfoCard.MEDIA
        val fresh = DesktopInfoConfiguration()
        assertTrue(AppGridDesktop.entries.all { fresh.mediaPackageOf(it).isEmpty() })

        val configured = fresh.withMediaPackage(main, "org.example.music").withMediaPackage(favorite, "org.example.radio")
            .withMediaStyle(main, DesktopMediaStyle.COVER).set(main, media, DesktopInfoSize.COMPACT)
            .set(favorite, media, DesktopInfoSize.WIDE)
        assertEquals(DesktopInfoTile(media, DesktopInfoSize.COMPACT, mediaStyle = DesktopMediaStyle.COVER,
            preferredMediaPackage = "org.example.music"), configured.tiles(main).single())
        assertEquals(DesktopInfoTile(media, DesktopInfoSize.WIDE, preferredMediaPackage = "org.example.radio"),
            configured.tiles(favorite).single())
        assertEquals("org.example.music", configured.clearTiles().mediaPackageOf(main))
        assertEquals("org.example.music", configured.set(main, media, null).mediaPackageOf(main))
        assertEquals("", configured.withMediaPackage(main, "").tiles(main).single().preferredMediaPackage)
        assertTrue(runCatching { configured.withMediaPackage(main, "content://org.example.music") }.isFailure)
    }

    @Test fun mediaOpenTargetIsIndependentOfStyleSourceVisibilityAndDesktop() {
        val main = AppGridDesktop.MAIN
        val favorite = AppGridDesktop.FAVORITES_ONE
        val otherFavorite = AppGridDesktop.FAVORITES_TWO
        val media = DesktopInfoCard.MEDIA
        val hidden = DesktopInfoConfiguration()
            .withMediaOpenTarget(main, DesktopMediaOpenTarget.PLAYER)
            .withMediaOpenTarget(favorite, DesktopMediaOpenTarget.DISPLAY)
            .withMediaStyle(main, DesktopMediaStyle.COVER)
            .withMediaPackage(main, "org.example.music")

        val visible = hidden.set(main, media, DesktopInfoSize.COMPACT).set(favorite, media, DesktopInfoSize.WIDE)
        assertEquals(DesktopInfoTile(media, DesktopInfoSize.COMPACT, mediaStyle = DesktopMediaStyle.COVER,
            preferredMediaPackage = "org.example.music", mediaOpenTarget = DesktopMediaOpenTarget.PLAYER),
            visible.tiles(main).single())
        assertEquals(DesktopMediaOpenTarget.DISPLAY, visible.tiles(favorite).single().mediaOpenTarget)
        assertEquals(DesktopMediaOpenTarget.NONE, visible.mediaOpenTargetOf(otherFavorite))
        assertEquals(hidden, visible.clearTiles())

        val reset = visible.withMediaOpenTarget(main, DesktopMediaOpenTarget.NONE).tiles(main).single()
        assertEquals(visible.tiles(main).single().copy(mediaOpenTarget = DesktopMediaOpenTarget.NONE), reset)
    }
}
