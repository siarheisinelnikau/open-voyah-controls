package org.openvoyah.controls.presentation.theme

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Window
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openvoyah.controls.R
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.domain.settings.AppAccent
import org.openvoyah.controls.domain.settings.AppAppearance
import org.openvoyah.controls.domain.settings.AppCorners
import org.openvoyah.controls.domain.settings.AppFontFamily
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.domain.settings.AppTextSize

private val CockpitTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    // Field labels, card captions and supporting lines use these roles. The Material baseline of 12 sp is too
    // small to read at arm's length, so the cockpit scale stops at 14 sp for running text.
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.25.sp, fontWeight = FontWeight.Medium),
)

private val StandardCockpitShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val SubtleCockpitShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

private val RoundedCockpitShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

internal fun cockpitShapes(style: AppCorners): Shapes = when (style) {
    AppCorners.SUBTLE -> SubtleCockpitShapes
    AppCorners.STANDARD -> StandardCockpitShapes
    AppCorners.ROUNDED -> RoundedCockpitShapes
}

internal fun cockpitTypography(
    size: AppTextSize,
    family: AppFontFamily = AppFontFamily.SYSTEM,
    bold: Boolean = false,
): Typography {
    if (size == AppTextSize.STANDARD && family == AppFontFamily.SYSTEM && !bold) return CockpitTypography
    val factor = size.multiplier
    val alternate = cockpitFontFamily(family)
    return with(CockpitTypography) {
        copy(
            displayLarge = displayLarge.styled(factor, alternate, bold),
            displayMedium = displayMedium.styled(factor, alternate, bold),
            displaySmall = displaySmall.styled(factor, alternate, bold),
            headlineLarge = headlineLarge.styled(factor, alternate, bold),
            headlineMedium = headlineMedium.styled(factor, alternate, bold),
            headlineSmall = headlineSmall.styled(factor, alternate, bold),
            titleLarge = titleLarge.styled(factor, alternate, bold),
            titleMedium = titleMedium.styled(factor, alternate, bold),
            titleSmall = titleSmall.styled(factor, alternate, bold),
            bodyLarge = bodyLarge.styled(factor, alternate, bold),
            bodyMedium = bodyMedium.styled(factor, alternate, bold),
            bodySmall = bodySmall.styled(factor, alternate, bold),
            labelLarge = labelLarge.styled(factor, alternate, bold),
            labelMedium = labelMedium.styled(factor, alternate, bold),
            labelSmall = labelSmall.styled(factor, alternate, bold),
        )
    }
}

private fun TextStyle.styled(factor: Float, alternate: FontFamily?, bold: Boolean): TextStyle = copy(
    fontSize = fontSize.scaledSp(factor),
    lineHeight = lineHeight.scaledSp(factor),
    letterSpacing = letterSpacing.scaledSp(factor),
    fontFamily = alternate ?: fontFamily,
    fontWeight = if (bold) maxOf(fontWeight ?: FontWeight.Normal, FontWeight.Bold) else fontWeight,
)

private fun TextUnit.scaledSp(factor: Float): TextUnit = if (isSp) this * factor else this

internal fun TextStyle.scaledText(factor: Float): TextStyle =
    if (factor == 1f) this else styled(factor, alternate = null, bold = false)

private val ContrastBackground = Color(0xFF070910)
private val ContrastSurface = Color(0xFF101622)
private val ContrastRaised = Color(0xFF1D293D)
private val ContrastInk = Color(0xFFF8FAFF)
private val ContrastAccent = Color(0xFFA5C1FF)

internal val HighContrastCockpitColors = darkColorScheme(
    primary = ContrastAccent,
    onPrimary = Color(0xFF0B182D),
    primaryContainer = Color(0xFF22365E),
    onPrimaryContainer = Color(0xFFF1F5FF),
    secondary = Color(0xFFD1DEFA),
    onSecondary = Color(0xFF152238),
    secondaryContainer = ContrastRaised,
    onSecondaryContainer = ContrastInk,
    tertiary = Color(0xFFFFCF9E),
    onTertiary = Color(0xFF351D10),
    tertiaryContainer = Color(0xFF4C2B1B),
    onTertiaryContainer = Color(0xFFFFECDA),
    background = ContrastBackground,
    onBackground = ContrastInk,
    surface = ContrastSurface,
    onSurface = ContrastInk,
    surfaceVariant = ContrastRaised,
    onSurfaceVariant = Color(0xFFDBE5F5),
    surfaceContainer = ContrastSurface,
    surfaceContainerLowest = ContrastBackground,
    surfaceContainerLow = Color(0xFF0C111B),
    surfaceContainerHigh = ContrastRaised,
    surfaceContainerHighest = Color(0xFF2B3B54),
    surfaceTint = ContrastAccent,
    surfaceBright = Color(0xFF354864),
    surfaceDim = ContrastBackground,
    outline = Color(0xFFABBAD0),
    outlineVariant = Color(0xFF7C8EA9),
    error = Color(0xFFFFB2AB),
    onError = Color(0xFF400C0D),
    errorContainer = Color(0xFF552426),
    onErrorContainer = Color(0xFFFFECE8),
    inverseSurface = Color(0xFFE5EDF9),
    inverseOnSurface = Color(0xFF122033),
    inversePrimary = Color(0xFF284A83),
)

