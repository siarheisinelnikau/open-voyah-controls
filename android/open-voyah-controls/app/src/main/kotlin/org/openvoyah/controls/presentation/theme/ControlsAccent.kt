package org.openvoyah.controls.presentation.theme

import androidx.annotation.StyleRes
import androidx.compose.runtime.staticCompositionLocalOf
import org.openvoyah.controls.R
import org.openvoyah.controls.domain.settings.AppAccent
import org.openvoyah.controls.domain.settings.AppPalette

/**
 * The theme overlay that gives Android's own widgets the Appearance accent: apply it with
 * `theme.applyStyle(controlsAccentOverlay(...), true)` on a window, or pass it as the theme of a platform dialog.
 */
@StyleRes
internal fun controlsAccentOverlay(accent: AppAccent, palette: AppPalette): Int = when (accent) {
    AppAccent.DEFAULT -> if (palette == AppPalette.DAY) R.style.ControlsAccent_Default_Day else R.style.ControlsAccent_Default_Dark
    AppAccent.TEAL -> if (palette == AppPalette.DAY) R.style.ControlsAccent_Teal_Day else R.style.ControlsAccent_Teal_Dark
    AppAccent.AMBER -> if (palette == AppPalette.DAY) R.style.ControlsAccent_Amber_Day else R.style.ControlsAccent_Amber_Dark
}

/** The overlay for the accent and palette that [ControlsTheme] is showing, for platform dialogs opened from Compose. */
internal val LocalControlsAccentOverlay = staticCompositionLocalOf { R.style.ControlsAccent_Default_Dark }
