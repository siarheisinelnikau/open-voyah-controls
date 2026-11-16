package org.openvoyah.controls.presentation.home

import android.test.AndroidTestCase
import org.openvoyah.controls.core.Action
import org.openvoyah.controls.data.settings.SettingsStore

class HomeControlsViewModelTest : AndroidTestCase() {
    fun testPublishedRowsDoNotChangeWhenLegacySettingsMutate() {
        val settings = SettingsStore.State()
        settings.panelActions["navbar01"] = Action(Action.Kind.SYSTEM_SETTINGS, "", "First target")
        val model = HomeControlsViewModel()
        model.refresh(settings, null, "Connected", true)
        val before = model.state.value
        settings.panelActions["navbar01"] = Action(Action.Kind.SYSTEM_SETTINGS, "", "Second target")
        settings.holdMs = 725
        assertEquals("First target", before.panels.first { it.slot == "navbar01" }.action)
        assertEquals(650, before.holdMs)
        model.refresh(settings, null, "Connected", true)
        assertEquals("Second target", model.state.value.panels.first { it.slot == "navbar01" }.action)
        assertEquals(725, model.state.value.holdMs)
    }

    fun testFailedLoadClearsPreviouslyActionableRows() {
        val model = HomeControlsViewModel()
        model.refresh(SettingsStore.State(), null, "Connected", true)
        assertTrue(model.state.value.available)
        model.refresh(null, "Unreadable settings", "", false)
        assertFalse(model.state.value.available)
        assertTrue(model.state.value.panels.isEmpty())
        assertEquals("Unreadable settings", model.state.value.error)
    }
}
