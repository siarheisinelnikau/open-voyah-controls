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
            primaryContainer = Color(0xFF2B3E52),
            onPrimaryContainer = ink,
            secondary = Color(0xFFB6C5D5),
            onSecondary = Color(0xFF1D2A37),
            secondaryContainer = raised,
            onSecondaryContainer = ink,
            tertiary = Color(0xFFCAB8A4),
            background = colorResource(R.color.cockpit_background),
            onBackground = ink,
            surface = surface,
            onSurface = ink,
            surfaceVariant = raised,
            onSurfaceVariant = muted,
            surfaceContainer = surface,
            surfaceContainerHigh = raised,
            surfaceContainerHighest = Color(0xFF2A3747),
            outline = Color(0xFF8091A5),
            outlineVariant = colorResource(R.color.cockpit_stroke),
            error = Color(0xFFE9A9A4),
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
