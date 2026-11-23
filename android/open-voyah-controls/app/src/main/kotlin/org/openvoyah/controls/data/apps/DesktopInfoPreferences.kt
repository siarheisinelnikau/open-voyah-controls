package org.openvoyah.controls.data.apps

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.DesktopInfoCard
import org.openvoyah.controls.domain.apps.DesktopClockFormat
import org.openvoyah.controls.domain.apps.DesktopHeadlightContent
import org.openvoyah.controls.domain.apps.DesktopInfoConfiguration
import org.openvoyah.controls.domain.apps.DesktopInfoSize
import org.openvoyah.controls.domain.apps.DesktopMediaOpenTarget
import org.openvoyah.controls.domain.apps.DesktopMediaStyle
import org.openvoyah.controls.domain.apps.DesktopRouteContent
import org.openvoyah.controls.domain.apps.DesktopRouteOpenTarget
import org.openvoyah.controls.domain.apps.MAX_DESKTOP_PHONE_FAVORITES
import org.openvoyah.controls.domain.apps.PhoneFavoriteEdit
import org.openvoyah.controls.domain.apps.validPhoneFavoriteId
import org.openvoyah.controls.domain.display.validMediaPackage

internal class DesktopInfoPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun observe(): Flow<Result<DesktopInfoConfiguration>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == CARDS || key == null) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map {
        synchronized(lock) {
            try {
                Result.success(read())
            } catch (error: JSONException) {
                ControlsLog.warn("desktop_info_settings", error, "outcome", "invalid_stored_settings")
                Result.failure(error)
            }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun set(desktop: AppGridDesktop, card: DesktopInfoCard, size: DesktopInfoSize?) =
        update { it.set(desktop, card, size) }

    suspend fun setMediaStyle(desktop: AppGridDesktop, style: DesktopMediaStyle) = update { it.withMediaStyle(desktop, style) }

    suspend fun setClockFormat(desktop: AppGridDesktop, format: DesktopClockFormat) = update { it.withClockFormat(desktop, format) }
    suspend fun setMediaPackage(desktop: AppGridDesktop, packageName: String) = update { it.withMediaPackage(desktop, packageName) }
    suspend fun setMediaOpenTarget(desktop: AppGridDesktop, target: DesktopMediaOpenTarget) =
        update { it.withMediaOpenTarget(desktop, target) }

    suspend fun setHeadlightContent(desktop: AppGridDesktop, content: DesktopHeadlightContent) =
        update { it.withHeadlightContent(desktop, content) }

    suspend fun setRouteContent(desktop: AppGridDesktop, content: DesktopRouteContent) =
        update { it.withRouteContent(desktop, content) }

    suspend fun setRouteOpenTarget(desktop: AppGridDesktop, target: DesktopRouteOpenTarget) =
        update { it.withRouteOpenTarget(desktop, target) }

    suspend fun editPhoneFavorite(desktop: AppGridDesktop, id: String, edit: PhoneFavoriteEdit) = update {
        it.withPhoneFavorites(desktop, edit.applyTo(it.phoneFavoritesOf(desktop), id))
    }

    private suspend fun update(change: (DesktopInfoConfiguration) -> DesktopInfoConfiguration) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = read()
            val updated = change(current)
            if (updated != current) write(updated)
        }
    }

    fun exportSettings(): JSONObject = synchronized(lock) { encode(read()) }

    fun restoreSettings(source: JSONObject) = synchronized(lock) {
        val previous = try { read() } catch (failure: JSONException) {
            ControlsLog.warn("store_decode", failure, "store", STORE, "outcome", "defaults_used")
            DesktopInfoConfiguration()
        }
        write(decode(source, previous))
    }

    private fun read(): DesktopInfoConfiguration = when (val raw = preferences.all[CARDS]) {
        null -> DesktopInfoConfiguration()
        is String -> decode(JSONObject(raw))
        else -> throw JSONException("Invalid desktop card settings")
    }

    private fun write(configuration: DesktopInfoConfiguration) {
        val previous = preferences.all[CARDS] as? String
        if (preferences.edit().putString(CARDS, encode(configuration).toString()).commit()) return
        val restored = preferences.edit().putString(CARDS, previous).commit()
        ControlsLog.warn("desktop_info_settings", "outcome", "commit_failed", "rolledBack", restored)
        throw IllegalStateException("Could not save desktop card settings")
    }

    companion object {
        const val STORE = "desktop_info_cards"
        private const val CARDS = "cards"
        private const val MEDIA_STYLE = "mediaStyle"
        private const val MEDIA_PACKAGE = "preferredMediaPackage"
        private const val MEDIA_OPEN_TARGET = "mediaOpenTarget"
        private const val CLOCK_FORMAT = "clockFormat"
        private const val PHONE_FAVORITES = "phoneFavorites"
        private const val HEADLIGHT_CONTENT = "headlightContent"
        private const val ROUTE_CONTENT = "routeContent"
        private const val ROUTE_OPEN_TARGET = "routeOpenTarget"
        private val lock = DesktopLayoutStores.WRITE_LOCK

        @JvmStatic
        fun validateSettings(source: JSONObject): JSONObject = JSONObject(source.toString()).also { decode(it) }

        @JvmStatic
        fun validateLayout(source: JSONObject): JSONObject = encode(decode(source), preserveOmissionsFrom = source)

        private fun decode(source: JSONObject, previous: DesktopInfoConfiguration = DesktopInfoConfiguration()): DesktopInfoConfiguration {
            val desktops = AppGridDesktop.entries.associateBy { it.storedValue }
            var configuration = previous.clearTiles()
            for (desktopKey in source.keys()) {
                val desktop = desktops[desktopKey] ?: throw JSONException("Unknown desktop in card settings")
                val cards = source.getJSONObject(desktopKey)
                for (key in cards.keys()) {
                    if (key == PHONE_FAVORITES) {
                        configuration = configuration.withPhoneFavorites(desktop, decodePhoneFavorites(cards.getJSONArray(key)))
                        continue
                    }
                    val value = cards.get(key) as? String ?: throw JSONException("Invalid desktop card option")
                    configuration = when (key) {
                        MEDIA_STYLE -> configuration.withMediaStyle(desktop, DesktopMediaStyle.fromStoredValue(value)
                            ?: throw JSONException("Invalid desktop media style"))
                        MEDIA_PACKAGE -> configuration.withMediaPackage(desktop, value.takeIf(::validMediaPackage)
                            ?: throw JSONException("Invalid desktop media package"))
                        MEDIA_OPEN_TARGET -> configuration.withMediaOpenTarget(desktop, DesktopMediaOpenTarget.fromStoredValue(value)
                            ?: throw JSONException("Invalid desktop media card open target"))
                        CLOCK_FORMAT -> configuration.withClockFormat(desktop, DesktopClockFormat.fromStoredValue(value)
                            ?: throw JSONException("Invalid desktop clock format"))
                        HEADLIGHT_CONTENT -> configuration.withHeadlightContent(desktop, DesktopHeadlightContent.fromStoredValue(value)
                            ?: throw JSONException("Invalid headlight card content"))
                        ROUTE_CONTENT -> configuration.withRouteContent(desktop, DesktopRouteContent.fromStoredValue(value)
                            ?: throw JSONException("Invalid navigation card content"))
                        ROUTE_OPEN_TARGET -> configuration.withRouteOpenTarget(desktop, DesktopRouteOpenTarget.fromStoredValue(value)
                            ?: throw JSONException("Invalid navigation card open target"))
                        else -> configuration.set(desktop, DesktopInfoCard.fromStoredValue(key)
                            ?: throw JSONException("Unknown desktop card"), DesktopInfoSize.fromStoredValue(value)
                            ?: throw JSONException("Invalid desktop card size"))
                    }
                }
            }
            return configuration
        }

        private fun decodePhoneFavorites(array: JSONArray): List<String> {
            if (array.length() > MAX_DESKTOP_PHONE_FAVORITES) throw JSONException("Too many phone favorites")
            val ids = List(array.length()) { index ->
                (array.get(index) as? String)?.takeIf(::validPhoneFavoriteId) ?: throw JSONException("Invalid phone favorite ID")
            }
            if (ids.distinct().size != ids.size) throw JSONException("Duplicate phone favorite ID")
            return ids
        }

        private fun encode(
            configuration: DesktopInfoConfiguration,
            preserveOmissionsFrom: JSONObject? = null,
        ): JSONObject {
            val result = JSONObject()
            for (desktop in AppGridDesktop.entries) {
                val tiles = configuration.tiles(desktop)
                val cards = JSONObject()
                for ((card, size) in tiles) cards.put(card.storedValue, size.storedValue)
                cards.put(MEDIA_STYLE, configuration.mediaStyleOf(desktop).storedValue)
                cards.put(MEDIA_PACKAGE, configuration.mediaPackageOf(desktop))
                cards.put(CLOCK_FORMAT, configuration.clockFormatOf(desktop).storedValue)
                // Older named layouts preserve the desktop options that only newer captures include.
                val archived = preserveOmissionsFrom?.optJSONObject(desktop.storedValue)
                if (preserveOmissionsFrom == null || archived?.has(PHONE_FAVORITES) == true) {
                    cards.put(PHONE_FAVORITES, JSONArray(configuration.phoneFavoritesOf(desktop)))
                }
                if (preserveOmissionsFrom == null || archived?.has(HEADLIGHT_CONTENT) == true) {
                    cards.put(HEADLIGHT_CONTENT, configuration.headlightContentOf(desktop).storedValue)
                }
                if (preserveOmissionsFrom == null || archived?.has(MEDIA_OPEN_TARGET) == true) {
                    cards.put(MEDIA_OPEN_TARGET, configuration.mediaOpenTargetOf(desktop).storedValue)
                }
                if (preserveOmissionsFrom == null || archived?.has(ROUTE_CONTENT) == true) {
                    cards.put(ROUTE_CONTENT, configuration.routeContentOf(desktop).storedValue)
                }
                if (preserveOmissionsFrom == null || archived?.has(ROUTE_OPEN_TARGET) == true) {
                    cards.put(ROUTE_OPEN_TARGET, configuration.routeOpenTargetOf(desktop).storedValue)
                }
                result.put(desktop.storedValue, cards)
            }
            return result
        }
    }
}
