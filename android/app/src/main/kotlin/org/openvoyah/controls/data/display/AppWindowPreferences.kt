package org.openvoyah.controls.data.display

import android.content.Context
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.display.AppDisplayDefaults
import org.openvoyah.controls.domain.display.AppWindowProfile
import org.openvoyah.controls.domain.display.WindowProfileStorage

internal class AppWindowPreferences(context: Context) : WindowProfileStorage {
    private val preferences = context.applicationContext.getSharedPreferences("app_windows", Context.MODE_PRIVATE)
    val userId: Int get() = Process.myUid() / 100_000

    override fun load(component: String): AppWindowProfile {
        val defaults = AppWindowProfile(requireNotNull(AppWindowProfile.key(component)))
        return profiles().firstOrNull { it.packageName == defaults.packageName }
            ?.copy(component = defaults.component) ?: defaults
    }

    override fun save(profile: AppWindowProfile) = synchronized(lock) {
        val canonical = profile.copy(component = requireNotNull(AppWindowProfile.key(profile.component)))
        val remaining = profiles().filterNot { it.packageName == canonical.packageName }
        write(if (canonical == AppWindowProfile(canonical.component)) remaining else remaining + canonical)
    }

    override fun reset(component: String) = synchronized(lock) {
        val packageName = requireNotNull(AppWindowProfile.key(component)).substringBefore('/')
        write(profiles().filterNot { it.packageName == packageName })
    }

    /**
     * [systemInsetsAttempt] is null unless the experimental strip reserved by Android itself is on. It counts how
     * often the driver turned the option on, which lets the agent tell a new request from one it already blocked.
     * The swipe between screens is sent only with [allowDisplayMove], the separate review of that operation.
     */
    @JvmOverloads
    fun agentConfig(
        enabled: Boolean,
        allowSecondary: Boolean = true,
        passengerDisplayId: Int? = null,
        systemInsetsAttempt: Int? = null,
        allowDisplayMove: Boolean = false,
    ): String = synchronized(lock) {
        require(passengerDisplayId == null || passengerDisplayId == 1)
        require(systemInsetsAttempt == null || systemInsetsAttempt >= 0)
        val config = JSONObject().put("schema", 1).put("user", userId).put("enabled", enabled)
            .put("apps", exportProfiles())
        if (systemInsetsAttempt != null) config.put("systemInsets", true).put("systemInsetsAttempt", systemInsetsAttempt)
        val stored = loadDefaults()
        val defaults = if (allowSecondary) stored else stored.copy(secondaryDpi = null)
        if (defaults.centralDpi != 0 || defaults.secondaryDpi != null) config.put("displayDpi", densities(defaults))
        if (defaults.secondaryDpi != null && passengerDisplayId != null) config.put("passengerDisplayId", passengerDisplayId)
        if (allowDisplayMove && stored.moveFingers != 0) {
            config.put("displayMove", JSONObject().put("fingers", stored.moveFingers)
                .put("reveal", if (stored.moveShowsApps) "apps" else "home"))
        }
        config.toString()
    }

    fun exportProfiles(): JSONArray = encode(profiles())

    fun restoreProfiles(data: JSONArray) = synchronized(lock) { write(decode(data)) }

    override fun loadDefaults(): AppDisplayDefaults {
        val value = preferences.getString("displayDefaults", null) ?: return AppDisplayDefaults()
        return validateDefaults(JSONObject(value))
    }

    override fun saveDefaults(defaults: AppDisplayDefaults) = synchronized(lock) {
        commitOrRollBack("displayDefaults", encodeDefaults(defaults).toString())
    }

    fun exportDefaults(): JSONObject = encodeDefaults(loadDefaults())

    private fun profiles() = decode(JSONArray(preferences.getString("profiles", "[]") ?: "[]"))

    private fun write(profiles: List<AppWindowProfile>) {
        require(profiles.size <= AppWindowProfile.MAX_PROFILES)
        commitOrRollBack("profiles", encode(profiles).toString())
    }

    /**
     * A failed disk write leaves the new value in memory, where the next publish would send the unsaved profile to the
     * window agent. Restore the previous stored string before failing.
     */
    private fun commitOrRollBack(key: String, value: String) {
        val previous = preferences.getString(key, null)
        if (preferences.edit().putString(key, value).commit()) return
        val rolledBack = preferences.edit().putString(key, previous).commit()
        ControlsLog.warn("window_profiles", "outcome", "commit_failed", "key", key, "rolledBack", rolledBack)
        check(false)
    }

    companion object {
        private val lock = Any()

        @JvmStatic
        fun validateProfiles(data: JSONArray): JSONArray = encode(decode(data))

        @JvmStatic
        fun validateDefaults(data: JSONObject): AppDisplayDefaults {
            val central = data.get("central") as? Int ?: error("Invalid central app density")
            val secondary = when (val value = data.get("secondary")) {
                JSONObject.NULL -> null
                is Int -> value
                else -> error("Invalid secondary app density")
            }
            // Documents written before the swipe existed take its default.
            val fingers = if (data.has("moveFingers")) data.get("moveFingers") as? Int
                ?: error("Invalid window swipe") else AppDisplayDefaults.DEFAULT_MOVE_FINGERS
            val apps = if (data.has("moveShowsApps")) data.get("moveShowsApps") as? Boolean
                ?: error("Invalid window swipe target") else false
            return AppDisplayDefaults(central, secondary, fingers, apps)
        }

        private fun decode(data: JSONArray): List<AppWindowProfile> {
            require(data.length() <= AppWindowProfile.MAX_PROFILES)
            val profiles = (0 until data.length()).map { index ->
                val value = data.getJSONObject(index)
                value.getBoolean("keepPanel") // Validate the legacy field while migrating to a reserved strip.
                AppWindowProfile(
                    requireNotNull(AppWindowProfile.key(value.getString("component"))),
                    value.getInt("width"), value.getInt("dpi"), value.getInt("panelPx"),
                    if (value.has("inheritDpi")) value.get("inheritDpi") as? Boolean
                        ?: error("Invalid app density inheritance") else false,
                )
            }
            require(profiles.distinctBy { it.packageName }.size == profiles.size)
            return profiles
        }

        private fun encode(profiles: List<AppWindowProfile>) = JSONArray(profiles.map { profile ->
            JSONObject().put("component", profile.component).put("width", profile.widthPercent)
                .put("dpi", profile.densityDpi).put("keepPanel", true).put("panelPx", profile.panelWidthPx)
                .put("inheritDpi", profile.inheritDensity)
        })

        /** The densities alone: the window agent reads exactly these two fields. */
        private fun densities(defaults: AppDisplayDefaults) = JSONObject()
            .put("central", defaults.centralDpi).put("secondary", defaults.secondaryDpi ?: JSONObject.NULL)

        /** What is stored and backed up. The swipe is always written, so a swipe turned off stays off. */
        private fun encodeDefaults(defaults: AppDisplayDefaults) = densities(defaults).put("moveFingers", defaults.moveFingers)
            .put("moveShowsApps", defaults.moveShowsApps)
    }
}
