package org.openvoyah.controls

import android.content.Context
import android.test.AndroidTestCase
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.data.settings.SeatMemoryPreferences
import org.openvoyah.controls.domain.seatmemory.SeatMemorySettings
import org.openvoyah.controls.domain.seatmemory.SeatMemorySlot

class SeatMemoryPreferencesTest : AndroidTestCase() {
    fun testDefaultsClearRenameAndRoundTripAcrossInstances() {
        val shared = context.getSharedPreferences(SeatMemoryPreferences.STORE, Context.MODE_PRIVATE)
        val original = shared.getString("settings", null)
        try {
            assertTrue(shared.edit().remove("settings").commit())
            val first = SeatMemoryPreferences(context)
            assertEquals(SeatMemorySettings(), first.load())
            first.select(SeatMemorySlot.TWO)
            first.rename(SeatMemorySlot.TWO, "  Weekend  ")
            first.rename(SeatMemorySlot.THREE, "Семья")
            first.setOpenFactorySeatsOnTap(true)

            val loaded = SeatMemoryPreferences(context)
            assertEquals(SeatMemorySlot.TWO, loaded.load().selectedSlot)
            assertEquals(mapOf(SeatMemorySlot.TWO to "Weekend", SeatMemorySlot.THREE to "Семья"), loaded.load().slotNames)
            assertTrue(loaded.load().openFactorySeatsOnTap)
            val exported = loaded.exportSettings()
            assertTrue(exported.get("openFactorySeatsOnTap") == true)
            loaded.rename(SeatMemorySlot.TWO, "")
            loaded.setOpenFactorySeatsOnTap(false)
            assertFalse(first.load().slotNames.containsKey(SeatMemorySlot.TWO))
            assertFalse(first.load().openFactorySeatsOnTap)
            loaded.restoreSettings(SeatMemoryPreferences.validateSettings(exported))
            assertEquals(exported.toString(), first.exportSettings().toString())
        } finally {
            assertTrue(shared.edit().putString("settings", original).commit())
        }
    }

    fun testLegacySettingsDefaultToGuidesAndAValidatedLegacyImportKeepsTheReceivingPreference() {
        val shared = context.getSharedPreferences(SeatMemoryPreferences.STORE, Context.MODE_PRIVATE)
        val original = shared.getString("settings", null)
        try {
            val legacy = JSONObject().put("selectedSlot", 2).put("slotNames", JSONArray(listOf("", "Weekend", "")))
            assertTrue(shared.edit().putString("settings", legacy.toString()).commit())
            val preferences = SeatMemoryPreferences(context)
            assertFalse(preferences.load().openFactorySeatsOnTap)
            preferences.setOpenFactorySeatsOnTap(true)
            preferences.select(SeatMemorySlot.THREE)

            val validated = SeatMemoryPreferences.validateSettings(legacy)
            assertFalse(validated.has("openFactorySeatsOnTap"))
            preferences.restoreSettings(validated)
            assertEquals(SeatMemorySlot.TWO, preferences.load().selectedSlot)
            assertEquals("Weekend", preferences.load().slotNames[SeatMemorySlot.TWO])
            assertTrue(preferences.load().openFactorySeatsOnTap)

            val explicitOff = SeatMemoryPreferences.validateSettings(legacy.put("openFactorySeatsOnTap", false))
            preferences.restoreSettings(explicitOff)
            assertFalse(preferences.load().openFactorySeatsOnTap)

            assertTrue(shared.edit().putString("settings", "{incomplete").commit())
            preferences.restoreSettings(validated)
            assertEquals(SeatMemorySlot.TWO, preferences.load().selectedSlot)
            assertFalse(preferences.load().openFactorySeatsOnTap)
        } finally {
            assertTrue(shared.edit().putString("settings", original).commit())
        }
    }

    fun testArchiveValidationIsStrictAndDoesNotAcceptCoordinates() {
        fun data(slot: Any = 1, names: List<Any> = listOf("", "", "")) =
            JSONObject().put("selectedSlot", slot).put("slotNames", JSONArray(names))

        val verified = SeatMemoryPreferences.validateSettings(data(names = listOf("  Weekend  ", "", "")))
        assertEquals("Weekend", verified.getJSONArray("slotNames").getString(0))
        val invalid = listOf(
            data(4), data("1"), data(1.0), data(names = listOf("one", "two")),
            data(names = listOf("line\nwrapped", "", "")), data(names = listOf(23, "", "")),
            data(names = listOf("x".repeat(33), "", "")), data().put("positions", JSONArray()),
            data().put("openFactorySeatsOnTap", "true"), data().put("openFactorySeatsOnTap", 1),
            data().put("openFactorySeatsOnTap", JSONObject.NULL),
        )
        for (value in invalid) {
            assertTrue("Invalid settings accepted: $value", runCatching { SeatMemoryPreferences.validateSettings(value) }.isFailure)
        }
    }
}
