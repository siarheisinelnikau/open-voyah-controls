package org.openvoyah.controls.domain.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPickerChoicesTest {
    private data class Row(val id: String, val system: Boolean)

    private val media = Row("org.example.media/org.example.media.Main", false)
    private val maps = Row("org.example.maps/org.example.maps.Main", false)
    private val radio = Row("com.maker.radio/com.maker.radio.Main", true)
    private val keyboard = Row("com.maker.keyboard/com.maker.keyboard.Settings", true)
    private val rows = listOf(media, maps, radio, keyboard)

    private fun offered(grid: AppGridConfiguration?, showAll: Boolean = false, keep: Set<String> = emptySet()) =
        AppPickerChoices.offered(rows, Row::id, Row::system, grid, showAll, keep)

    @Test fun firmwareAndServiceAppsAreLeftOutByDefault() {
        assertEquals(listOf(media, maps), offered(AppGridConfiguration()))
        // Unreadable grid settings fall back to the same defaults rather than offering everything.
        assertEquals(listOf(media, maps), offered(null))
        assertTrue(AppPickerChoices.hidesAny(rows, Row::id, Row::system, AppGridConfiguration()))
    }

    @Test fun theUsersHideChoiceWinsEitherWay() {
        val grid = AppGridConfiguration()
            .withHidden(radio.id, hidden = false, hiddenByDefault = true)
            .withHidden(maps.id, hidden = true, hiddenByDefault = false)
        assertEquals(listOf(media, radio), offered(grid))
    }

    @Test fun turningOffHideByDefaultOffersSystemAppsTheUserDidNotHide() {
        val grid = AppGridConfiguration(hideByDefault = false, hiddenApps = setOf(keyboard.id))
        assertEquals(listOf(media, maps, radio), offered(grid))
    }

    @Test fun showAllOffersEveryAppAndKeptChoicesStay() {
        assertEquals(rows, offered(AppGridConfiguration(), showAll = true))
        assertEquals(listOf(media, maps, radio), offered(AppGridConfiguration(), keep = setOf(radio.id)))
        assertFalse(AppPickerChoices.hidesAny(listOf(media, maps), Row::id, Row::system, AppGridConfiguration()))
        assertFalse(AppPickerChoices.hidesAny(rows, Row::id, Row::system, AppGridConfiguration(), setOf(radio.id, keyboard.id)))
    }
}
