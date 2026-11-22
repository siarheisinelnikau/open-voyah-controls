package org.openvoyah.controls.data.settings

import android.content.Context

/**
 * The owner can switch off an approved vehicle command on this head unit. Switching off only narrows the approval, so
 * the app enforces it alone. It stays on the device: approvals belong to one firmware and are not part of an export.
 */
internal class VehicleCommandSwitches(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vehicle_commands", Context.MODE_PRIVATE)

    fun switchedOff(): Set<String> = prefs.getStringSet(KEY, null).orEmpty().filterTo(sortedSetOf()) { commandId.matches(it) }

    fun isSwitchedOff(command: String): Boolean = command in switchedOff()

    fun replace(switchedOff: Set<String>) {
        require(switchedOff.size <= MAX && switchedOff.all { commandId.matches(it) }) { "Invalid vehicle command list" }
        prefs.edit().putStringSet(KEY, HashSet(switchedOff)).apply()
    }

    private companion object {
        const val KEY = "switchedOff"
        const val MAX = 256
        val commandId = Regex("[A-Za-z][A-Za-z0-9]{0,63}")
    }
}
