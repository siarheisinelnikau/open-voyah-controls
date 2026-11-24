package org.openvoyah.controls.presentation.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.util.Locale
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.settings.AppAccent
import org.openvoyah.controls.domain.settings.AppAppearance
import org.openvoyah.controls.domain.settings.AppBackgroundTreatment
import org.openvoyah.controls.domain.settings.AppCorners
import org.openvoyah.controls.domain.settings.AppFontFamily
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.domain.settings.AppRoleTextSize
import org.openvoyah.controls.domain.settings.AppSpacing
import org.openvoyah.controls.domain.settings.AppTextSize
import org.openvoyah.controls.domain.settings.LAUNCH_MEDIA_VOLUME_PERCENTS
import org.openvoyah.controls.domain.settings.LauncherEntry
import org.openvoyah.controls.domain.settings.RouteTransitionSpeed
import org.openvoyah.controls.domain.settings.WallpaperDimming
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitOptionDialog
import org.openvoyah.controls.presentation.theme.CockpitReveal
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch

/** The parts of Appearance; Settings shows each in the section it belongs to. */
internal enum class AppearancePart(val title: Int, val icon: CockpitIcon) {
    THEME(R.string.settings_part_theme, CockpitIcon.LIGHT),
    TEXT(R.string.settings_part_text, CockpitIcon.LANGUAGE),
    SHAPE(R.string.settings_part_shape, CockpitIcon.APPS),
    MOTION(R.string.settings_part_motion, CockpitIcon.PLAY),
    WALLPAPER(R.string.settings_wallpaper_heading, CockpitIcon.HOME),
    START(R.string.settings_start_heading, CockpitIcon.HOME),
    LAUNCH_VOLUME(R.string.settings_launch_volume_title, CockpitIcon.PLAY),
}

@Composable
internal fun AppearanceSettings(state: AppearanceUiState, part: AppearancePart, change: (AppearanceChange) -> Unit) {
    val current = state.settings ?: AppAppearance()
    val enabled = state.settings != null && !state.saving
    CockpitSection(UiText.get(part.title), part.icon) {
        when (part) {
            AppearancePart.THEME -> {
                AppearanceChoiceRow(R.string.appearance_palette, current.palette, AppPalette.entries, enabled, { UiText.get(it.label) },
                    preview = { PaletteSample(it, current) }) {
                    change(AppearanceChange.Palette(it))
                }
                AppearanceChoiceRow(R.string.appearance_accent, current.accent, AppAccent.entries, enabled, { UiText.get(it.label) },
                    preview = { AccentSample(it, current) }) {
                    change(AppearanceChange.Accent(it))
                }
                AppearanceChoiceRow(R.string.appearance_background, current.backgroundTreatment, AppBackgroundTreatment.entries,
                    enabled, { UiText.get(it.label) }, preview = { BackgroundSample(it, current) }) {
                    change(AppearanceChange.BackgroundTreatment(it))
                }
                CockpitSwitch(R.string.appearance_high_contrast, current.highContrast, enabled) {
                    change(AppearanceChange.HighContrast(it))
                }
            }
            AppearancePart.TEXT -> {
                AppearanceChoiceRow(R.string.appearance_text_size, current.textSize, AppTextSize.entries, enabled, { UiText.get(it.label) },
                    preview = { TextSizeSample(it, current) }) {
                    change(AppearanceChange.TextSize(it))
                }
                AppearanceChoiceRow(R.string.appearance_navigation_label_size, current.navigationLabelSize,
                    AppRoleTextSize.entries, enabled, { UiText.get(it.label) }, preview = { RoleTextSizeSample(it, current) }) {
                    change(AppearanceChange.NavigationLabelSize(it))
                }
                AppearanceChoiceRow(R.string.appearance_page_title_size, current.pageTitleSize,
                    AppRoleTextSize.entries, enabled, { UiText.get(it.label) }, preview = { RoleTextSizeSample(it, current) }) {
                    change(AppearanceChange.PageTitleSize(it))
                }
                AppearanceChoiceRow(R.string.appearance_font_family, current.fontFamily, AppFontFamily.entries, enabled,
                    { UiText.get(it.label) }, preview = { FontSample(it, current) }) {
                    change(AppearanceChange.FontFamily(it))
                }
                CockpitSwitch(R.string.appearance_bold_text, current.boldText, enabled) {
                    change(AppearanceChange.BoldText(it))
                }
            }
            AppearancePart.SHAPE -> {
                AppearanceChoiceRow(R.string.appearance_spacing, current.spacing, AppSpacing.entries, enabled, { UiText.get(it.label) },
                    preview = { SpacingSample(it) }) {
                    change(AppearanceChange.Spacing(it))
                }
                AppearanceChoiceRow(R.string.appearance_corners, current.corners, AppCorners.entries, enabled, { UiText.get(it.label) },
                    preview = { CornersSample(it) }) {
                    change(AppearanceChange.Corners(it))
                }
            }
            AppearancePart.MOTION -> {
                CockpitSwitch(R.string.appearance_animate_routes, current.animateRoutes, enabled) {
                    change(AppearanceChange.AnimateRoutes(it))
                }
                AppearanceChoiceRow(R.string.appearance_transition_speed, current.transitionSpeed, RouteTransitionSpeed.entries,
                    enabled && current.animateRoutes, { UiText.get(it.label) }, preview = { TransitionSample(it) }) {
                    change(AppearanceChange.TransitionSpeed(it))
                }
            }
            AppearancePart.WALLPAPER -> {
                AppearanceChoiceRow(R.string.appearance_wallpaper_dimming, current.wallpaperDimming, WallpaperDimming.entries,
                    enabled, { UiText.get(it.label) }, description = R.string.appearance_wallpaper_dimming_description,
                    preview = { WallpaperSample(it, current) }) {
                    change(AppearanceChange.WallpaperShade(it))
                }
            }
            AppearancePart.START -> {
                AppearanceChoiceRow(R.string.appearance_start_screen, current.launcherEntry, LauncherEntry.entries, enabled,
                    { UiText.get(it.label) }, description = R.string.appearance_start_screen_description,
                    preview = { StartScreenSample(it) }) {
                    change(AppearanceChange.StartScreen(it))
                }
            }
            AppearancePart.LAUNCH_VOLUME -> {
                CockpitSwitch(R.string.appearance_limit_media_on_launch, current.limitMediaVolumeOnLaunch, enabled) {
                    change(AppearanceChange.LimitMediaVolumeOnLaunch(it))
                }
                CockpitReveal(current.limitMediaVolumeOnLaunch) {
                    val percentFormat = UiText.get(R.string.appearance_media_volume_percent)
                    AppearanceChoiceRow(R.string.appearance_launch_media_limit, current.launchMediaVolumePercent,
                        LAUNCH_MEDIA_VOLUME_PERCENTS, enabled, { percentFormat.format(Locale.ROOT, it) },
                        description = R.string.appearance_launch_media_limit_description, preview = { VolumeSample(it) }) {
                        change(AppearanceChange.LaunchMediaVolumePercent(it))
                    }
                }
            }
        }
        if (state.saveFailed) CockpitFeedback(UiText.get(R.string.appearance_save_failed), error = true)
    }
}

