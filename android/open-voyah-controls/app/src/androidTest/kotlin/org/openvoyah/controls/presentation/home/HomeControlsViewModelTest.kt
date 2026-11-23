package org.openvoyah.controls.presentation.home

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.AndroidTestCase
import java.util.UUID
import java.util.function.Consumer
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.Action
import org.openvoyah.controls.core.Binding
import org.openvoyah.controls.core.PanelControls
import org.openvoyah.controls.data.settings.SettingsStore

class HomeControlsViewModelTest : AndroidTestCase() {
    private lateinit var preferences: SharedPreferences
    private lateinit var store: SettingsStore
    private class Panel : PanelControls {
        var publishes = 0
        var runs = 0
        var completion: Consumer<PanelControls.Result>? = null
        override fun health() = "Connected"
        override fun configured() = true
        override fun publish(error: Consumer<String>) { publishes += 1 }
        override fun run(operation: PanelControls.Operation, done: Consumer<PanelControls.Result>) {
            runs += 1
            completion = done
        }
    }

    override fun setUp() {
        super.setUp()
        preferences = context.getSharedPreferences("home-model-test-" + UUID.randomUUID(), Context.MODE_PRIVATE)
        store = SettingsStore(settingsContext())
    }
    override fun tearDown() { preferences.edit().clear().commit(); super.tearDown() }

    private fun settingsContext(failWrites: Boolean = false): Context = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            if (!failWrites) return preferences
            return object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val delegate = preferences.edit()
                    return object : SharedPreferences.Editor by delegate {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            delegate.putString(key, value)
                            return this
                        }
                        override fun commit() = false
                    }
                }
            }
        }
    }
    private fun model(panel: Panel = Panel(), failWrites: Boolean = false) =
        HomeControlsViewModel(if (failWrites) SettingsStore(settingsContext(true)) else store, panel, "f0506h", 30) { true }
    private fun action() = Action(Action.Kind.SYSTEM_SETTINGS, "", "System settings")
    private fun binding(id: String = "binding", code: Int = 85) = Binding(id, "Media button", "fixture-device", code, action(), Action.NONE, true)

    fun testPublishedRowsRemainImmutableAcrossReloads() {
        val settings = SettingsStore.State()
        settings.panelActions["navbar01"] = Action(Action.Kind.SYSTEM_SETTINGS, "", "First target")
        store.save(settings)
        val model = model()
        val before = model.state.value
        settings.panelActions["navbar01"] = Action(Action.Kind.SYSTEM_SETTINGS, "", "Second target")
        settings.holdMs = 725
        store.save(settings)
        assertEquals("First target", before.panels.first { it.slot == "navbar01" }.action)
        assertEquals(650, before.holdMs)
        model.refresh()
        assertEquals("Second target", model.state.value.panels.first { it.slot == "navbar01" }.action)
        assertEquals(725, model.state.value.holdMs)
        assertEquals("First target", before.panels.first { it.slot == "navbar01" }.action)
    }

    fun testFailedLoadClearsPreviouslyActionableRows() {
        val model = model()
        assertTrue(model.state.value.available)
        preferences.edit().putString("state", "malformed settings").commit()
        model.refresh()
        assertFalse(model.state.value.available)
        assertTrue(model.state.value.panels.isEmpty())
        assertTrue(model.state.value.bindings.isEmpty())
        assertNotNull(model.state.value.error)
        model.choosePanel("navbar01", action())
        assertEquals("malformed settings", preferences.getString("state", null))
    }

    fun testFailedSaveKeepsPublishedAndStoredSettings() {
        val panel = Panel()
        val model = model(panel, failWrites = true)
        assertFalse(model.buttonsEnabled(false))
        assertFalse(model.timings(725, 150))
        assertTrue(model.state.value.buttonsEnabled)
        assertEquals(650, model.state.value.holdMs)
        assertEquals(100, model.state.value.debounceMs)
        assertTrue(store.load().enabled)
        assertEquals(650, store.load().holdMs)
        assertEquals(0, panel.publishes)
        assertEquals(2, model.state.value.notices.size)
    }

    fun testFailedBindingSaveLeavesNoPartialBinding() {
        val panel = Panel()
        val model = model(panel, failWrites = true)
        assertNotNull(model.saveBinding(binding(), null))
        assertTrue(model.state.value.bindings.isEmpty())
        assertTrue(store.load().bindings.isEmpty())
        assertEquals(0, panel.publishes)
    }

    fun testExplicitNativeMainAppsChoiceSurvivesReload() {
        val model = model()
        assertEquals(UiText.get(R.string.controls_apps), model.state.value.panels.first { it.slot == "mainApps" }.action)
        model.choosePanel("mainApps", Action.NONE)
        assertTrue(store.load().panelActions.containsKey("mainApps"))
        assertEquals(Action.Kind.NONE, store.load().panelActions["mainApps"]!!.kind)
        model.refresh()
        assertEquals(UiText.get(R.string.ui_71), model.state.value.panels.first { it.slot == "mainApps" }.action)
    }

    fun testBridgeDoesNotReplayOrDeliverResultsToAClosedRoute() {
        val panel = Panel()
        val model = model(panel)
        val owner = model.attach()
        model.run(PanelControls.Operation.STATUS)
        model.run(PanelControls.Operation.STATUS)
        assertEquals(1, panel.runs)
        assertTrue(model.state.value.busy)
        model.detach(owner)
        model.attach()
        panel.completion!!.accept(PanelControls.Result(true, "Old result"))
        assertFalse(model.state.value.busy)
        assertTrue(model.state.value.notices.isEmpty())
        model.run(PanelControls.Operation.STATUS)
        panel.completion!!.accept(PanelControls.Result(false, "Current failure"))
        val notice = model.state.value.notices.single()
        assertEquals("Current failure", notice.message)
        model.acknowledgeNotice(notice.id)
        assertTrue(model.state.value.notices.isEmpty())
        assertEquals(2, panel.runs)
    }

    fun testBindingConflictsAndHoldOnlyAssignmentsKeepOriginals() {
        val settings = SettingsStore.State()
        settings.bindings.add(binding())
        store.save(settings)
        val panel = Panel()
        val model = model(panel)
        assertNotNull(model.saveBinding(binding("duplicate"), null))
        val holdOnly = Binding("hold-only", "Hold", "fixture-device", 86, Action.NONE, action(), true)
        assertEquals(UiText.get(R.string.button_tap_required), model.saveBinding(holdOnly, null))
        assertEquals(listOf("binding"), model.state.value.bindings.map { it.id })
        assertEquals(listOf("binding"), store.load().bindings.map { it.id })
        assertEquals(0, panel.publishes)
    }
}
