package org.openvoyah.controls.data.settings

import android.content.Context
import org.json.JSONObject
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.permissions.ActionCatalog
import org.openvoyah.controls.domain.permissions.ActionItem
import org.openvoyah.controls.domain.permissions.ActionPermission

/**
 * Settings → Allowed actions. Only the owner's changes are stored: an absent key means [ActionPermission.ALLOWED].
 * The store is part of the settings archive. Gates read it on every use, so a change applies to the next command;
 * agent channels that are already running follow at the next connection.
 */
class ActionPermissions(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    init { migrateVehicleCommandSwitches() }

    fun state(item: ActionItem): ActionPermission = ActionPermission.parse(prefs.getString(item.key, null)) ?: ActionPermission.ALLOWED

    fun operation(id: String): ActionPermission = state(ActionCatalog.operation(id))
    fun agent(id: String): ActionPermission = state(ActionCatalog.agent(id))
    fun voice(id: String): ActionPermission =
        if (ActionCatalog.isVehicleCommand(id)) state(ActionCatalog.voice(id)) else ActionPermission.ALLOWED

    /** The owner's non-default choices, by archive key. */
    fun changes(): Map<String, ActionPermission> = prefs.all.mapNotNull { (key, value) ->
        val state = ActionPermission.parse(value as? String) ?: return@mapNotNull null
        if (ActionCatalog.parseKey(key) == null || state == ActionPermission.ALLOWED) null else key to state
    }.toMap()

    fun set(item: ActionItem, state: ActionPermission) {
        val editor = prefs.edit()
        if (state == ActionPermission.ALLOWED) editor.remove(item.key) else editor.putString(item.key, state.name)
        editor.apply()
        ControlsLog.info("action_permission", "action", item.key, "state", state.name.lowercase())
    }

    /** Replaces every choice at once; items not in [changes] return to the default. */
    fun replace(changes: Map<ActionItem, ActionPermission>) {
        require(changes.size <= MAX_ITEMS) { "Too many action permissions" }
        val editor = prefs.edit().clear()
        for ((item, state) in changes) if (state != ActionPermission.ALLOWED) editor.putString(item.key, state.name)
        editor.apply()
        ControlsLog.info("action_permission", "changed", changes.count { it.value != ActionPermission.ALLOWED })
    }

    /** Called right before an allowed action runs; an unverified one leaves a journal entry every time. */
    fun recordUse(item: ActionItem, state: ActionPermission = state(item)) {
        if (state == ActionPermission.UNVERIFIED) ControlsLog.info("action_unverified_use", "action", item.key)
    }

    fun exportSettings(): JSONObject = encode(changes())

    fun restoreSettings(source: JSONObject) {
        val valid = decode(validateSettings(source))
        val editor = prefs.edit().clear()
        for ((key, state) in valid) editor.putString(key, state.name)
        if (!editor.commit()) throw java.io.IOException("Action permissions were not saved")
    }

    /**
     * The earlier Voice settings → Vehicle commands switches become Forbidden once. The old store is cleared, so a
     * restored archive cannot be overridden by it later.
     */
    private fun migrateVehicleCommandSwitches() {
        val old = app.getSharedPreferences(LEGACY_STORE, Context.MODE_PRIVATE)
        val switchedOff = old.getStringSet(LEGACY_KEY, null) ?: return
        val editor = prefs.edit()
        for (id in switchedOff) if (ActionCatalog.isVehicleCommand(id) && !prefs.contains(ActionCatalog.voice(id).key)) {
            editor.putString(ActionCatalog.voice(id).key, ActionPermission.FORBIDDEN.name)
        }
        editor.commit()
        old.edit().remove(LEGACY_KEY).commit()
        ControlsLog.info("action_permission_migration", "forbidden", switchedOff.size)
    }

    companion object {
        const val STORE = "action_permissions"
        private const val LEGACY_STORE = "vehicle_commands"
        private const val LEGACY_KEY = "switchedOff"
        private const val MAX_ITEMS = 512

        /** Accepts an archive section and returns its canonical form. Keys this version does not know are dropped. */
        @JvmStatic
        fun validateSettings(source: JSONObject): JSONObject {
            require(source.optInt("schema") == 1) { "Unsupported action permissions" }
            val items = source.getJSONObject("items")
            require(items.length() <= MAX_ITEMS) { "Too many action permissions" }
            val result = linkedMapOf<String, ActionPermission>()
            for (key in items.keys()) {
                val state = requireNotNull(ActionPermission.parse(items.get(key) as? String)) { "Invalid action permission" }
                if (ActionCatalog.parseKey(key) != null && state != ActionPermission.ALLOWED) result[key] = state
            }
            return encode(result)
        }

        private fun encode(changes: Map<String, ActionPermission>): JSONObject {
            val items = JSONObject()
            for ((key, state) in changes.toSortedMap()) items.put(key, state.name)
            return JSONObject().put("schema", 1).put("items", items)
        }

        private fun decode(source: JSONObject): Map<String, ActionPermission> {
            val items = source.getJSONObject("items")
            return items.keys().asSequence().associateWith { requireNotNull(ActionPermission.parse(items.getString(it))) }
        }
    }
}
