package org.openvoyah.controls.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.openvoyah.controls.domain.settings.AppAccent
import org.openvoyah.controls.domain.settings.AppPalette

private data class AccentRoles(
    val primary: Long,
    val onPrimary: Long,
    val container: Long,
    val onContainer: Long,
    val inverse: Long,
)

private data class AccentFamily(
    val dark: AccentRoles,
    val darkContrast: AccentRoles,
    val day: AccentRoles,
    val dayContrast: AccentRoles,
) {
    fun roles(palette: AppPalette, highContrast: Boolean): AccentRoles = when (palette) {
        AppPalette.DARK -> if (highContrast) darkContrast else dark
        AppPalette.DAY -> if (highContrast) dayContrast else day
    }
}

private val TealAccent = AccentFamily(
    dark = AccentRoles(0xFF7AD7CA, 0xFF06312B, 0xFF134741, 0xFFC8FFF6, 0xFF156559),
    darkContrast = AccentRoles(0xFF8AF7E7, 0xFF071F1A, 0xFF184E45, 0xFFEBFFF9, 0xFF0B554A),
    day = AccentRoles(0xFF006358, 0xFFF7FFFD, 0xFFCCF4EC, 0xFF004138, 0xFF8CE7D6),
    dayContrast = AccentRoles(0xFF004A41, 0xFFFFFFFF, 0xFFAAF0DF, 0xFF002A24, 0xFFACFBEF),
)

private val AmberAccent = AccentFamily(
    dark = AccentRoles(0xFFF5CF71, 0xFF362400, 0xFF4F3913, 0xFFFFF2CF, 0xFF74510A),
    darkContrast = AccentRoles(0xFFFFE397, 0xFF2A1D00, 0xFF584113, 0xFFFFF8E6, 0xFF684500),
    day = AccentRoles(0xFF774F00, 0xFFFFF9E9, 0xFFFAE8B3, 0xFF442F00, 0xFFFFD572),
    dayContrast = AccentRoles(0xFF5A3A00, 0xFFFFFFFF, 0xFFFCE6A3, 0xFF312200, 0xFFFFE399),
)

internal fun cockpitAccentColors(
    base: ColorScheme,
    accent: AppAccent,
    palette: AppPalette,
    highContrast: Boolean,
): ColorScheme {
    val family = when (accent) {
        AppAccent.DEFAULT -> return base
        AppAccent.TEAL -> TealAccent
        AppAccent.AMBER -> AmberAccent
    }
    val roles = family.roles(palette, highContrast)
    return base.copy(
        primary = Color(roles.primary),
        onPrimary = Color(roles.onPrimary),
        primaryContainer = Color(roles.container),
        onPrimaryContainer = Color(roles.onContainer),
        inversePrimary = Color(roles.inverse),
        surfaceTint = Color(roles.primary),
    )
}
