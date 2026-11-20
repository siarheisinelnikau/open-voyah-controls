package org.openvoyah.controls.data.dock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
import org.openvoyah.controls.domain.dock.MAX_DOCK_APP_SHORTCUTS
import org.openvoyah.controls.domain.dock.QuickAction
import org.openvoyah.controls.domain.dock.QuickActionAppsDestination
import org.openvoyah.controls.domain.dock.QuickActionArea
import org.openvoyah.controls.domain.dock.QuickActionCompactEdge
import org.openvoyah.controls.domain.dock.QuickActionCompactHandleSide
import org.openvoyah.controls.domain.dock.QuickActionCompactLabels
import org.openvoyah.controls.domain.dock.QuickActionHandleAccent
import org.openvoyah.controls.domain.dock.QuickActionHandleSize
import org.openvoyah.controls.domain.dock.QuickActionHeadlightContent
import org.openvoyah.controls.domain.dock.QuickActionHeadlightTap
import org.openvoyah.controls.domain.dock.QuickActionIdleCollapse
import org.openvoyah.controls.domain.dock.QuickActionRailAlignment
import org.openvoyah.controls.domain.dock.QuickActionRailWidth
import org.openvoyah.controls.domain.dock.QuickActionSettings
import org.openvoyah.controls.domain.dock.QuickActionSide
import org.openvoyah.controls.domain.dock.QuickActionStartState
import org.openvoyah.controls.domain.dock.QuickActionStore

internal class QuickActionPreferences(context: Context) : QuickActionStore {
    private val store = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    override fun observe(): Flow<QuickActionSettings> = callbackFlow<Unit> {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SETTINGS) trySend(Unit)
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { synchronized(lock) { read() } }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun update(change: (QuickActionSettings) -> QuickActionSettings) =
        withContext(Dispatchers.IO) { edit(change) }

    fun exportSettings(): JSONObject = synchronized(lock) { encode(read()) }

    fun restoreSettings(source: JSONObject) = edit { current -> decode(source, current) }

    private fun read(): QuickActionSettings {
        val raw = store.all[SETTINGS] as? String ?: return QuickActionSettings()
        return try {
            decode(JSONObject(raw), QuickActionSettings())
        } catch (_: JSONException) {
            invalidSavedSettings()
        } catch (_: IllegalArgumentException) {
            invalidSavedSettings()
        }
    }

    private fun invalidSavedSettings(): QuickActionSettings {
        Log.w(TAG, "event=quick_actions outcome=invalid_saved_settings")
        return QuickActionSettings()
    }

    private fun edit(change: (QuickActionSettings) -> QuickActionSettings) = synchronized(lock) {
        val current = read()
        val next = change(current)
        if (next != current) {
            val previous = store.all[SETTINGS] as? String
            if (!store.edit().putString(SETTINGS, encode(next).toString()).commit()) {
                // SharedPreferences updates memory even if the disk write fails.
                val rolledBack = store.edit().putString(SETTINGS, previous).commit()
                Log.w(TAG, "event=quick_actions outcome=commit_failed rolledBack=$rolledBack")
                error("Could not save quick actions")
            }
        }
    }

