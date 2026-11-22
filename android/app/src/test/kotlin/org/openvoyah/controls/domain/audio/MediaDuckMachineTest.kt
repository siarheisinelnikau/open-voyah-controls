package org.openvoyah.controls.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDuckMachineTest {
    private class MemoryStore : DuckStore {
        var record: DuckRecord? = null
        override fun load() = record
        override fun save(record: DuckRecord) { this.record = record }
        override fun clear() { record = null }
    }

    /** The media stream as the OEM policy keeps it, with the controller's fade folded into one call. */
    private class Car(var volume: Int, val store: MemoryStore = MemoryStore()) {
        var machine = MediaDuckMachine(store)

        fun fade(target: Int?) {
            target ?: return
            for (level in rampLevels(volume, target, 4)) {
                assertTrue(machine.owns(volume))
                volume = level
                machine.applied(level)
            }
            machine.reached(volume)
        }

        fun start(trigger: DuckTrigger, settings: DuckSettings = DuckSettings.DEFAULT) =
            fade(machine.start(trigger, volume, 0, settings))

        fun stop(trigger: DuckTrigger) = fade(machine.stop(trigger, volume))

        /** A process kill: the store survives, the machine does not. */
        fun restart(callActive: Boolean, settings: DuckSettings = DuckSettings.DEFAULT) {
            machine = MediaDuckMachine(store)
            fade(machine.recover(volume, callActive, settings))
        }
    }

    @Test
    fun `listening lowers media strongly and restores the exact level afterwards`() {
        val car = Car(volume = 18)
        car.start(DuckTrigger.LISTENING)
        assertEquals(3, car.volume)
        assertEquals(DuckRecord(18, 3), car.store.record)

        car.stop(DuckTrigger.LISTENING)
        assertEquals(18, car.volume)
        assertNull(car.store.record)
        assertFalse(car.machine.ducked)
    }

    @Test
    fun `the depth follows the setting and silence goes to the minimum`() {
        assertEquals(6, duckedLevel(current = 30, minimum = 0, levelPercent = 20))
        assertEquals(1, duckedLevel(current = 2, minimum = 0, levelPercent = 20))
        assertEquals(0, duckedLevel(current = 12, minimum = 0, levelPercent = 0))
        assertEquals(10, duckedLevel(current = 30, minimum = 0, levelPercent = 35))
        assertEquals(0, duckedLevel(current = 0, minimum = 0, levelPercent = 20))
    }

    @Test
    fun `a level that is already low is left alone`() {
        val car = Car(volume = 1)
        car.start(DuckTrigger.LISTENING)
        assertEquals(1, car.volume)
        assertFalse(car.machine.ducked)
        car.stop(DuckTrigger.LISTENING)
        assertEquals(1, car.volume)
    }

    @Test
    fun `a switched off trigger does nothing`() {
        val car = Car(volume = 20)
        car.start(DuckTrigger.CALL, DuckSettings(listening = true, calls = false, levelPercent = 20))
        assertEquals(20, car.volume)
        car.start(DuckTrigger.LISTENING, DuckSettings(listening = false, calls = true, levelPercent = 20))
        assertEquals(20, car.volume)
        assertTrue(car.machine.triggers.isEmpty())
    }

    @Test
    fun `a call that starts while listening keeps media low until both have ended`() {
        val car = Car(volume = 20)
        car.start(DuckTrigger.LISTENING)
        car.start(DuckTrigger.CALL)
        assertEquals(4, car.volume)

        car.stop(DuckTrigger.LISTENING)
        assertEquals(4, car.volume)
        car.stop(DuckTrigger.CALL)
        assertEquals(20, car.volume)
    }

    @Test
    fun `a new trigger during the fade back lowers again from the saved level`() {
        val car = Car(volume = 20)
        car.start(DuckTrigger.LISTENING)
        val back = car.machine.stop(DuckTrigger.LISTENING, car.volume)
        assertEquals(20, back)
        // One step of the fade back, then the phone rings.
        car.volume = 8
        car.machine.applied(8)
        car.start(DuckTrigger.CALL)
        assertEquals(4, car.volume)
        assertEquals(DuckRecord(20, 4), car.store.record)

        car.stop(DuckTrigger.CALL)
        assertEquals(20, car.volume)
    }

    @Test
    fun `an absolute volume command wins and is not restored over`() {
        val car = Car(volume = 20)
        car.start(DuckTrigger.LISTENING)
        assertNull(car.machine.command(relative = false, current = car.volume))
        car.volume = 9 // "громкость 30 процентов"

        car.stop(DuckTrigger.LISTENING)
        assertEquals(9, car.volume)
        assertNull(car.store.record)
    }

    @Test
    fun `a relative volume command steps from the owner's level`() {
        val car = Car(volume = 20)
        car.start(DuckTrigger.LISTENING)
        assertEquals(20, car.machine.command(relative = true, current = car.volume))
        assertFalse(car.machine.ducked)
        assertNull(car.store.record)
    }

    @Test
    fun `a volume command after the restore has started also takes the owner's level`() {
        val car = Car(volume = 20)
        car.start(DuckTrigger.LISTENING)
        car.machine.stop(DuckTrigger.LISTENING, car.volume)
        car.volume = 9
        car.machine.applied(9)

        assertEquals(20, car.machine.command(relative = true, current = car.volume))
        assertFalse(car.machine.ducked)
    }

    @Test
    fun `a command during a call ends the duck for the rest of the call`() {
        val car = Car(volume = 20)
        car.start(DuckTrigger.CALL)
        assertNull(car.machine.command(relative = false, current = car.volume))
        car.volume = 12

        car.start(DuckTrigger.LISTENING)
        assertEquals(12, car.volume)
        car.stop(DuckTrigger.LISTENING)
        car.stop(DuckTrigger.CALL)
        assertEquals(12, car.volume)

        // The next episode ducks again.
        car.start(DuckTrigger.LISTENING)
        assertEquals(2, car.volume)
    }

    @Test
    fun `a volume set by hand while lowered stays`() {
        val car = Car(volume = 20)
        car.start(DuckTrigger.LISTENING)
        car.volume = 7 // the steering wheel knob

        car.stop(DuckTrigger.LISTENING)
        assertEquals(7, car.volume)
        assertNull(car.store.record)
    }

    @Test
    fun `a hand change during a fade stops the fade`() {
        val car = Car(volume = 20)
        val target = car.machine.start(DuckTrigger.LISTENING, car.volume, 0, DuckSettings.DEFAULT)
        assertEquals(4, target)
        car.volume = 15
        car.machine.applied(15)
        car.volume = 25 // the owner turns it up mid-fade

        assertFalse(car.machine.owns(car.volume))
        assertFalse(car.machine.ducked)
        car.stop(DuckTrigger.LISTENING)
        assertEquals(25, car.volume)
    }

    @Test
    fun `a killed process restores the saved level on the next start`() {
        val car = Car(volume = 20)
        car.start(DuckTrigger.LISTENING)
        car.restart(callActive = false)

        assertEquals(20, car.volume)
        assertNull(car.store.record)
    }

    @Test
    fun `a process killed mid-fade still restores`() {
        val store = MemoryStore()
        store.record = DuckRecord(saved = 20, ducked = 4)
        val car = Car(volume = 12, store = store)
        car.restart(callActive = false)
        assertEquals(20, car.volume)
    }

    @Test
    fun `a restart during a call keeps media low until the call ends`() {
        val car = Car(volume = 20)
        car.start(DuckTrigger.CALL)
        car.restart(callActive = true)
        assertEquals(4, car.volume)
        assertEquals(setOf(DuckTrigger.CALL), car.machine.triggers)

        car.stop(DuckTrigger.CALL)
        assertEquals(20, car.volume)
    }

    @Test
    fun `a level changed after the kill is the owner's and stays`() {
        val store = MemoryStore()
        store.record = DuckRecord(saved = 20, ducked = 4)
        val car = Car(volume = 26, store = store)
        car.restart(callActive = false)
        assertEquals(26, car.volume)
        assertNull(store.record)
    }

    @Test
    fun `phone call states come from the OEM Bluetooth phone`() {
        assertTrue(phoneCallActive(128)) // active
        assertTrue(phoneCallActive(129)) // dialing
        assertTrue(phoneCallActive(130)) // incoming
        assertFalse(phoneCallActive(PHONE_CALL_TERMINATED))
        assertFalse(phoneCallActive(0))
    }

    @Test
    fun `fades are short and end on the target`() {
        assertEquals(listOf(16, 11, 7, 2), rampLevels(20, 2, 4))
        assertEquals(listOf(3), rampLevels(2, 3, 8))
        assertEquals(emptyList<Int>(), rampLevels(5, 5, 4))
    }

    @Test
    fun `the settings offer only supported depths`() {
        assertTrue(DuckSettings.validLevel(DuckSettings.DEFAULT_LEVEL))
        assertFalse(DuckSettings.validLevel(50))
    }

    @Test
    fun `the startup level replaces a level a killed process saved`() {
        val store = MemoryStore()
        store.record = DuckRecord(saved = 24, ducked = 4)
        val machine = MediaDuckMachine(store)
        machine.discard()
        assertNull(store.record)
        // Nothing is restored afterwards, and the next duck starts from the startup level.
        assertNull(machine.stop(DuckTrigger.LISTENING, 3))
        assertEquals(1, machine.start(DuckTrigger.LISTENING, 3, 0, DuckSettings.DEFAULT))
        assertEquals(DuckRecord(3, 1), store.record)
    }

    @Test
    fun `the startup level is a share of the stream range`() {
        assertEquals(3, startupLevel(minimum = 0, maximum = 30, percent = 10))
        assertEquals(0, startupLevel(minimum = 0, maximum = 30, percent = 0))
        assertEquals(30, startupLevel(minimum = 0, maximum = 30, percent = 100))
        assertEquals(6, startupLevel(minimum = 2, maximum = 42, percent = 10))
    }

    @Test
    fun `the startup level is set once per boot and only shortly after it`() {
        val on = StartupVolume.DEFAULT
        assertTrue(startupVolumeDue(on, bootCount = 12, appliedBoot = 11, uptimeMs = 25_000))
        assertTrue(startupVolumeDue(on, bootCount = 12, appliedBoot = null, uptimeMs = 25_000))
        // A restart of Controls in the same boot.
        assertFalse(startupVolumeDue(on, bootCount = 12, appliedBoot = 12, uptimeMs = 25_000))
        // Controls started long after boot: the owner has had time to choose a level.
        assertFalse(startupVolumeDue(on, bootCount = 12, appliedBoot = 11, uptimeMs = STARTUP_VOLUME_WINDOW_MS + 1))
        assertFalse(startupVolumeDue(StartupVolume(enabled = false, percent = 10), 12, 11, 25_000))
        assertFalse(startupVolumeDue(on, bootCount = null, appliedBoot = 11, uptimeMs = 25_000))
    }

    @Test
    fun `startup volume is on at ten percent by default`() {
        assertEquals(StartupVolume(enabled = true, percent = 10), StartupVolume.DEFAULT)
        assertFalse(StartupVolume.validPercent(101))
    }
}
