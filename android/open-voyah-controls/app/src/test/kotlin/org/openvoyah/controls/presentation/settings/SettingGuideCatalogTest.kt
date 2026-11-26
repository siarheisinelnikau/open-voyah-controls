package org.openvoyah.controls.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.R
import org.openvoyah.controls.domain.display.PassengerWorkspace
import org.openvoyah.controls.presentation.theme.GuideFrame
import org.openvoyah.controls.presentation.theme.settingGuides

class SettingGuideCatalogTest {
    @Test
    fun eachTitleOwnsACompleteDistinctComparison() {
        assertEquals(settingGuides.size, settingGuides.values.map { it.copyRes }.toSet().size)
        val comparisons = mutableMapOf<Pair<GuideFrame, GuideFrame>, Int>()
        for ((title, guide) in settingGuides) {
            val scene = guide.buildStory(0)
            for (frame in listOfNotNull(scene.before, scene.event, scene.after)) {
                assertFalse("Empty frame for $title", frame.isEmpty())
                assertEquals("Duplicate layer key for $title", frame.size, frame.distinctBy { it.key }.size)
                assertTrue("Invalid dimensions for $title", frame.all { it.width > 0f && it.height > 0f })
            }
            assertNotEquals("Identical before and after for $title", scene.before, scene.after)
            val previous = comparisons.put(scene.before to scene.after, title)
            assertTrue("The same comparison is used for $previous and $title", previous == null)
        }
    }

    @Test
    fun workspaceExamplesPointToTheSlotBeingEdited() {
        val groups = mapOf(
            R.string.passenger_workspace_app_slot to PassengerWorkspace.SLOT_COUNT,
            R.string.passenger_workspace_dock_slot to PassengerWorkspace.DOCK_COUNT,
        )
        for ((title, slots) in groups) {
            val scenes = (0 until slots).map(settingGuides.getValue(title).buildStory)
            assertEquals(slots, scenes.map { it.after }.toSet().size)
        }
    }
}
