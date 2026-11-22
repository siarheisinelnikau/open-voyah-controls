package org.openvoyah.controls.presentation.keyboard

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.keyboard.KeyboardAvailability
import org.openvoyah.controls.domain.keyboard.KeyboardFallback
import org.openvoyah.controls.domain.keyboard.KeyboardOption
import org.openvoyah.controls.domain.keyboard.KeyboardOutcome
import org.openvoyah.controls.domain.keyboard.KeyboardPort
import org.openvoyah.controls.domain.keyboard.KeyboardResult
import org.openvoyah.controls.domain.keyboard.KeyboardStatus
import org.openvoyah.controls.platform.keyboard.InstalledKeyboard

class KeyboardViewModelTest {
    private val dispatcher = Dispatchers.Unconfined
    private val yandex = "ru.yandex.androidkeyboard/com.android.inputmethod.latin.LatinIME"
    private val aosp = "com.android.inputmethod.latin/.LatinIME"

    private class Port(var availability: KeyboardAvailability, var status: KeyboardStatus) : KeyboardPort {
        var selectOutcome = KeyboardOutcome.APPLIED
        var fails = false
        val calls = mutableListOf<String>()
        override fun availability() = availability
        override fun read(): KeyboardResult {
            calls += "read"
            if (fails) throw IOException("root channel")
            return KeyboardResult(KeyboardOutcome.READ, status)
        }
        override fun select(id: String): KeyboardResult {
            calls += "select $id"
            if (fails) throw IOException("root channel")
            if (selectOutcome == KeyboardOutcome.APPLIED) status = status.copy(choice = id, active = id)
            return KeyboardResult(selectOutcome, status)
        }
        override fun restoreFactory(): KeyboardResult {
            calls += "factory"
            status = status.copy(choice = null, active = "com.android.inputmethod.latin/.LatinIME", fallback = null, fallbackId = null)
            return KeyboardResult(KeyboardOutcome.FACTORY, status)
        }
    }

    private val catalog = object : KeyboardCatalog {
        override fun keyboards() = listOf(InstalledKeyboard(KeyboardOption(yandex, "Yandex Keyboard", "ru.yandex.androidkeyboard"), null))
        override fun labels() = mapOf(yandex to "Yandex Keyboard", aosp to "Android Keyboard (AOSP)")
    }

    private fun model(port: Port) = KeyboardViewModel(port, catalog, dispatcher, CoroutineScope(dispatcher))

    @Test fun aChoiceIsShownOnlyAfterAndroidConfirmedIt() {
        val port = Port(KeyboardAvailability.READY, KeyboardStatus(active = aosp))
        val model = model(port)
        assertTrue(model.state.value.canChoose)
        model.select(yandex)
        val state = model.state.value
        assertEquals(yandex, state.status?.choice)
        assertEquals(yandex, state.status?.active)
        assertEquals(KeyboardNotice(KeyboardNotice.Kind.APPLIED, yandex), state.notice)
        assertEquals("Yandex Keyboard", state.label(state.status?.active))
    }

    @Test fun anUnconfirmedChoiceLeavesTheFactoryKeyboard() {
        val port = Port(KeyboardAvailability.READY, KeyboardStatus(active = aosp)).apply { selectOutcome = KeyboardOutcome.NOT_CONFIRMED }
        val model = model(port)
        model.select(yandex)
        assertNull(model.state.value.status?.choice)
        assertEquals(KeyboardNotice.Kind.NOT_CONFIRMED, model.state.value.notice?.kind)
    }

    @Test fun withoutTheAgentNothingCanBeChosenButTheFactoryKeyboardCanReturn() {
        val port = Port(KeyboardAvailability.WINDOW_SERVICE_OFF, KeyboardStatus(choice = yandex, active = aosp))
        val model = model(port)
        assertFalse(model.state.value.canChoose)
        model.select(yandex)
        assertFalse(port.calls.any { it.startsWith("select") })
        model.restoreFactory()
        assertEquals(listOf("read", "factory"), port.calls)
        assertNull(model.state.value.status?.choice)
        assertEquals(KeyboardNotice.Kind.FACTORY, model.state.value.notice?.kind)
    }

    @Test fun withoutSetupNothingIsRead() {
        val port = Port(KeyboardAvailability.SETUP_REQUIRED, KeyboardStatus())
        val model = model(port)
        assertTrue(port.calls.isEmpty())
        assertNull(model.state.value.status)
        assertFalse(model.state.value.canChoose)
        model.restoreFactory()
        assertTrue(port.calls.isEmpty())
    }

    @Test fun aFallbackIsShownAndClearedByTheFactoryChoice() {
        val port = Port(KeyboardAvailability.READY, KeyboardStatus(active = aosp, fallback = KeyboardFallback.CRASHED, fallbackId = yandex))
        val model = model(port)
        assertEquals(KeyboardFallback.CRASHED, model.state.value.status?.fallback)
        model.restoreFactory()
        assertNull(model.state.value.status?.fallback)
    }

    @Test fun failuresAreReportedWithoutChangingTheShownState() {
        val port = Port(KeyboardAvailability.READY, KeyboardStatus(active = aosp))
        val model = model(port)
        port.fails = true
        model.select(yandex)
        assertEquals(KeyboardNotice.Kind.FAILED, model.state.value.notice?.kind)
        assertEquals(aosp, model.state.value.status?.active)
        assertFalse(model.state.value.working)
        model.refresh()
        assertEquals(KeyboardNotice.Kind.READ_FAILED, model.state.value.notice?.kind)
    }
}
