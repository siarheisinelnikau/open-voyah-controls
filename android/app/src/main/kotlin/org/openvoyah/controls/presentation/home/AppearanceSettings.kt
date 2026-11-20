package org.openvoyah.controls.presentation.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch

@Composable
internal fun AppearanceSettings(state: AppearanceUiState, change: (AppearanceChange) -> Unit) {
    val current = state.settings ?: AppAppearance()
    val enabled = state.settings != null && !state.saving
    CockpitSection(UiText.get(R.string.appearance_title), CockpitIcon.DISPLAY) {
        CockpitSwitch(UiText.get(R.string.appearance_animate_routes), current.animateRoutes, enabled) {
            change(AppearanceChange.AnimateRoutes(it))
        }
        AppearanceChoiceRow(R.string.appearance_transition_speed, current.transitionSpeed, RouteTransitionSpeed.entries,
            enabled && current.animateRoutes, { UiText.get(it.label) }) {
            change(AppearanceChange.TransitionSpeed(it))
        }
        CockpitSwitch(UiText.get(R.string.appearance_high_contrast), current.highContrast, enabled) {
            change(AppearanceChange.HighContrast(it))
        }
        AppearanceChoiceRow(R.string.appearance_palette, current.palette, AppPalette.entries, enabled, { UiText.get(it.label) }) {
            change(AppearanceChange.Palette(it))
        }
        AppearanceChoiceRow(R.string.appearance_accent, current.accent, AppAccent.entries, enabled, { UiText.get(it.label) }) {
            change(AppearanceChange.Accent(it))
        }
        AppearanceChoiceRow(R.string.appearance_background, current.backgroundTreatment, AppBackgroundTreatment.entries,
            enabled, { UiText.get(it.label) }) {
            change(AppearanceChange.BackgroundTreatment(it))
        }
        AppearanceChoiceRow(R.string.appearance_wallpaper_dimming, current.wallpaperDimming, WallpaperDimming.entries,
            enabled, { UiText.get(it.label) }, description = R.string.appearance_wallpaper_dimming_description) {
            change(AppearanceChange.WallpaperShade(it))
        }
        AppearanceChoiceRow(R.string.appearance_text_size, current.textSize, AppTextSize.entries, enabled, { UiText.get(it.label) }) {
            change(AppearanceChange.TextSize(it))
        }
        AppearanceChoiceRow(R.string.appearance_navigation_label_size, current.navigationLabelSize,
            AppRoleTextSize.entries, enabled, { UiText.get(it.label) }) {
            change(AppearanceChange.NavigationLabelSize(it))
        }
        AppearanceChoiceRow(R.string.appearance_page_title_size, current.pageTitleSize,
            AppRoleTextSize.entries, enabled, { UiText.get(it.label) }) {
            change(AppearanceChange.PageTitleSize(it))
        }
        AppearanceChoiceRow(R.string.appearance_font_family, current.fontFamily, AppFontFamily.entries, enabled, { UiText.get(it.label) }) {
            change(AppearanceChange.FontFamily(it))
        }
        CockpitSwitch(UiText.get(R.string.appearance_bold_text), current.boldText, enabled) {
            change(AppearanceChange.BoldText(it))
        }
        AppearanceChoiceRow(R.string.appearance_spacing, current.spacing, AppSpacing.entries, enabled, { UiText.get(it.label) }) {
            change(AppearanceChange.Spacing(it))
        }
        AppearanceChoiceRow(R.string.appearance_corners, current.corners, AppCorners.entries, enabled, { UiText.get(it.label) }) {
            change(AppearanceChange.Corners(it))
        }
        AppearanceChoiceRow(R.string.appearance_start_screen, current.launcherEntry, LauncherEntry.entries, enabled,
            { UiText.get(it.label) }, description = R.string.appearance_start_screen_description) {
            change(AppearanceChange.StartScreen(it))
        }
        CockpitSwitch(UiText.get(R.string.appearance_limit_media_on_launch), current.limitMediaVolumeOnLaunch, enabled) {
            change(AppearanceChange.LimitMediaVolumeOnLaunch(it))
        }
        if (current.limitMediaVolumeOnLaunch) {
            val percentFormat = UiText.get(R.string.appearance_media_volume_percent)
            AppearanceChoiceRow(R.string.appearance_launch_media_limit, current.launchMediaVolumePercent,
                LAUNCH_MEDIA_VOLUME_PERCENTS, enabled, { percentFormat.format(Locale.ROOT, it) },
                description = R.string.appearance_launch_media_limit_description) {
                change(AppearanceChange.LaunchMediaVolumePercent(it))
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
    change: (T) -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    CockpitChoice(UiText.get(title), label(current), enabled) { choosing = true }
    if (!choosing) return

    CockpitAlertDialog(
        onDismissRequest = { choosing = false },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text(UiText.get(R.string.ui_89)) } },
        title = { Text(UiText.get(title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                description?.let { Text(UiText.get(it)) }
                choices.forEach { value ->
                    val selected = value == current
                    var focused by remember { mutableStateOf(false) }
                    val focusRing = if (enabled && focused) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                    } else Modifier
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .onFocusChanged { focused = it.isFocused }
                            .then(focusRing)
                            .selectable(selected, enabled = enabled, role = Role.RadioButton) {
                                choosing = false
                                if (!selected) change(value)
                            }
                            .padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, enabled = enabled, onClick = null)
                        Text(label(value))
                    }
                }
            }
        },
    )
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
