package org.openvoyah.controls.domain.display

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppWindowConfigurationTest {
    private val original = AppWindowProfile("org.example.window/org.example.window.Main")
    private val custom = original.copy(widthPercent = 75, panelWidthPx = 120)
    private val events = mutableListOf<String>()
    private var stored = original
    private var defaults = AppDisplayDefaults()
    private var writable = true
    private var connection = WindowConnection.ENABLED
    private var delivery: WindowProfileDelivery = WindowProfileDelivery.Outcome.UNCONFIRMED

    private val storage = object : WindowProfileStorage {
        override fun load(component: String) = stored
        override fun save(profile: AppWindowProfile) {
            check(writable)
            events += "save"
            stored = profile
        }
        override fun reset(component: String) {
            events += "reset"
            stored = original
        }
        override fun loadDefaults() = defaults
        override fun saveDefaults(defaults: AppDisplayDefaults) {
            events += "save defaults"
            this@AppWindowConfigurationTest.defaults = defaults
        }
    }
    private val service = object : WindowProfileService {
        override val supported = true
        override fun connection() = connection
        override fun enable() = Unit
        override fun apply(component: String, change: WindowProfileChange): WindowProfileDelivery {
            assertEquals(component, stored.component)
            events += change.name
            return delivery
        }
        override fun publishDefaults(passengerRequested: Boolean): DisplayDefaultsDelivery {
            assertEquals(defaults.secondaryDpi != null, passengerRequested)
            events += "publish defaults"
            return DisplayDefaultsDelivery.CENTRAL_ONLY
        }
    }
    private val configuration = AppWindowConfiguration(storage, service, io = Dispatchers.Unconfined)

    @Test fun offlineSaveKeepsTheProfileForLater() = runBlocking {
        connection = WindowConnection.DISCONNECTED
        val result = configuration.changeProfile(custom, WindowProfileChange.APPLY)
        assertEquals(listOf("save"), events)
        assertEquals(custom, configuration.readProfile(custom.component).profile)
        assertEquals(WindowProfileDelivery.Outcome.SAVED, result.delivery)
    }

    @Test fun unconfirmedApplyLeavesTheProfileSavedWithoutASecondRequest() = runBlocking {
        val result = configuration.changeProfile(custom, WindowProfileChange.APPLY)
        assertEquals(listOf("save", "APPLY"), events)
        assertEquals(custom, result.selection.profile)
        assertEquals(custom, stored)
        assertEquals(WindowProfileDelivery.Outcome.UNCONFIRMED, result.delivery)
    }

    @Test fun failedStoragePreventsDispatch() {
        writable = false
        assertThrows(IllegalStateException::class.java) {
            runBlocking { configuration.changeProfile(custom, WindowProfileChange.APPLY) }
        }
        assertEquals(emptyList<String>(), events)
        assertEquals(original, stored)
    }

    @Test fun resetRemovesTheOverrideBeforeTheLiveRequest() = runBlocking {
        stored = custom
        delivery = WindowProfileDelivery.Outcome.NEXT_LAUNCH
        val result = configuration.changeProfile(custom, WindowProfileChange.RESET)
        assertEquals(listOf("reset", "RESET"), events)
        assertEquals(original, result.selection.profile)
        assertEquals(original, stored)
        assertEquals(WindowProfileDelivery.Outcome.NEXT_LAUNCH, result.delivery)
    }

    @Test fun displayDefaultsAreSavedBeforePublication() = runBlocking {
        val customDefaults = AppDisplayDefaults(190, 220)
        assertEquals(DisplayDefaultsDelivery.CENTRAL_ONLY, configuration.changeDisplayDefaults(customDefaults))
        assertEquals(listOf("save defaults", "publish defaults"), events)
        assertEquals(customDefaults, configuration.readDisplayDefaults())
    }
}
