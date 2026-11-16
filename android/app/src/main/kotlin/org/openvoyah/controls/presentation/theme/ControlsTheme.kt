package org.openvoyah.controls.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openvoyah.controls.R

private val CockpitTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.25.sp, fontWeight = FontWeight.Medium),
)

@Composable
internal fun ControlsTheme(content: @Composable () -> Unit) {
    val accent = colorResource(R.color.cockpit_accent)
    val ink = colorResource(R.color.cockpit_text)
    val muted = colorResource(R.color.cockpit_muted)
    val surface = colorResource(R.color.cockpit_surface)
    val raised = colorResource(R.color.cockpit_surface_raised)
    MaterialTheme(
        colorScheme = darkColorScheme(
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
        ),
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(24.dp),
        ),
        typography = CockpitTypography,
        content = content,
    )
}
