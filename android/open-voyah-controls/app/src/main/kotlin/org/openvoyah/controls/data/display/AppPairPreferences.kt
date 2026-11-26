package org.openvoyah.controls.data.display

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.domain.display.AppPair

internal class AppPairPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun load(): List<AppPair> = decode(JSONArray(preferences.getString(KEY, "[]") ?: "[]"))

    fun observe(): Flow<Result<List<AppPair>>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY || key == null) trySend(runCatching(::load))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(runCatching(::load))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun save(pair: AppPair) = synchronized(lock) {
        val pairs = load().toMutableList()
        val index = pairs.indexOfFirst { it.id == pair.id }
        if (index >= 0) pairs[index] = pair else pairs.add(pair)
        require(pairs.size <= AppPair.MAX_PAIRS)
        write(encode(pairs))
    }

    fun remove(id: String) = synchronized(lock) { write(encode(load().filterNot { it.id == id })) }

    fun exportSettings(): JSONArray = encode(load())

    fun restoreSettings(data: JSONArray) = synchronized(lock) { write(validateSettings(data)) }

    private fun write(data: JSONArray) {
        val old = preferences.getString(KEY, null)
        if (preferences.edit().putString(KEY, data.toString()).commit()) return
        preferences.edit().putString(KEY, old).commit()
        error("App pair settings could not be saved")
    }

    companion object {
        const val STORE = "app_pairs"
        private const val KEY = "pairs"
        private val lock = Any()

        @JvmStatic
        fun validateSettings(data: JSONArray): JSONArray = encode(decode(data))

        private fun decode(data: JSONArray): List<AppPair> {
            require(data.length() <= AppPair.MAX_PAIRS)
            val pairs = (0 until data.length()).map { index ->
                val entry = data.getJSONObject(index)
                AppPair(entry.get("id") as String, entry.get("name") as String,
                    entry.get("left") as String, entry.get("right") as String,
                    entry.get("leftPercent") as Int, entry.get("inDock") as Boolean)
            }
            require(pairs.distinctBy(AppPair::id).size == pairs.size)
            return pairs
        }

        private fun encode(pairs: List<AppPair>): JSONArray = JSONArray(pairs.map {
            JSONObject().put("id", it.id).put("name", it.name).put("left", it.left).put("right", it.right)
                .put("leftPercent", it.leftPercent).put("inDock", it.inDock)
        })
    }
}
