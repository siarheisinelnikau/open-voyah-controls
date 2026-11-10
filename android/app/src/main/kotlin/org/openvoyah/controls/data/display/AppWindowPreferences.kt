package org.openvoyah.controls.data.display

import android.content.Context
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.domain.display.AppWindowProfile

class AppWindowPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("app_windows", Context.MODE_PRIVATE)
    val userId: Int get() = Process.myUid() / 100_000

    fun load(component: String): AppWindowProfile {
        val defaults = AppWindowProfile(requireNotNull(AppWindowProfile.key(component)))
        return profiles().firstOrNull { it.packageName == defaults.packageName }
            ?.copy(component = defaults.component) ?: defaults
    }

    fun save(profile: AppWindowProfile) = synchronized(lock) {
        val canonical = profile.copy(component = requireNotNull(AppWindowProfile.key(profile.component)))
        val remaining = profiles().filterNot { it.packageName == canonical.packageName }
        write(if (canonical == AppWindowProfile(canonical.component)) remaining else remaining + canonical)
    }

    fun reset(component: String) = synchronized(lock) {
        val packageName = requireNotNull(AppWindowProfile.key(component)).substringBefore('/')
        write(profiles().filterNot { it.packageName == packageName })
    }

    fun agentConfig(enabled: Boolean): String = JSONObject()
        .put("schema", 1).put("user", userId).put("enabled", enabled).put("apps", exportProfiles()).toString()

    fun exportProfiles(): JSONArray = encode(profiles())

    fun restoreProfiles(data: JSONArray) = synchronized(lock) { write(decode(data)) }

    private fun profiles() = decode(JSONArray(preferences.getString("profiles", "[]") ?: "[]"))

    private fun write(profiles: List<AppWindowProfile>) {
        require(profiles.size <= AppWindowProfile.MAX_PROFILES)
        check(preferences.edit().putString("profiles", encode(profiles).toString()).commit())
    }

    companion object {
        private val lock = Any()

        @JvmStatic
        fun validateProfiles(data: JSONArray): JSONArray = encode(decode(data))

        private fun decode(data: JSONArray): List<AppWindowProfile> {
            require(data.length() <= AppWindowProfile.MAX_PROFILES)
            val profiles = (0 until data.length()).map { index ->
                val value = data.getJSONObject(index)
                value.getBoolean("keepPanel") // Validate the legacy field while migrating to a reserved strip.
                AppWindowProfile(
                    requireNotNull(AppWindowProfile.key(value.getString("component"))),
                    value.getInt("width"), value.getInt("dpi"), value.getInt("panelPx"),
                )
            }
            require(profiles.distinctBy { it.packageName }.size == profiles.size)
            return profiles
        }

        private fun encode(profiles: List<AppWindowProfile>) = JSONArray(profiles.map { profile ->
            JSONObject().put("component", profile.component).put("width", profile.widthPercent)
                .put("dpi", profile.densityDpi).put("keepPanel", true).put("panelPx", profile.panelWidthPx)
        })
    }
}
