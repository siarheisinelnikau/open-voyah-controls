package org.openvoyah.controls.presentation.dock

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.openvoyah.controls.domain.dock.QuickActionHandleAccent
import org.openvoyah.controls.domain.settings.AppAccent
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.presentation.theme.cockpitAccentColors

internal data class QuickActionHandleColors(val fill: Color, val ink: Color, val outline: Color? = null)

internal fun QuickActionHandleAccent.collapsedColors(colors: ColorScheme, highContrast: Boolean): QuickActionHandleColors {
    val selected = when (this) {
        QuickActionHandleAccent.THEME -> return if (highContrast) {
            QuickActionHandleColors(colors.inverseSurface, colors.inverseOnSurface)
        } else {
            QuickActionHandleColors(colors.primaryContainer, colors.onPrimaryContainer)
        }
        QuickActionHandleAccent.AMBER -> AppAccent.AMBER
        QuickActionHandleAccent.TEAL -> AppAccent.TEAL
    }
    val palette = if (colors.surfaceContainerLow.luminance() > .5f) AppPalette.DAY else AppPalette.DARK
    val accented = cockpitAccentColors(colors, selected, palette, highContrast)
    // Outline the quieter fill so the handle is visible against the rail in both palettes.
    return if (highContrast) QuickActionHandleColors(accented.primary, accented.onPrimary)
    else QuickActionHandleColors(accented.primaryContainer, accented.onPrimaryContainer, accented.primary)
}

internal fun QuickActionHandleColors.dimmedForIdle(colors: ColorScheme, highContrast: Boolean): QuickActionHandleColors {
    val rail = colors.surfaceContainerLow
    val dimmedFill = fill.copy(alpha = .24f).compositeOver(rail)
    val minimumInkContrast = if (highContrast) 7f else 4.5f
    val dimmedInk = sequenceOf(outline, ink, fill, colors.onSurface, Color.White, Color.Black)
        .filterNotNull()
        .firstOrNull { contrast(it, dimmedFill) >= minimumInkContrast && contrast(it, rail) >= 3f }
        ?: return this
    val dimmedOutline = outline?.takeIf { contrast(it, dimmedFill) >= 3f && contrast(it, rail) >= 3f } ?: dimmedInk
    return QuickActionHandleColors(dimmedFill, dimmedInk, dimmedOutline)
}

private fun contrast(first: Color, second: Color): Float {
    val firstLuminance = first.luminance()
    val secondLuminance = second.luminance()
    return (maxOf(firstLuminance, secondLuminance) + .05f) / (minOf(firstLuminance, secondLuminance) + .05f)
}
