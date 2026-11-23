package org.openvoyah.controls.domain.settings

internal interface AppearanceChoice {
    val preferenceId: String
}

internal enum class LauncherEntry(override val preferenceId: String) : AppearanceChoice {
    APP_GRID("apps"),
    VEHICLE("vehicle"),
    SETTINGS("settings"),
}

internal enum class AppTextSize(override val preferenceId: String) : AppearanceChoice {
    STANDARD("standard"),
    LARGE("large"),
    EXTRA_LARGE("extra_large"),
}

internal enum class AppRoleTextSize(val preset: AppTextSize?) : AppearanceChoice {
    FOLLOW(null),
    STANDARD(AppTextSize.STANDARD),
    LARGE(AppTextSize.LARGE),
    EXTRA_LARGE(AppTextSize.EXTRA_LARGE);

    override val preferenceId: String get() = preset?.preferenceId ?: "follow"
}

internal enum class AppSpacing(override val preferenceId: String) : AppearanceChoice {
    COMPACT("compact"),
    STANDARD("standard"),
    SPACIOUS("spacious"),
}

internal enum class AppCorners(override val preferenceId: String) : AppearanceChoice {
    SUBTLE("subtle"),
    STANDARD("standard"),
    ROUNDED("rounded"),
}

internal enum class AppPalette(override val preferenceId: String) : AppearanceChoice {
    DARK("dark"),
    DAY("day"),
}

internal enum class AppAccent(override val preferenceId: String) : AppearanceChoice {
    DEFAULT("default"),
    TEAL("teal"),
    AMBER("amber"),
}

internal enum class AppFontFamily(override val preferenceId: String) : AppearanceChoice {
    SYSTEM("system"),
    GOLOS_TEXT("golosText"),
}

internal enum class AppBackgroundTreatment(override val preferenceId: String) : AppearanceChoice {
    AMBIENT("ambient"),
    SOLID("solid"),
}

internal enum class WallpaperDimming(override val preferenceId: String) : AppearanceChoice {
    LIGHT("light"),
    STANDARD("standard"),
    STRONG("strong"),
}

internal enum class RouteTransitionSpeed(override val preferenceId: String) : AppearanceChoice {
    SHORT("short"),
    STANDARD("standard"),
    SMOOTH("smooth"),
}

internal const val DEFAULT_LAUNCH_MEDIA_VOLUME_PERCENT = 40
internal val LAUNCH_MEDIA_VOLUME_PERCENTS = (20..80 step 10).toList()

internal data class AppAppearance(
    val animateRoutes: Boolean = true,
    val highContrast: Boolean = false,
    val launcherEntry: LauncherEntry = LauncherEntry.APP_GRID,
    val limitMediaVolumeOnLaunch: Boolean = false,
    val launchMediaVolumePercent: Int = DEFAULT_LAUNCH_MEDIA_VOLUME_PERCENT,
    val textSize: AppTextSize = AppTextSize.STANDARD,
    val spacing: AppSpacing = AppSpacing.STANDARD,
    val corners: AppCorners = AppCorners.STANDARD,
    val palette: AppPalette = AppPalette.DARK,
    val transitionSpeed: RouteTransitionSpeed = RouteTransitionSpeed.STANDARD,
    val accent: AppAccent = AppAccent.DEFAULT,
    val wallpaperDimming: WallpaperDimming = WallpaperDimming.STANDARD,
    val fontFamily: AppFontFamily = AppFontFamily.SYSTEM,
    val backgroundTreatment: AppBackgroundTreatment = AppBackgroundTreatment.AMBIENT,
    val boldText: Boolean = false,
    val navigationLabelSize: AppRoleTextSize = AppRoleTextSize.FOLLOW,
    val pageTitleSize: AppRoleTextSize = AppRoleTextSize.FOLLOW,
)
