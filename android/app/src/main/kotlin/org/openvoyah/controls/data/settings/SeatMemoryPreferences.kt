package org.openvoyah.controls.data.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.seatmemory.SeatMemorySettings
import org.openvoyah.controls.domain.seatmemory.SeatMemorySettingsStore
import org.openvoyah.controls.domain.seatmemory.SeatMemorySlot

internal class SeatMemoryPreferences(context: Context) : SeatMemorySettingsStore {
    private val preferences = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    override fun load(): SeatMemorySettings {
        val raw = preferences.getString(SETTINGS, null) ?: return SeatMemorySettings()
        return decode(JSONObject(raw))
    }

    override fun select(slot: SeatMemorySlot) = update { it.copy(selectedSlot = slot) }

    override fun rename(slot: SeatMemorySlot, name: String) = update { it.rename(slot, name) }

    override fun setOpenFactorySeatsOnTap(enabled: Boolean) = update { it.copy(openFactorySeatsOnTap = enabled) }

    fun exportSettings(): JSONObject = encode(load())

    fun restoreSettings(value: JSONObject) = synchronized(lock) {
        val incoming = decode(value)
        val restored = if (value.has(OPEN_FACTORY_SEATS_ON_TAP)) incoming
            else incoming.copy(openFactorySeatsOnTap = storedShortcutPreference())
        write(restored)
    }

    private fun storedShortcutPreference(): Boolean {
        val raw = preferences.getString(SETTINGS, null) ?: return false
        return try {
            JSONObject(raw).opt(OPEN_FACTORY_SEATS_ON_TAP) == true
        } catch (failure: JSONException) {
            ControlsLog.warn("store_decode", failure, "store", STORE, "outcome", "shortcut_default_used")
            false
        }
    }

    private fun update(transform: (SeatMemorySettings) -> SeatMemorySettings): SeatMemorySettings = synchronized(lock) {
        val old = load()
        val changed = transform(old)
        if (old != changed) write(changed)
        changed
    }

    private fun write(settings: SeatMemorySettings) {
        val previous = preferences.getString(SETTINGS, null)
        if (!preferences.edit().putString(SETTINGS, encode(settings).toString()).commit()) {
            // SharedPreferences changes its in-memory map even when a disk commit fails.
            preferences.edit().putString(SETTINGS, previous).commit()
            error("Could not save seat memory settings")
        }
    }

    companion object {
        const val STORE = "seat_memory"
        private const val SETTINGS = "settings"
        private const val OPEN_FACTORY_SEATS_ON_TAP = "openFactorySeatsOnTap"
        private val lock = Any()

        @JvmStatic
        fun validateSettings(value: JSONObject): JSONObject = encode(decode(value)).apply {
            // An omitted field leaves the receiving device's choice in place.
            if (!value.has(OPEN_FACTORY_SEATS_ON_TAP)) remove(OPEN_FACTORY_SEATS_ON_TAP)
        }

        private fun decode(value: JSONObject): SeatMemorySettings {
            val hasShortcutPreference = value.has(OPEN_FACTORY_SEATS_ON_TAP)
            val fieldCount = if (hasShortcutPreference) 3 else 2
            require(value.length() == fieldCount)
            val number = requireNotNull(value.get("selectedSlot") as? Int)
            val selected = requireNotNull(SeatMemorySlot.forNumber(number))
            val names = value.getJSONArray("slotNames")
            require(names.length() == SeatMemorySlot.entries.size)
            val openFactorySeatsOnTap = if (hasShortcutPreference) {
                requireNotNull(value.get(OPEN_FACTORY_SEATS_ON_TAP) as? Boolean)
            } else false
            val settings = buildMap {
                SeatMemorySlot.entries.forEachIndexed { index, slot ->
                    val name = names.get(index) as? String ?: error("Invalid seat memory name")
                    require(SeatMemorySettings.validName(name))
                    val clean = name.trim()
                    if (clean.isNotEmpty()) put(slot, clean)
                }
            }
            return SeatMemorySettings(selected, settings, openFactorySeatsOnTap)
        }

        private fun encode(value: SeatMemorySettings): JSONObject = JSONObject()
            .put("selectedSlot", value.selectedSlot.number)
            .put("slotNames", JSONArray(SeatMemorySlot.entries.map { value.slotNames[it].orEmpty() }))
            .put(OPEN_FACTORY_SEATS_ON_TAP, value.openFactorySeatsOnTap)
    }
}
