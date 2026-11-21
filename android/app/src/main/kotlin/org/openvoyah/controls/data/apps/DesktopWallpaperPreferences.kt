package org.openvoyah.controls.data.apps

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.platform.apps.DesktopWallpaperImages

/** Holds only bounded private JPEG renditions. The user's original document stays with its provider. */
internal class DesktopWallpaperPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun observeCustomDesktops(): Flow<Set<AppGridDesktop>> = changes().map {
        synchronized(lock) { AppGridDesktop.entries.filterTo(mutableSetOf()) { preferences.contains(it.storedValue) } }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    fun observeImage(desktop: AppGridDesktop): Flow<Bitmap?> = changes()
        .map { synchronized(lock) { preferences.getString(desktop.storedValue, null) } }
        .distinctUntilChanged()
        .map { data -> data?.let { decodeImage(desktop, it) } }
        .flowOn(Dispatchers.IO)

    // Exception, not Throwable: an OutOfMemoryError while decoding must not read as a damaged rendition.
    private fun decodeImage(desktop: AppGridDesktop, data: String): Bitmap? = try {
        DesktopWallpaperImages.decode(decodeBase64(data))
    } catch (failure: Exception) {
        ControlsLog.warn("store_decode", failure, "store", STORE, "outcome", "image_unreadable", "desktop", desktop.storedValue)
        null
    }

    fun set(desktop: AppGridDesktop, rendition: ByteArray?) {
        val encoded = rendition?.let {
            DesktopWallpaperImages.decode(it).recycle()
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
        save(mapOf(desktop.storedValue to encoded))
    }

    fun exportSettings(): JSONObject = synchronized(lock) {
        JSONObject().apply {
            for (desktop in AppGridDesktop.entries) {
                val key = desktop.storedValue
                put(key, preferences.getString(key, null) ?: JSONObject.NULL)
            }
        }
    }

    fun restoreSettings(value: JSONObject) {
        save(deserialize(value))
    }

    private fun changes() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    private fun save(values: Map<String, String?>) = synchronized(lock) {
        val edits = values.filter { (key, data) -> preferences.getString(key, null) != data }
        if (edits.isEmpty()) return@synchronized
        val previous = edits.keys.associateWith { preferences.getString(it, null) }
        val editor = preferences.edit()
        edits.forEach { (key, data) -> editor.putString(key, data) }
        if (!editor.commit()) {
            val rollback = preferences.edit()
            previous.forEach { (key, data) -> rollback.putString(key, data) }
            rollback.commit()
            error("Desktop wallpaper settings could not be saved")
        }
    }

    companion object {
        const val STORE = "desktop_wallpapers"
        internal const val MAX_ENCODED_BYTES = (DesktopWallpaperImages.MAX_RENDITION_BYTES + 2) / 3 * 4
        private val allowedKeys = AppGridDesktop.entries.mapTo(mutableSetOf()) { it.storedValue }
        private val lock = DesktopLayoutStores.WRITE_LOCK

        @JvmStatic
        fun validateSettings(value: JSONObject): JSONObject = JSONObject().apply {
            deserialize(value).forEach { (key, data) -> put(key, data ?: JSONObject.NULL) }
        }

        @JvmStatic
        fun validateLayout(value: JSONObject): JSONObject {
            val validated = validateSettings(value)
            return JSONObject().apply {
                for (desktop in AppGridDesktop.entries) {
                    val key = desktop.storedValue
                    put(key, validated.opt(key) ?: JSONObject.NULL)
                }
            }
        }

        private fun deserialize(value: JSONObject): Map<String, String?> = value.keys().asSequence().associateWith { key ->
            require(key in allowedKeys) { "Unknown wallpaper desktop" }
            val raw = value.get(key)
            if (raw == JSONObject.NULL) null else {
                val data = raw as? String ?: error("Invalid wallpaper rendition")
                validateRendition(data)
                data
            }
        }

        internal fun validateRendition(data: String) {
            val bytes = decodeBase64(data)
            require(Base64.encodeToString(bytes, Base64.NO_WRAP) == data)
            DesktopWallpaperImages.decode(bytes).recycle()
        }

        private fun decodeBase64(value: String): ByteArray {
            require(value.length in 1..MAX_ENCODED_BYTES)
            return Base64.decode(value, Base64.NO_WRAP)
        }
    }
}
