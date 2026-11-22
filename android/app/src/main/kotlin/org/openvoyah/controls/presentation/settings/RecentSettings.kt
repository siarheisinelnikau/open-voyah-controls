package org.openvoyah.controls.presentation.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.io.File
import org.openvoyah.controls.UiText
import org.openvoyah.controls.presentation.navigation.ControlsRoute

/** A Settings page, and the part of a section page, where something was changed. */
internal data class RecentChange(val route: ControlsRoute, val part: SettingsPartId?) {
    fun label(text: (Int) -> String = UiText::get): String {
        val title = part?.title ?: routeTitle(route)
        val section = sectionOf(route)
        return listOfNotNull(section?.title?.takeIf { it != title }?.let(text), title?.let(text)).joinToString(" · ")
    }

    fun encode(): String = route.name + (part?.let { "|" + it.name } ?: "")

    companion object {
        fun decode(value: String): RecentChange? {
            val (route, part) = value.split('|').let { it[0] to it.getOrNull(1) }
            val known = ControlsRoute.entries.firstOrNull { it.name == route } ?: return null
            if (sectionOf(known) == null) return null
            return RecentChange(known, SettingsPartId.of(part))
        }
    }
}

/**
 * The last places in Settings where the owner changed something. It is a local history of this head unit, like the
 * current desktop tab: it is not a setting, takes no part in a settings export and lists at most [LIMIT] places.
 */
internal class RecentSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun read(): List<RecentChange> = prefs.getString(KEY, "").orEmpty().split(';').filter { it.isNotBlank() }
        .mapNotNull(RecentChange::decode).distinct().take(LIMIT)

    fun record(change: RecentChange) {
        val list = listOf(change) + read().filterNot { it == change || (it.route == change.route && it.part == null) }
        prefs.edit().putString(KEY, list.take(LIMIT).joinToString(";") { it.encode() }).apply()
    }

    internal companion object {
        const val STORE = "settings_recent"
        private const val KEY = "places"
        const val LIMIT = 4
        /** Preference files written by installs, jobs and histories rather than by a setting the owner changes. */
        val IGNORED = setOf(STORE, "apk_install_session", "self_update_install", "verified_apk_installs",
            "routine-startup-history", "text_overlay_job", "updates")
    }
}

/**
 * Watches the app's preference files while Controls is in the foreground and records the Settings page on screen when
 * one of them changes. Writes in the first moments after a page opens are its own loading, not the owner's change.
 */
internal class RecentSettingsWatcher(
    private val context: Context,
    private val visible: () -> RecentChange?,
) {
    private val recent = RecentSettings(context)
    private val watched = mutableListOf<SharedPreferences>()
    private var arrived = 0L
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val place = visible() ?: return@OnSharedPreferenceChangeListener
        if (SystemClock.uptimeMillis() - arrived >= SETTLE_MILLIS) recent.record(place)
    }

    /** A page opened; files created since the last page (a store written for the first time) are watched too. */
    fun arrived() {
        arrived = SystemClock.uptimeMillis()
        stop()
        start()
    }

    fun start() {
        val names = File(context.applicationInfo.dataDir, "shared_prefs").list().orEmpty()
            .filter { it.endsWith(".xml") }.map { it.removeSuffix(".xml") }.filterNot { it in RecentSettings.IGNORED }
        names.forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(listener)
            watched += prefs
        }
    }

    fun stop() {
        watched.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
        watched.clear()
    }

    private companion object {
        const val SETTLE_MILLIS = 1_500L
    }
}

/** The part of a section page that is on screen, so a change on it is recorded with its part. */
internal object VisibleSettingsPart {
    @Volatile var route: ControlsRoute? = null
    @Volatile var part: SettingsPartId? = null

    fun show(route: ControlsRoute, key: String) {
        this.route = route
        part = SettingsPartId.of(key)
    }

    fun at(route: ControlsRoute): RecentChange? {
        sectionOf(route) ?: return null
        return RecentChange(route, part.takeIf { this.route == route })
    }
}
