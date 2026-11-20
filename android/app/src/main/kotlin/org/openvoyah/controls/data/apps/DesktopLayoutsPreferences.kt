package org.openvoyah.controls.data.apps

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.UUID
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
import org.json.JSONObject
import org.openvoyah.controls.data.settings.LocalSettingsArchive
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.DesktopLayoutException
import org.openvoyah.controls.domain.apps.DesktopLayoutPreset
import org.openvoyah.controls.domain.apps.DesktopLayoutProblem

internal data class DesktopLayoutRevision(val id: String, val contentHash: String)

internal class DesktopLayoutsPreferences(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private val archive by lazy { LocalSettingsArchive(app) }

    fun observe(): Flow<Result<List<DesktopLayoutPreset>>> = callbackFlow<Unit> {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == LIBRARY || key == null) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map {
        runCatching { synchronized(lock) { read().layouts.map { it.preset } } }.onFailure {
            ControlsLog.warn("desktop_layouts", "outcome", "invalid_library", "error", it)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun save(name: String) = withContext(Dispatchers.IO) {
        val normalized = requireName(name)
        val captured = archive.captureDesktopLayout()
        val grid = AppGridPreferences.validateLayout(captured.getJSONObject(GRID))
        val cards = DesktopInfoPreferences.validateLayout(captured.getJSONObject(CARDS))
        val wallpapers = captured.getJSONObject(WALLPAPERS)
        synchronized(lock) {
            val current = read()
            current.requireFreeSlot()
            current.requireUniqueName(normalized)
            val images = current.images.toMutableMap()
            val references = AppGridDesktop.entries.associateWith { desktop ->
                val raw = wallpapers.get(desktop.storedValue)
                if (raw === JSONObject.NULL) null else {
                    val image = raw as? String ?: error("Invalid wallpaper rendition")
                    require(image.length in 1..DesktopWallpaperPreferences.MAX_ENCODED_BYTES)
                    fingerprint(image).also { hash ->
                        if (hash !in images) {
                            DesktopWallpaperPreferences.validateRendition(image)
                            images[hash] = image
                        } else require(images[hash] == image)
                    }
                }
            }
            val layout = Layout(DesktopLayoutPreset(UUID.randomUUID().toString(), normalized), grid, cards, references)
            write(Library(current.layouts + layout, images).encode())
        }
    }

    suspend fun rename(id: String, name: String) = modify { current ->
        val layout = current.find(id)
        val normalized = requireName(name)
        current.requireUniqueName(normalized, except = id)
        current.copy(layouts = current.layouts.map {
            if (it.preset.id == id) layout.copy(preset = layout.preset.copy(name = normalized)) else it
        })
    }

    suspend fun duplicate(id: String, name: String) = modify { current ->
        val source = current.find(id)
        val normalized = requireName(name)
        current.requireFreeSlot()
        current.requireUniqueName(normalized)
        val preset = DesktopLayoutPreset(UUID.randomUUID().toString(), normalized)
        current.copy(layouts = current.layouts + source.copy(preset = preset))
    }

    suspend fun delete(id: String) = modify { current ->
        current.find(id)
        current.copy(layouts = current.layouts.filterNot { it.preset.id == id })
    }

    fun revision(id: String): DesktopLayoutRevision = synchronized(lock) { read().find(id).revision() }

    suspend fun apply(id: String) = withContext(Dispatchers.IO) { archive.applySavedDesktopLayout(id) }

    fun interface LayoutAction {
        @Throws(Exception::class)
        fun apply(grid: JSONObject, wallpapers: JSONObject, cards: JSONObject): Boolean
    }

    /** The archive owns the desktop write lock before entering the library and releases it after the action. */
    @Throws(Exception::class)
    fun withLayout(id: String, expected: DesktopLayoutRevision?, action: LayoutAction): Boolean = synchronized(lock) {
        val current = read()
        val layout = current.find(id)
        if (expected != null && expected != layout.revision()) fail(DesktopLayoutProblem.MISSING)
        val wallpapers = JSONObject().apply {
            for ((desktop, hash) in layout.wallpapers) {
                val image = hash?.let { current.images.getValue(it) }
                if (image != null) require(fingerprint(image) == hash) { "Desktop layout wallpaper does not match its reference" }
                put(desktop.storedValue, image ?: JSONObject.NULL)
            }
        }
        action.apply(layout.grid, wallpapers, layout.cards)
    }

    fun exportSettings(): JSONObject = synchronized(lock) { read().encode() }

    fun restoreSettings(data: JSONObject) {
        val validated = validateSettings(data)
        synchronized(lock) { write(validated) }
    }

    private suspend fun modify(change: (Library) -> Library) = withContext(Dispatchers.IO) {
        synchronized(lock) { write(change(read()).encode()) }
    }

    private fun read(): Library {
        val raw = preferences.getString(LIBRARY, null) ?: return Library()
        require(raw.length <= MAX_BYTES)
        return decode(JSONObject(raw), validateImages = false)
    }

    private fun write(data: JSONObject) {
        checkSize(data)
        val raw = data.toString()
        val previous = preferences.all[LIBRARY] as? String
        if (raw == previous) return
        if (!preferences.edit().putString(LIBRARY, raw).commit()) {
            val restored = preferences.edit().putString(LIBRARY, previous).commit()
            ControlsLog.warn("desktop_layouts", "outcome", "commit_failed", "rolledBack", restored)
            error("Could not save desktop layouts")
        }
    }

    private data class Layout(
        val preset: DesktopLayoutPreset,
        val grid: JSONObject,
        val cards: JSONObject,
        val wallpapers: Map<AppGridDesktop, String?>,
    ) {
        fun revision() = DesktopLayoutRevision(preset.id, fingerprint(encodeContent(JSONObject()).toString()))

        fun encode(): JSONObject = encodeContent(JSONObject().put("id", preset.id).put("name", preset.name))

        private fun encodeContent(target: JSONObject): JSONObject {
            val references = JSONObject()
            for ((desktop, hash) in wallpapers) references.put(desktop.storedValue, hash ?: JSONObject.NULL)
            return target.put(GRID, grid).put(CARDS, cards).put(WALLPAPERS, references)
        }
    }

    private data class Library(val layouts: List<Layout> = emptyList(), val images: Map<String, String> = emptyMap()) {
        fun find(id: String): Layout = layouts.firstOrNull { it.preset.id == id } ?: fail(DesktopLayoutProblem.MISSING)

        fun requireFreeSlot() {
            if (layouts.size >= DesktopLayoutPreset.MAX_PRESETS) fail(DesktopLayoutProblem.FULL)
        }

        fun requireUniqueName(name: String, except: String? = null) {
            if (layouts.any { it.preset.id != except && it.preset.name.equals(name, ignoreCase = true) }) {
                fail(DesktopLayoutProblem.DUPLICATE_NAME)
            }
        }

        fun encode(): JSONObject {
            val referenced = layouts.flatMap { it.wallpapers.values }.filterNotNull().toSortedSet()
            val imagePool = JSONObject()
            for (hash in referenced) imagePool.put(hash, images.getValue(hash))
            return JSONObject().put(PRESETS, JSONArray(layouts.map(Layout::encode))).put(IMAGES, imagePool)
        }
    }

    companion object {
        const val STORE = "desktop_layouts"
        const val MAX_BYTES = 1_048_576
        private const val LIBRARY = "library"
        private const val PRESETS = "presets"
        private const val IMAGES = "images"
        private const val GRID = "appGrid"
        private const val CARDS = "desktopInfoCards"
        private const val WALLPAPERS = "desktopWallpapers"
        private const val HEX = "0123456789abcdef"
        private val lock = Any()
        private val fingerprintPattern = Regex("[0-9a-f]{64}")

        @JvmStatic
        fun validateSettings(data: JSONObject): JSONObject {
            checkSize(data)
            val library = decode(data, validateImages = true)
            return library.encode().also(::checkSize)
        }

        private fun decode(data: JSONObject, validateImages: Boolean): Library {
            if (data.length() == 0) return Library()
            require(data.length() == 2)
            val presets = data.getJSONArray(PRESETS)
            val imagePool = data.getJSONObject(IMAGES)
            require(presets.length() <= DesktopLayoutPreset.MAX_PRESETS)
            require(imagePool.length() <= DesktopLayoutPreset.MAX_PRESETS * AppGridDesktop.entries.size)
            val images = imagePool.keys().asSequence().associateWith { hash ->
                require(fingerprintPattern.matches(hash))
                val image = imagePool.string(hash)
                require(image.length in 1..DesktopWallpaperPreferences.MAX_ENCODED_BYTES)
                image
            }
            val layouts = List(presets.length()) { index ->
                val preset = presets.getJSONObject(index)
                require(preset.length() == 5)
                val name = requireName(preset.string("name"))
                val references = preset.getJSONObject(WALLPAPERS)
                require(references.length() == AppGridDesktop.entries.size)
                val wallpapers = AppGridDesktop.entries.associateWith { desktop ->
                    val key = desktop.storedValue
                    if (references.get(key) === JSONObject.NULL) null
                    else references.string(key).also { require(it in images) }
                }
                Layout(DesktopLayoutPreset(preset.string("id"), name),
                    AppGridPreferences.validateLayout(preset.getJSONObject(GRID)),
                    DesktopInfoPreferences.validateLayout(preset.getJSONObject(CARDS)), wallpapers)
            }
            require(layouts.distinctBy { it.preset.id }.size == layouts.size)
            require(layouts.indices.none { index ->
                layouts.take(index).any { it.preset.name.equals(layouts[index].preset.name, ignoreCase = true) }
            })
            require(layouts.flatMap { it.wallpapers.values }.filterNotNull().toSet() == images.keys)
            if (validateImages) for ((hash, image) in images) {
                require(fingerprint(image) == hash)
                DesktopWallpaperPreferences.validateRendition(image)
            }
            return Library(layouts, images)
        }

        private fun checkSize(data: JSONObject) {
            if (data.toString(2).toByteArray(Charsets.UTF_8).size > MAX_BYTES) fail(DesktopLayoutProblem.SIZE_LIMIT)
        }

        private fun JSONObject.string(key: String): String = get(key) as? String ?: error("Invalid desktop layout field")

        private fun requireName(name: String): String =
            DesktopLayoutPreset.normalizeName(name) ?: fail(DesktopLayoutProblem.INVALID_NAME)

        private fun fail(problem: DesktopLayoutProblem): Nothing = throw DesktopLayoutException(problem)

        private fun fingerprint(content: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            return buildString(bytes.size * 2) {
                for (byte in bytes) {
                    val value = byte.toInt() and 0xff
                    append(HEX[value ushr 4])
                    append(HEX[value and 0xf])
                }
            }
        }
    }
}
