package org.openvoyah.controls.data.apps

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import org.json.JSONObject
import org.openvoyah.controls.data.settings.SettingsStore
import org.openvoyah.controls.platform.apps.AppIconImages

/**
 * Per-app icon choices, keyed by package: a custom picture, kept as a small PNG rendition, and removal of the
 * white plate of the launcher icon (experimental). The app grid and the launcher panel both follow them.
 * Yandex Navigator starts without its white plate; any other app starts with its launcher icon. Only choices that
 * differ from that start are stored, so turning Navigator's choice off is kept as a choice of its own.
 */
internal class AppIconPreferences(context: Context) {
    data class Choice(val plain: Boolean = false, val custom: String? = null) {
        /** The launcher icon as it is. */
        val isDefault: Boolean get() = !plain && custom == null
        fun rendition(): ByteArray? = custom?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    private val preferences = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun choice(packageName: String): Choice = synchronized(lock) {
        parse(preferences.getString(packageName, null)) ?: initial(packageName)
    }

    /** Every app whose icon differs from its launcher icon, including the apps that start that way. */
    fun all(): Map<String, Choice> = synchronized(lock) {
        val stored = stored()
        PLAIN_FROM_START.associateWith { initial(it) } + stored
    }

    private fun stored(): Map<String, Choice> =
        preferences.all.mapNotNull { (key, value) -> (value as? String)?.let { raw -> parse(raw)?.let { key to it } } }.toMap()

    fun setPlain(packageName: String, plain: Boolean) = update(packageName) { it.copy(plain = plain) }

    /** A null rendition returns to the launcher icon. */
    fun setCustom(packageName: String, rendition: ByteArray?) {
        val encoded = rendition?.let {
            AppIconImages.decode(it).recycle()
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
        update(packageName) { it.copy(custom = encoded) }
    }

    fun changes(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    fun exportSettings(): JSONObject = synchronized(lock) {
        JSONObject().apply { stored().toSortedMap().forEach { (key, choice) -> put(key, encode(choice)) } }
    }

    /** Replaces every choice with the archive's. */
    fun restoreSettings(value: JSONObject) {
        val choices = deserialize(value)
        synchronized(lock) {
            val editor = preferences.edit().clear()
            choices.forEach { (key, choice) -> editor.putString(key, encode(choice).toString()) }
            check(editor.commit()) { "App icon settings could not be saved" }
        }
    }

    private fun update(packageName: String, edit: (Choice) -> Choice) = synchronized(lock) {
        require(PACKAGE.matches(packageName)) { "Invalid package" }
        val next = edit(parse(preferences.getString(packageName, null)) ?: initial(packageName))
        val editor = preferences.edit()
        if (next == initial(packageName)) editor.remove(packageName) else editor.putString(packageName, encode(next).toString())
        check(editor.commit()) { "App icon settings could not be saved" }
    }

    companion object {
        const val STORE = "app_icons"
        private val PACKAGE = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
        private const val MAX_APPS = 256
        private val MAX_ENCODED_BYTES = (AppIconImages.MAX_RENDITION_BYTES + 2) / 3 * 4
        private val lock = Any()
        private val PLAIN_FROM_START = setOf(SettingsStore.YANDEX_NAVIGATOR)

        /** The choice an app has until the driver makes one. */
        fun initial(packageName: String) = Choice(plain = packageName in PLAIN_FROM_START)

        // A damaged entry reads as no choice at all.
        private fun parse(raw: String?): Choice? = try {
            raw?.let { decode(JSONObject(it)) }
        } catch (_: Exception) {
            null
        }

        private fun encode(choice: Choice) = JSONObject().put("plain", choice.plain).put("custom", choice.custom ?: JSONObject.NULL)

        private fun decode(value: JSONObject): Choice {
            val custom = value.opt("custom").takeUnless { it == null || it == JSONObject.NULL }?.let {
                val data = it as? String ?: error("Invalid custom icon")
                require(data.length in 1..MAX_ENCODED_BYTES)
                val bytes = Base64.decode(data, Base64.NO_WRAP)
                require(Base64.encodeToString(bytes, Base64.NO_WRAP) == data)
                AppIconImages.decode(bytes).recycle()
                data
            }
            return Choice(value.optBoolean("plain", false), custom)
        }

        private fun deserialize(value: JSONObject): Map<String, Choice> {
            require(value.length() <= MAX_APPS) { "Too many app icons" }
            return value.keys().asSequence().associateWith { key ->
                require(PACKAGE.matches(key)) { "Invalid package" }
                decode(value.getJSONObject(key))
            }.filter { (key, choice) -> choice != initial(key) }
        }

        @JvmStatic
        fun validateSettings(value: JSONObject): JSONObject = JSONObject().apply {
            deserialize(value).forEach { (key, choice) -> put(key, encode(choice)) }
        }
    }
}
