package org.openvoyah.controls.presentation.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.openvoyah.controls.domain.settings.WallpaperDimming

private val DayBackground = Color(0xFFEEF3F8)
private val DaySurface = Color(0xFFF8FAFD)
private val DayRaised = Color(0xFFE3EAF3)
private val DayInk = Color(0xFF18253A)
private val DayAccent = Color(0xFF20529E)

internal val DayCockpitColors = lightColorScheme(
    primary = DayAccent,
    onPrimary = Color(0xFFFAFCFF),
    primaryContainer = Color(0xFFD8E7FF),
    onPrimaryContainer = Color(0xFF173765),
    secondary = Color(0xFF3D5678),
    onSecondary = Color.White,
    secondaryContainer = DayRaised,
    onSecondaryContainer = DayInk,
    tertiary = Color(0xFF84380D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE7D5),
    onTertiaryContainer = Color(0xFF54260B),
    background = DayBackground,
    onBackground = DayInk,
    surface = DaySurface,
    onSurface = DayInk,
    surfaceVariant = DayRaised,
    onSurfaceVariant = Color(0xFF3F536B),
    surfaceTint = DayAccent,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F6FA),
    surfaceContainer = DaySurface,
    surfaceContainerHigh = DayRaised,
    surfaceContainerHighest = Color(0xFFDBE5F0),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFD7E1EC),
    outline = Color(0xFF60758F),
    outlineVariant = Color(0xFF6D829C),
    error = Color(0xFFA12632),
    onError = Color.White,
    errorContainer = Color(0xFFFDE1DF),
    onErrorContainer = Color(0xFF69161B),
    inverseSurface = Color(0xFF25354D),
    inverseOnSurface = Color(0xFFF4F7FC),
    inversePrimary = Color(0xFFB7D0FF),
)

private val HighContrastDayInk = Color(0xFF081425)
private val HighContrastDayAccent = Color(0xFF12346F)

internal val HighContrastDayCockpitColors = DayCockpitColors.copy(
    primary = HighContrastDayAccent,
    onPrimary = Color.White,
    onPrimaryContainer = Color(0xFF0C2654),
    secondary = Color(0xFF29405F),
    onSecondaryContainer = HighContrastDayInk,
    tertiary = Color(0xFF622605),
    onBackground = HighContrastDayInk,
    onSurface = HighContrastDayInk,
    onSurfaceVariant = Color(0xFF26364B),
    surfaceTint = HighContrastDayAccent,
    outline = Color(0xFF40536D),
    outlineVariant = Color(0xFF536780),
    error = Color(0xFF801A22),
)

/** Keeps foreground controls legible over both dark and bright imported photos. */
internal fun cockpitWallpaperScrim(background: Color, dimming: WallpaperDimming = WallpaperDimming.STANDARD): Color {
    val daytime = background.luminance() > .5f
    val opacity = when (dimming) {
        WallpaperDimming.LIGHT -> if (daytime) .76f else .68f
        WallpaperDimming.STANDARD -> if (daytime) .86f else .73f
        WallpaperDimming.STRONG -> if (daytime) .94f else .86f
    }
    return (if (daytime) background else Color.Black).copy(alpha = opacity)
}

internal val LocalCockpitWallpaperScrim = compositionLocalOf {
    cockpitWallpaperScrim(Color.Black, WallpaperDimming.STANDARD)
}