@Composable
internal fun ControlsTheme(
    appearance: AppAppearance? = null,
    systemBars: Window? = null,
    temporaryPalette: AppPalette? = null,
    content: @Composable () -> Unit,
) {
    val settings = appearance ?: persistedAppearance()
    val layout = remember(settings.spacing, settings.textSize, settings.navigationLabelSize, settings.pageTitleSize) {
        cockpitLayout(settings.spacing, settings.textSize, settings.navigationLabelSize, settings.pageTitleSize)
    }
    val typography = remember(settings.textSize, settings.fontFamily, settings.boldText) {
        cockpitTypography(settings.textSize, settings.fontFamily, settings.boldText)
    }
    // Dialogs and the voice popup are separate composition roots; they follow a routine's session palette too.
    val sessionPalette by SessionPalette.selected.collectAsStateWithLifecycle()
    val activePalette = temporaryPalette ?: sessionPalette ?: settings.palette
    val daytime = activePalette == AppPalette.DAY
    val baseColors = cockpitBaseColors(activePalette, settings.highContrast)
    val colors = remember(baseColors, settings.accent, activePalette, settings.highContrast) {
        cockpitAccentColors(baseColors, settings.accent, activePalette, settings.highContrast)
    }
    val wallpaperScrim = remember(baseColors.background, settings.wallpaperDimming) {
        cockpitWallpaperScrim(baseColors.background, settings.wallpaperDimming)
    }
    if (systemBars != null) ApplyCockpitSystemBars(systemBars, colors.background, daytime)
    CompositionLocalProvider(
        LocalCockpitLayout provides layout,
        LocalCockpitWallpaperScrim provides wallpaperScrim,
        LocalCockpitBackgroundTreatment provides settings.backgroundTreatment,
        LocalCockpitMotion provides CockpitMotionSettings(settings.animateRoutes, settings.transitionSpeed),
        LocalControlsAccentOverlay provides controlsAccentOverlay(settings.accent, activePalette),
    ) {
        MaterialTheme(
            colorScheme = rememberBlendedColors(colors, settings.animateRoutes),
            shapes = cockpitShapes(settings.corners),
            typography = typography,
        ) { LauncherPanelPadding(content) }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ApplyCockpitSystemBars(window: Window, background: Color, darkIcons: Boolean) {
    val barColor = background.toArgb()
    LaunchedEffect(window, barColor, darkIcons) {
        window.setBackgroundDrawable(ColorDrawable(barColor))
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = barColor
            window.navigationBarColor = barColor
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
}

@Composable
private fun persistedAppearance(): AppAppearance {
    val app = LocalContext.current.applicationContext
    val preferences = remember(app) { AppearancePreferences(app) }
    val initial = remember(preferences) { preferences.load() }
    val settings = remember(preferences) { preferences.observeSettings() }
    return settings.collectAsStateWithLifecycle(initialValue = initial).value
}

@Composable
private fun cockpitBaseColors(palette: AppPalette, highContrast: Boolean): ColorScheme = when {
    palette == AppPalette.DAY && highContrast -> HighContrastDayCockpitColors
    palette == AppPalette.DAY -> DayCockpitColors
    highContrast -> HighContrastCockpitColors
    else -> standardCockpitColors()
}

/** The colours [ControlsTheme] gives these choices, so a choice can be previewed before it is made. */
@Composable
internal fun cockpitColorsFor(accent: AppAccent, palette: AppPalette, highContrast: Boolean): ColorScheme {
    val base = cockpitBaseColors(palette, highContrast)
    return remember(base, accent, palette, highContrast) { cockpitAccentColors(base, accent, palette, highContrast) }
}

@Composable
private fun standardCockpitColors(): ColorScheme {
    val accent = colorResource(R.color.cockpit_accent)
    val ink = colorResource(R.color.cockpit_text)
    val muted = colorResource(R.color.cockpit_muted)
    val surface = colorResource(R.color.cockpit_surface)
    val raised = colorResource(R.color.cockpit_surface_raised)
    return darkColorScheme(
        primary = accent,
        onPrimary = colorResource(R.color.cockpit_on_accent),
        primaryContainer = colorResource(R.color.cockpit_accent_container),
        onPrimaryContainer = colorResource(R.color.cockpit_on_accent_container),
        secondary = colorResource(R.color.cockpit_secondary),
        onSecondary = Color(0xFF161F3A),
        secondaryContainer = raised,
        onSecondaryContainer = ink,
        tertiary = colorResource(R.color.cockpit_warning),
        onTertiary = Color(0xFF472206),
        tertiaryContainer = Color(0xFF57301A),
        onTertiaryContainer = Color(0xFFFFDCC4),
        background = colorResource(R.color.cockpit_background),
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = raised,
        onSurfaceVariant = muted,
        surfaceContainer = surface,
        surfaceContainerLowest = colorResource(R.color.cockpit_background),
        surfaceContainerLow = Color(0xFF10121A),
        surfaceContainerHigh = raised,
        surfaceContainerHighest = colorResource(R.color.cockpit_surface_highest),
        surfaceTint = accent,
        surfaceBright = Color(0xFF323A4C),
        surfaceDim = colorResource(R.color.cockpit_background),
        outline = colorResource(R.color.cockpit_outline),
        outlineVariant = colorResource(R.color.cockpit_stroke),
        error = colorResource(R.color.cockpit_error),
        onError = Color(0xFF4D0808),
        errorContainer = Color(0xFF592622),
        onErrorContainer = Color(0xFFFFDEDA),
        inverseSurface = Color(0xFFE5E7EF),
        inverseOnSurface = Color(0xFF1B2030),
        inversePrimary = Color(0xFF3B5CCA),
    )
}
