package org.openvoyah.controls

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.test.AndroidTestCase
import org.openvoyah.controls.domain.seatmemory.SeatMemoryAction
import org.openvoyah.controls.domain.seatmemory.SeatMemorySlot
import org.openvoyah.controls.platform.ui.FactorySeatHandoffOutcome
import org.openvoyah.controls.platform.ui.FactorySeatShortcut
import org.openvoyah.controls.platform.ui.VehicleSettingsDestination
import org.openvoyah.controls.platform.ui.VehicleSettingsLauncher

class SeatMemoryFactoryShortcutTest : AndroidTestCase() {
    fun testOnlyPublicSeatsActionIsPassedToTheStockActivity() {
        val seats = activityInfo()
        val events = mutableListOf<String>()
        val launcher = VehicleSettingsLauncher(queryActivities = { query ->
            assertEquals(SEATS_ACTION, query.action)
            assertEquals(VEHICLE, query.`package`)
            assertEquals(setOf(Intent.CATEGORY_DEFAULT), query.categories)
            assertNull(query.component)
            listOf(seats)
        }, hasPermission = { error("The public Seats activity has no required permission") }, logHandoff = { events.add(it) })

        val shortcut = checkNotNull(launcher.seatMemoryShortcut())
        assertEquals(FactorySeatShortcut.SEATS, shortcut)
        assertTrue(events.isEmpty())
        val requests = mutableListOf<Intent>()
        val before = SystemClock.elapsedRealtime()
        val outcome = launcher.requestSeatMemory(shortcut, SeatMemorySlot.TWO, SeatMemoryAction.SAVE) { requests.add(it) }
        assertEquals(FactorySeatHandoffOutcome.REQUESTED, outcome)
        val result = requests.single()
        assertEquals(ComponentName(VEHICLE, seats.name), result.component)
        assertEquals(SEATS_ACTION, result.action)
        assertNull(result.extras)
        assertNull(result.data)
        assertHandoff(events.single(), "slot=2 action=save target=seats outcome=requested", before)
    }

    fun testStaleSeatsChoiceIsLoggedUnavailableWithoutFallingBackToAnotherRoute() {
        var seatsAvailable = true
        val queries = mutableListOf<String?>()
        val events = mutableListOf<String>()
        val launcher = VehicleSettingsLauncher(queryActivities = { query ->
            queries.add(query.action)
            if (query.action == Intent.ACTION_MAIN || (query.action == SEATS_ACTION && seatsAvailable)) {
                listOf(activityInfo())
            } else emptyList()
        }, hasPermission = { false }, logHandoff = { events.add(it) })

        val shortcut = checkNotNull(launcher.seatMemoryShortcut())
        assertEquals(FactorySeatShortcut.SEATS, shortcut)
        seatsAvailable = false
        val before = SystemClock.elapsedRealtime()
        val outcome = launcher.requestSeatMemory(shortcut, SeatMemorySlot.ONE, SeatMemoryAction.RESTORE) {
            fail("A stale Seats shortcut must not open the generic vehicle menu")
        }
        assertEquals(FactorySeatHandoffOutcome.UNAVAILABLE, outcome)
        assertEquals(listOf(SEATS_ACTION, SEATS_ACTION), queries)
        assertHandoff(events.single(), "slot=1 action=restore target=seats outcome=unavailable", before)
    }

    fun testUntrustedDisabledProtectedAndAmbiguousSeatsTargetsAreRejected() {
        val denied = listOf(
            activityInfo().apply { exported = false },
            activityInfo().apply { enabled = false },
            activityInfo().apply { applicationInfo.enabled = false },
            activityInfo().apply { applicationInfo.flags = 0 },
            activityInfo().apply { packageName = "org.example.unrelated" },
            activityInfo().apply { permission = "org.example.requires_signature" },
        )
        assertNull(VehicleSettingsLauncher({ denied }, { false }).seatsIntent())
        val ambiguous = listOf(activityInfo(), activityInfo().apply { name = "$VEHICLE.SecondSeatsActivity" })
        assertNull(VehicleSettingsLauncher({ ambiguous }, { true }).seatsIntent())
    }

    fun testFactoryLauncherRemainsAvailableWhenDedicatedSeatsRouteIsAbsent() {
        val factorySettings = "com.qinggan.app.vehiclesetting"
        val events = mutableListOf<String>()
        val launcher = VehicleSettingsLauncher(queryActivities = { query ->
            if (query.`package` == factorySettings) {
                assertEquals(Intent.ACTION_MAIN, query.action)
                assertEquals(setOf(Intent.CATEGORY_LAUNCHER), query.categories)
                listOf(activityInfo().apply {
                    packageName = factorySettings
                    name = "$factorySettings.CarSettingActivity"
                    applicationInfo.flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                })
            } else emptyList()
        }, hasPermission = { false }, logHandoff = { events.add(it) })

        assertNull(launcher.seatsIntent())
        val fallback = checkNotNull(launcher.intent(VehicleSettingsDestination.VEHICLE))
        assertEquals(ComponentName(factorySettings, "$factorySettings.CarSettingActivity"), fallback.component)
        assertNull(fallback.extras)
        val shortcut = checkNotNull(launcher.seatMemoryShortcut())
        assertEquals(FactorySeatShortcut.VEHICLE, shortcut)
        assertTrue(events.isEmpty())
        val before = SystemClock.elapsedRealtime()
        val outcome = launcher.requestSeatMemory(shortcut, SeatMemorySlot.THREE, SeatMemoryAction.RESTORE) {
            assertEquals(fallback.component, it.component)
            assertEquals(Intent.ACTION_MAIN, it.action)
            assertNull(it.extras)
            throw ActivityNotFoundException()
        }
        assertEquals(FactorySeatHandoffOutcome.FAILED, outcome)
        assertHandoff(events.single(), "slot=3 action=restore target=vehicle outcome=failed", before)
    }

    private fun assertHandoff(message: String, fields: String, before: Long) {
        val prefix = "event=driver_memory_handoff $fields elapsed_ms="
        assertTrue(message, message.startsWith(prefix))
        val elapsed = message.removePrefix(prefix)
        assertTrue(message, elapsed.matches(Regex("0|[1-9][0-9]*")))
        assertTrue(message, elapsed.toLong() in before..SystemClock.elapsedRealtime())
    }

    private fun activityInfo() = ActivityInfo().apply {
        packageName = VEHICLE
        name = "com.qinggan.app.vehicletype.ui.activity.AirActivity"
        enabled = true
        exported = true
        applicationInfo = ApplicationInfo().apply {
            enabled = true
            flags = ApplicationInfo.FLAG_SYSTEM
        }
    }

    private companion object {
        const val VEHICLE = "com.qinggan.app.vehicle"
        const val SEATS_ACTION = "$VEHICLE.SHOW_SEAT"
    }
}