    companion object {
        const val STORE = "quick_actions"
        private const val SETTINGS = "settings"
        private const val TAG = "OpenVoyahControls"
        private val keys = setOf("enabled", "side", "showLabels", "area", "actions", "compactEdge", "collapseAfterUse",
            "highContrastHandle", "showCompactHandleLabel", "appsDestination", "railWidth", "idleCollapseSeconds",
            "startState", "compactLabels", "showHeadlightMode", "handleSize", "compactHandleSide", "headlightContent",
            "appShortcuts", "sideRailAlignment", "hideOnVirtualDisplay", "keepSideInset", "headlightTap", "handleAccent",
            "collapsedHandleAlignment", "dimIdleHandle", "routineShortcutId", "largeActions")
        private val lock = Any()

        @JvmStatic
        fun validateSettings(source: JSONObject): JSONObject {
            decode(source, QuickActionSettings())
            return JSONObject(source.toString())
        }

        private fun encode(settings: QuickActionSettings): JSONObject = JSONObject()
            .put("enabled", settings.enabled)
            .put("side", settings.side.id)
            .put("showLabels", settings.showLabels)
            .put("area", settings.area.id)
            .put("actions", JSONArray(settings.actions.map { it.id }))
            .put("compactEdge", settings.compactEdge.id)
            .put("collapseAfterUse", settings.collapseAfterUse)
            .put("highContrastHandle", settings.highContrastHandle)
            .put("showCompactHandleLabel", settings.showCompactHandleLabel)
            .put("appsDestination", settings.appsDestination.id)
            .put("railWidth", settings.railWidth.id)
            .put("idleCollapseSeconds", settings.idleCollapse.seconds)
            .put("startState", settings.startState.id)
            .put("compactLabels", settings.compactLabels.id)
            .put("showHeadlightMode", settings.showHeadlightMode)
            .put("handleSize", settings.handleSize.id)
            .put("compactHandleSide", settings.compactHandleSide.id)
            .put("headlightContent", settings.headlightContent.id)
            .put("appShortcuts", JSONArray(settings.appShortcuts))
            .put("sideRailAlignment", settings.sideRailAlignment.id)
            .put("hideOnVirtualDisplay", settings.hideOnVirtualDisplay)
            .put("keepSideInset", settings.keepSideInset)
            .put("headlightTap", settings.headlightTap.id)
            .put("handleAccent", settings.handleAccent.id)
            .put("collapsedHandleAlignment", settings.collapsedHandleAlignment.id)
            .put("dimIdleHandle", settings.dimIdleHandle)
            .put("routineShortcutId", settings.routineShortcutId ?: JSONObject.NULL)
            .put("largeActions", JSONArray(QuickAction.entries.filter { it in settings.largeActions }.map { it.id }))

        private fun decode(source: JSONObject, base: QuickActionSettings): QuickActionSettings {
            require(source.keys().asSequence().all { it in keys })
            fun boolean(key: String, fallback: Boolean): Boolean =
                if (source.has(key)) requireNotNull(source.get(key) as? Boolean) else fallback
            val side = if (source.has("side")) {
                requireNotNull(QuickActionSide.entries.firstOrNull { it.id == source.get("side") })
            } else base.side
            val area = if (source.has("area")) {
                requireNotNull(QuickActionArea.entries.firstOrNull { it.id == source.get("area") })
            } else base.area
            val compactEdge = if (source.has("compactEdge")) {
                requireNotNull(QuickActionCompactEdge.entries.firstOrNull { it.id == source.get("compactEdge") })
            } else base.compactEdge
            val appsDestination = if (source.has("appsDestination")) {
                requireNotNull(QuickActionAppsDestination.entries.firstOrNull { it.id == source.get("appsDestination") })
            } else base.appsDestination
            val railWidth = if (source.has("railWidth")) {
                requireNotNull(QuickActionRailWidth.entries.firstOrNull { it.id == source.get("railWidth") })
            } else base.railWidth
            val sideRailAlignment = if (source.has("sideRailAlignment")) {
                requireNotNull(QuickActionRailAlignment.entries.firstOrNull { it.id == source.get("sideRailAlignment") })
            } else base.sideRailAlignment
            val idleCollapse = if (source.has("idleCollapseSeconds")) {
                val seconds = requireNotNull(source.get("idleCollapseSeconds") as? Int)
                requireNotNull(QuickActionIdleCollapse.entries.firstOrNull { it.seconds == seconds })
            } else base.idleCollapse
            val startState = if (source.has("startState")) {
                requireNotNull(QuickActionStartState.entries.firstOrNull { it.id == source.get("startState") })
            } else base.startState
            val compactLabels = if (source.has("compactLabels")) {
                requireNotNull(QuickActionCompactLabels.entries.firstOrNull { it.id == source.get("compactLabels") })
            } else base.compactLabels
            val handleSize = if (source.has("handleSize")) {
                requireNotNull(QuickActionHandleSize.entries.firstOrNull { it.id == source.get("handleSize") })
            } else base.handleSize
            val handleAccent = if (source.has("handleAccent")) {
                requireNotNull(QuickActionHandleAccent.entries.firstOrNull { it.id == source.get("handleAccent") })
            } else base.handleAccent
            val collapsedHandleAlignment = if (source.has("collapsedHandleAlignment")) {
                requireNotNull(QuickActionRailAlignment.entries.firstOrNull { it.id == source.get("collapsedHandleAlignment") })
            } else base.collapsedHandleAlignment
            val compactHandleSide = if (source.has("compactHandleSide")) {
                requireNotNull(QuickActionCompactHandleSide.entries.firstOrNull { it.id == source.get("compactHandleSide") })
            } else base.compactHandleSide
            val headlightContent = if (source.has("headlightContent")) {
                requireNotNull(QuickActionHeadlightContent.entries.firstOrNull { it.id == source.get("headlightContent") })
            } else base.headlightContent
            val headlightTap = if (source.has("headlightTap")) {
                requireNotNull(QuickActionHeadlightTap.entries.firstOrNull { it.id == source.get("headlightTap") })
            } else base.headlightTap
            val actions = if (source.has("actions")) {
                decodeActions(source, "actions")
            } else base.actions
            val largeActions = if (source.has("largeActions")) {
                val list = decodeActions(source, "largeActions")
                list.toSet().also { require(it.size == list.size) }
            } else base.largeActions
            val appShortcuts = if (source.has("appShortcuts")) {
                val list = requireNotNull(source.get("appShortcuts") as? JSONArray)
                require(list.length() <= MAX_DOCK_APP_SHORTCUTS)
                List(list.length()) { index -> requireNotNull(list.get(index) as? String) }
            } else base.appShortcuts
            val routineShortcutId = when {
                !source.has("routineShortcutId") -> base.routineShortcutId
                source.isNull("routineShortcutId") -> null
                else -> requireNotNull(source.get("routineShortcutId") as? String)
            }
            return QuickActionSettings(
                enabled = boolean("enabled", base.enabled), side = side, showLabels = boolean("showLabels", base.showLabels),
                area = area, actions = actions, compactEdge = compactEdge, collapseAfterUse = boolean("collapseAfterUse", base.collapseAfterUse),
                highContrastHandle = boolean("highContrastHandle", base.highContrastHandle),
                showCompactHandleLabel = boolean("showCompactHandleLabel", base.showCompactHandleLabel),
                appsDestination = appsDestination,
                railWidth = railWidth, idleCollapse = idleCollapse, startState = startState, compactLabels = compactLabels,
                showHeadlightMode = boolean("showHeadlightMode", base.showHeadlightMode),
                handleSize = handleSize, compactHandleSide = compactHandleSide, headlightContent = headlightContent,
                appShortcuts = appShortcuts, sideRailAlignment = sideRailAlignment,
                hideOnVirtualDisplay = boolean("hideOnVirtualDisplay", base.hideOnVirtualDisplay),
                keepSideInset = boolean("keepSideInset", base.keepSideInset), headlightTap = headlightTap,
                handleAccent = handleAccent, collapsedHandleAlignment = collapsedHandleAlignment,
                dimIdleHandle = boolean("dimIdleHandle", base.dimIdleHandle),
                routineShortcutId = routineShortcutId,
                largeActions = largeActions,
            )
        }

        private fun decodeActions(source: JSONObject, key: String): List<QuickAction> {
            val list = requireNotNull(source.get(key) as? JSONArray)
            require(list.length() <= QuickAction.entries.size)
            return List(list.length()) { index ->
                val id = requireNotNull(list.get(index) as? String)
                requireNotNull(QuickAction.entries.firstOrNull { it.id == id })
            }
        }
    }
}
