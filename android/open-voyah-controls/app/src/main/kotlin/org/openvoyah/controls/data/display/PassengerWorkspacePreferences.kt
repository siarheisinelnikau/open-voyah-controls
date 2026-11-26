package org.openvoyah.controls.data.display

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.domain.display.PassengerWorkspace

internal class PassengerWorkspacePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun load(): PassengerWorkspace = preferences.getString(KEY, null)?.let { decode(JSONObject(it)) } ?: PassengerWorkspace()

    fun update(change: (PassengerWorkspace) -> PassengerWorkspace): PassengerWorkspace = synchronized(lock) {
        change(load()).also { write(encode(it)) }
    }

    fun exportSettings(): JSONObject = encode(load())

    fun restoreSettings(data: JSONObject) = synchronized(lock) { write(validateSettings(data)) }

    private fun write(data: JSONObject) {
        val old = preferences.getString(KEY, null)
        if (preferences.edit().putString(KEY, data.toString()).commit()) return
        preferences.edit().putString(KEY, old).commit()
        error("Passenger workspace settings could not be saved")
    }

    companion object {
        const val STORE = "passenger_workspace"
        private const val KEY = "workspace"
        private val lock = Any()

        @JvmStatic
        fun validateSettings(data: JSONObject): JSONObject = encode(decode(data))

        private fun decode(data: JSONObject): PassengerWorkspace {
            require(data.get("schema") == 1)
            return PassengerWorkspace(apps(data.getJSONArray("slots"), PassengerWorkspace.SLOT_COUNT),
                apps(data.getJSONArray("dock"), PassengerWorkspace.DOCK_COUNT),
                data.get("compact") as Boolean, data.get("clock") as Boolean, data.get("date") as Boolean,
                data.get("openOnStartup") as Boolean)
        }

        private fun apps(data: JSONArray, expected: Int): List<String?> {
            require(data.length() == expected)
            return List(expected) { index -> if (data.isNull(index)) null else data.get(index) as String }
        }

        private fun encode(value: PassengerWorkspace): JSONObject = JSONObject().put("schema", 1)
            .put("slots", JSONArray(value.slots.map { it ?: JSONObject.NULL }))
            .put("dock", JSONArray(value.dock.map { it ?: JSONObject.NULL }))
            .put("compact", value.compact).put("clock", value.clock).put("date", value.date)
            .put("openOnStartup", value.openOnStartup)
    }
}
