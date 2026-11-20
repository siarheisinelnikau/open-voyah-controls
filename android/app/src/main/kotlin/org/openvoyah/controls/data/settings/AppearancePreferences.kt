package org.openvoyah.controls.data.settings

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
import org.json.JSONObject
import org.openvoyah.controls.domain.settings.AppearanceChoice
import org.openvoyah.controls.domain.settings.AppAccent
import org.openvoyah.controls.domain.settings.AppAppearance
import org.openvoyah.controls.domain.settings.AppBackgroundTreatment
import org.openvoyah.controls.domain.settings.AppCorners
import org.openvoyah.controls.domain.settings.AppFontFamily
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.domain.settings.AppRoleTextSize
import org.openvoyah.controls.domain.settings.AppSpacing
import org.openvoyah.controls.domain.settings.AppTextSize
import org.openvoyah.controls.domain.settings.DEFAULT_LAUNCH_MEDIA_VOLUME_PERCENT
import org.openvoyah.controls.domain.settings.LAUNCH_MEDIA_VOLUME_PERCENTS
import org.openvoyah.controls.domain.settings.LauncherEntry
import org.openvoyah.controls.domain.settings.RouteTransitionSpeed
import org.openvoyah.controls.domain.settings.WallpaperDimming

internal class AppearancePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun observeSettings(): Flow<AppAppearance> = callbackFlow<Unit> {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in preferenceKeys) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { load() }.distinctUntilChanged().flowOn(Dispatchers.IO)

    fun load(): AppAppearance = synchronized(lock) {
        val values = preferences.all
        AppAppearance(
            animateRoutes = values[ANIMATE_ROUTES] as? Boolean ?: true,
            highContrast = values[HIGH_CONTRAST] as? Boolean ?: false,
            launcherEntry = choice(values[LAUNCHER_ENTRY], LauncherEntry.entries) ?: LauncherEntry.APP_GRID,
            limitMediaVolumeOnLaunch = values[LIMIT_MEDIA_VOLUME_ON_LAUNCH] as? Boolean ?: false,
            launchMediaVolumePercent = mediaVolumePercent(values[LAUNCH_MEDIA_VOLUME_PERCENT])
                ?: DEFAULT_LAUNCH_MEDIA_VOLUME_PERCENT,
            textSize = choice(values[TEXT_SIZE], AppTextSize.entries) ?: AppTextSize.STANDARD,
            spacing = choice(values[SPACING], AppSpacing.entries) ?: AppSpacing.STANDARD,
            corners = choice(values[CORNERS], AppCorners.entries) ?: AppCorners.STANDARD,
            palette = choice(values[PALETTE], AppPalette.entries) ?: AppPalette.DARK,
            transitionSpeed = choice(values[TRANSITION_SPEED], RouteTransitionSpeed.entries) ?: RouteTransitionSpeed.STANDARD,
            accent = choice(values[ACCENT], AppAccent.entries) ?: AppAccent.DEFAULT,
            wallpaperDimming = choice(values[WALLPAPER_DIMMING], WallpaperDimming.entries) ?: WallpaperDimming.STANDARD,
            fontFamily = choice(values[FONT_FAMILY], AppFontFamily.entries) ?: AppFontFamily.SYSTEM,
            backgroundTreatment = choice(values[BACKGROUND_TREATMENT], AppBackgroundTreatment.entries)
                ?: AppBackgroundTreatment.AMBIENT,
            boldText = values[BOLD_TEXT] as? Boolean ?: false,
            navigationLabelSize = choice(values[NAVIGATION_LABEL_SIZE], AppRoleTextSize.entries) ?: AppRoleTextSize.FOLLOW,
            pageTitleSize = choice(values[PAGE_TITLE_SIZE], AppRoleTextSize.entries) ?: AppRoleTextSize.FOLLOW,
        )
    }

    fun setAnimateRoutes(enabled: Boolean) = update(Patch(animateRoutes = enabled))
    fun setHighContrast(enabled: Boolean) = update(Patch(highContrast = enabled))
    fun setLauncherEntry(entry: LauncherEntry) = update(Patch(launcherEntry = entry))
    fun setLimitMediaVolumeOnLaunch(enabled: Boolean) = update(Patch(limitMediaVolumeOnLaunch = enabled))
    fun setLaunchMediaVolumePercent(percent: Int) {
        require(percent in LAUNCH_MEDIA_VOLUME_PERCENTS)
        update(Patch(launchMediaVolumePercent = percent))
    }
    fun setTextSize(size: AppTextSize) = update(Patch(textSize = size))
    fun setSpacing(spacing: AppSpacing) = update(Patch(spacing = spacing))
    fun setCorners(corners: AppCorners) = update(Patch(corners = corners))
    fun setPalette(palette: AppPalette) = update(Patch(palette = palette))
    fun setTransitionSpeed(speed: RouteTransitionSpeed) = update(Patch(transitionSpeed = speed))
    fun setAccent(accent: AppAccent) = update(Patch(accent = accent))
    fun setWallpaperDimming(dimming: WallpaperDimming) = update(Patch(wallpaperDimming = dimming))
    fun setFontFamily(family: AppFontFamily) = update(Patch(fontFamily = family))
    fun setBackgroundTreatment(treatment: AppBackgroundTreatment) = update(Patch(backgroundTreatment = treatment))
    fun setBoldText(enabled: Boolean) = update(Patch(boldText = enabled))
    fun setNavigationLabelSize(size: AppRoleTextSize) = update(Patch(navigationLabelSize = size))
    fun setPageTitleSize(size: AppRoleTextSize) = update(Patch(pageTitleSize = size))

    fun exportSettings(): JSONObject = load().let { settings ->
        Patch(
            animateRoutes = settings.animateRoutes,
            highContrast = settings.highContrast,
            launcherEntry = settings.launcherEntry,
            limitMediaVolumeOnLaunch = settings.limitMediaVolumeOnLaunch,
            launchMediaVolumePercent = settings.launchMediaVolumePercent,
            textSize = settings.textSize,
            spacing = settings.spacing,
            corners = settings.corners,
            palette = settings.palette,
            transitionSpeed = settings.transitionSpeed,
            accent = settings.accent,
            wallpaperDimming = settings.wallpaperDimming,
            fontFamily = settings.fontFamily,
            backgroundTreatment = settings.backgroundTreatment,
            boldText = settings.boldText,
            navigationLabelSize = settings.navigationLabelSize,
            pageTitleSize = settings.pageTitleSize,
        ).encode()
    }

    fun restoreSettings(data: JSONObject) = update(decode(data))

    private fun update(patch: Patch) = synchronized(lock) {
        val before = preferences.all
        val changes = patch.values().filter { (key, value) -> before[key] != value }
        if (changes.isEmpty()) return@synchronized
        val editor = preferences.edit()
        changes.forEach { (key, value) -> editor.write(key, value) }
        if (!editor.commit()) {
            val rollback = preferences.edit()
            changes.keys.forEach { key -> rollback.write(key, before[key]) }
            rollback.commit()
            error("Appearance preferences could not be written")
        }
    }

    private fun SharedPreferences.Editor.write(key: String, value: Any?) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is String -> putString(key, value)
            else -> remove(key)
        }
    }

    private data class Patch(
        val animateRoutes: Boolean? = null,
        val highContrast: Boolean? = null,
        val launcherEntry: LauncherEntry? = null,
        val limitMediaVolumeOnLaunch: Boolean? = null,
        val launchMediaVolumePercent: Int? = null,
        val textSize: AppTextSize? = null,
        val spacing: AppSpacing? = null,
        val corners: AppCorners? = null,
        val palette: AppPalette? = null,
        val transitionSpeed: RouteTransitionSpeed? = null,
        val accent: AppAccent? = null,
        val wallpaperDimming: WallpaperDimming? = null,
        val fontFamily: AppFontFamily? = null,
        val backgroundTreatment: AppBackgroundTreatment? = null,
        val boldText: Boolean? = null,
        val navigationLabelSize: AppRoleTextSize? = null,
        val pageTitleSize: AppRoleTextSize? = null,
    ) {
        fun values(): Map<String, Any> = buildMap {
            animateRoutes?.let { put(ANIMATE_ROUTES, it) }
            highContrast?.let { put(HIGH_CONTRAST, it) }
            launcherEntry?.let { put(LAUNCHER_ENTRY, it.preferenceId) }
            limitMediaVolumeOnLaunch?.let { put(LIMIT_MEDIA_VOLUME_ON_LAUNCH, it) }
            launchMediaVolumePercent?.let { put(LAUNCH_MEDIA_VOLUME_PERCENT, it) }
            textSize?.let { put(TEXT_SIZE, it.preferenceId) }
            spacing?.let { put(SPACING, it.preferenceId) }
            corners?.let { put(CORNERS, it.preferenceId) }
            palette?.let { put(PALETTE, it.preferenceId) }
            transitionSpeed?.let { put(TRANSITION_SPEED, it.preferenceId) }
            accent?.let { put(ACCENT, it.preferenceId) }
            wallpaperDimming?.let { put(WALLPAPER_DIMMING, it.preferenceId) }
            fontFamily?.let { put(FONT_FAMILY, it.preferenceId) }
            backgroundTreatment?.let { put(BACKGROUND_TREATMENT, it.preferenceId) }
            boldText?.let { put(BOLD_TEXT, it) }
            navigationLabelSize?.let { put(NAVIGATION_LABEL_SIZE, it.preferenceId) }
            pageTitleSize?.let { put(PAGE_TITLE_SIZE, it.preferenceId) }
        }

        fun encode(): JSONObject = JSONObject().apply {
            animateRoutes?.let { put(ARCHIVE_ANIMATE, it) }
            highContrast?.let { put(ARCHIVE_CONTRAST, it) }
            launcherEntry?.let { put(ARCHIVE_ENTRY, it.preferenceId) }
            limitMediaVolumeOnLaunch?.let { put(ARCHIVE_LIMIT_MEDIA_VOLUME_ON_LAUNCH, it) }
            launchMediaVolumePercent?.let { put(ARCHIVE_LAUNCH_MEDIA_VOLUME_PERCENT, it) }
            textSize?.let { put(ARCHIVE_TEXT_SIZE, it.preferenceId) }
            spacing?.let { put(ARCHIVE_SPACING, it.preferenceId) }
            corners?.let { put(ARCHIVE_CORNERS, it.preferenceId) }
            palette?.let { put(ARCHIVE_PALETTE, it.preferenceId) }
            transitionSpeed?.let { put(ARCHIVE_TRANSITION_SPEED, it.preferenceId) }
            accent?.let { put(ARCHIVE_ACCENT, it.preferenceId) }
            wallpaperDimming?.let { put(ARCHIVE_WALLPAPER_DIMMING, it.preferenceId) }
            fontFamily?.let { put(ARCHIVE_FONT_FAMILY, it.preferenceId) }
            backgroundTreatment?.let { put(ARCHIVE_BACKGROUND_TREATMENT, it.preferenceId) }
            boldText?.let { put(ARCHIVE_BOLD_TEXT, it) }
            navigationLabelSize?.let { put(ARCHIVE_NAVIGATION_LABEL_SIZE, it.preferenceId) }
            pageTitleSize?.let { put(ARCHIVE_PAGE_TITLE_SIZE, it.preferenceId) }
        }
    }

    companion object {
        const val STORE = "appearance"
        private const val ANIMATE_ROUTES = "animate_routes"
        private const val HIGH_CONTRAST = "high_contrast"
        private const val LAUNCHER_ENTRY = "launcher_entry"
        private const val LIMIT_MEDIA_VOLUME_ON_LAUNCH = "limit_media_volume_on_launch"
        private const val LAUNCH_MEDIA_VOLUME_PERCENT = "launch_media_volume_percent"
        private const val TEXT_SIZE = "text_size"
        private const val SPACING = "spacing"
        private const val CORNERS = "corners"
        private const val PALETTE = "palette"
        private const val TRANSITION_SPEED = "transition_speed"
        private const val ACCENT = "accent"
        private const val WALLPAPER_DIMMING = "wallpaper_dimming"
        private const val FONT_FAMILY = "font_family"
        private const val BACKGROUND_TREATMENT = "background_treatment"
        private const val BOLD_TEXT = "bold_text"
        private const val NAVIGATION_LABEL_SIZE = "navigation_label_size"
        private const val PAGE_TITLE_SIZE = "page_title_size"
        private const val ARCHIVE_ANIMATE = "animateRoutes"
        private const val ARCHIVE_CONTRAST = "highContrast"
        private const val ARCHIVE_ENTRY = "launcherEntry"
        private const val ARCHIVE_LIMIT_MEDIA_VOLUME_ON_LAUNCH = "limitMediaVolumeOnLaunch"
        private const val ARCHIVE_LAUNCH_MEDIA_VOLUME_PERCENT = "launchMediaVolumePercent"
        private const val ARCHIVE_TEXT_SIZE = "textSize"
        private const val ARCHIVE_SPACING = "spacing"
        private const val ARCHIVE_CORNERS = "corners"
        private const val ARCHIVE_PALETTE = "palette"
        private const val ARCHIVE_TRANSITION_SPEED = "transitionSpeed"
        private const val ARCHIVE_ACCENT = "accent"
        private const val ARCHIVE_WALLPAPER_DIMMING = "wallpaperDimming"
        private const val ARCHIVE_FONT_FAMILY = "fontFamily"
        private const val ARCHIVE_BACKGROUND_TREATMENT = "backgroundTreatment"
        private const val ARCHIVE_BOLD_TEXT = "boldText"
        private const val ARCHIVE_NAVIGATION_LABEL_SIZE = "navigationLabelSize"
        private const val ARCHIVE_PAGE_TITLE_SIZE = "pageTitleSize"
        private val preferenceKeys = setOf(ANIMATE_ROUTES, HIGH_CONTRAST, LAUNCHER_ENTRY, TEXT_SIZE, SPACING, CORNERS,
            PALETTE, TRANSITION_SPEED, ACCENT, WALLPAPER_DIMMING, FONT_FAMILY, BACKGROUND_TREATMENT, BOLD_TEXT,
            NAVIGATION_LABEL_SIZE, PAGE_TITLE_SIZE, LIMIT_MEDIA_VOLUME_ON_LAUNCH, LAUNCH_MEDIA_VOLUME_PERCENT)
        private val archiveKeys = setOf(ARCHIVE_ANIMATE, ARCHIVE_CONTRAST, ARCHIVE_ENTRY, ARCHIVE_TEXT_SIZE,
            ARCHIVE_SPACING, ARCHIVE_CORNERS, ARCHIVE_PALETTE, ARCHIVE_TRANSITION_SPEED, ARCHIVE_ACCENT,
            ARCHIVE_WALLPAPER_DIMMING, ARCHIVE_FONT_FAMILY, ARCHIVE_BACKGROUND_TREATMENT, ARCHIVE_BOLD_TEXT,
            ARCHIVE_NAVIGATION_LABEL_SIZE, ARCHIVE_PAGE_TITLE_SIZE, ARCHIVE_LIMIT_MEDIA_VOLUME_ON_LAUNCH,
            ARCHIVE_LAUNCH_MEDIA_VOLUME_PERCENT)
        private val lock = Any()

        @JvmStatic
        fun validateSettings(data: JSONObject): JSONObject = decode(data).encode()

        private fun decode(data: JSONObject): Patch {
            require(data.keys().asSequence().all { it in archiveKeys })
            return Patch(
                animateRoutes = data.optionalBoolean(ARCHIVE_ANIMATE),
                highContrast = data.optionalBoolean(ARCHIVE_CONTRAST),
                launcherEntry = data.optionalChoice(ARCHIVE_ENTRY, LauncherEntry.entries),
                limitMediaVolumeOnLaunch = data.optionalBoolean(ARCHIVE_LIMIT_MEDIA_VOLUME_ON_LAUNCH),
                launchMediaVolumePercent = if (data.has(ARCHIVE_LAUNCH_MEDIA_VOLUME_PERCENT)) {
                    requireNotNull(mediaVolumePercent(data.get(ARCHIVE_LAUNCH_MEDIA_VOLUME_PERCENT)))
                } else null,
                textSize = data.optionalChoice(ARCHIVE_TEXT_SIZE, AppTextSize.entries),
                spacing = data.optionalChoice(ARCHIVE_SPACING, AppSpacing.entries),
                corners = data.optionalChoice(ARCHIVE_CORNERS, AppCorners.entries),
                palette = data.optionalChoice(ARCHIVE_PALETTE, AppPalette.entries),
                transitionSpeed = data.optionalChoice(ARCHIVE_TRANSITION_SPEED, RouteTransitionSpeed.entries),
                accent = data.optionalChoice(ARCHIVE_ACCENT, AppAccent.entries),
                wallpaperDimming = data.optionalChoice(ARCHIVE_WALLPAPER_DIMMING, WallpaperDimming.entries),
                fontFamily = data.optionalChoice(ARCHIVE_FONT_FAMILY, AppFontFamily.entries),
                backgroundTreatment = data.optionalChoice(ARCHIVE_BACKGROUND_TREATMENT, AppBackgroundTreatment.entries),
                boldText = data.optionalBoolean(ARCHIVE_BOLD_TEXT),
                navigationLabelSize = data.optionalChoice(ARCHIVE_NAVIGATION_LABEL_SIZE, AppRoleTextSize.entries),
                pageTitleSize = data.optionalChoice(ARCHIVE_PAGE_TITLE_SIZE, AppRoleTextSize.entries),
            )
        }

        private fun JSONObject.optionalBoolean(key: String): Boolean? = if (has(key)) {
            requireNotNull(get(key) as? Boolean)
        } else null

        private fun <T : AppearanceChoice> JSONObject.optionalChoice(key: String, choices: List<T>): T? =
            if (has(key)) requireNotNull(choice(get(key), choices)) else null

        private fun <T : AppearanceChoice> choice(value: Any?, choices: List<T>): T? =
            choices.firstOrNull { it.preferenceId == value }

        private fun mediaVolumePercent(value: Any?): Int? = (value as? Int)?.takeIf { it in LAUNCH_MEDIA_VOLUME_PERCENTS }
    }
}