@Composable
private fun <T> AppearanceChoiceRow(
    title: Int,
    current: T,
    choices: List<T>,
    enabled: Boolean,
    label: (T) -> String,
    description: Int? = null,
    preview: (@Composable (T) -> Unit)? = null,
    change: (T) -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    CockpitChoice(title, label(current), enabled) { choosing = true }
    if (!choosing) return
    CockpitOptionDialog(UiText.get(title), choices, current, label, change, { choosing = false },
        description = description?.let { UiText.get(it) }, enabled = enabled, preview = preview)
}

private val AppTextSize.label: Int get() = when (this) {
    AppTextSize.STANDARD -> R.string.appearance_option_standard
    AppTextSize.LARGE -> R.string.appearance_text_large
    AppTextSize.EXTRA_LARGE -> R.string.appearance_text_extra_large
}

private val AppRoleTextSize.label: Int get() = preset?.label ?: R.string.appearance_text_follow

private val RouteTransitionSpeed.label: Int get() = when (this) {
    RouteTransitionSpeed.SHORT -> R.string.appearance_transition_short
    RouteTransitionSpeed.STANDARD -> R.string.appearance_option_standard
    RouteTransitionSpeed.SMOOTH -> R.string.appearance_transition_smooth
}

private val AppSpacing.label: Int get() = when (this) {
    AppSpacing.COMPACT -> R.string.appearance_spacing_compact
    AppSpacing.STANDARD -> R.string.appearance_option_standard
    AppSpacing.SPACIOUS -> R.string.appearance_spacing_spacious
}

private val AppCorners.label: Int get() = when (this) {
    AppCorners.SUBTLE -> R.string.appearance_corners_subtle
    AppCorners.STANDARD -> R.string.appearance_option_standard
    AppCorners.ROUNDED -> R.string.appearance_corners_rounded
}

private val AppPalette.label: Int get() = when (this) {
    AppPalette.DARK -> R.string.appearance_palette_dark
    AppPalette.DAY -> R.string.appearance_palette_day
}

private val AppAccent.label: Int get() = when (this) {
    AppAccent.DEFAULT -> R.string.appearance_accent_default
    AppAccent.TEAL -> R.string.appearance_accent_teal
    AppAccent.AMBER -> R.string.appearance_accent_amber
}

private val AppBackgroundTreatment.label: Int get() = when (this) {
    AppBackgroundTreatment.AMBIENT -> R.string.appearance_background_ambient
    AppBackgroundTreatment.SOLID -> R.string.appearance_background_solid
}

private val AppFontFamily.label: Int get() = when (this) {
    AppFontFamily.SYSTEM -> R.string.appearance_font_system
    AppFontFamily.GOLOS_TEXT -> R.string.appearance_font_golos
}

private val WallpaperDimming.label: Int get() = when (this) {
    WallpaperDimming.LIGHT -> R.string.appearance_wallpaper_dimming_light
    WallpaperDimming.STANDARD -> R.string.appearance_option_standard
    WallpaperDimming.STRONG -> R.string.appearance_wallpaper_dimming_strong
}

private val LauncherEntry.label: Int get() = when (this) {
    LauncherEntry.APP_GRID -> R.string.appearance_app_grid
    LauncherEntry.VEHICLE -> R.string.vehicle_settings_title
    LauncherEntry.SETTINGS -> R.string.cockpit_settings
}
