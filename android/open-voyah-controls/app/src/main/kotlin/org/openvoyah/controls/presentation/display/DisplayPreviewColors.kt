package org.openvoyah.controls.presentation.display

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.openvoyah.controls.domain.display.DisplayPreviewPalette

internal val DaylightPreviewColors = lightColorScheme(
    background = Color(0xFFEEF2F7),
    onBackground = Color(0xFF152132),
    onSurfaceVariant = Color(0xFF405269),
    primary = Color(0xFF1D3464),
    secondaryContainer = Color(0xFF7889A1),
)

@Composable
internal fun previewColorScheme(palette: DisplayPreviewPalette): ColorScheme = when (palette) {
    DisplayPreviewPalette.COCKPIT -> MaterialTheme.colorScheme
    DisplayPreviewPalette.DAYLIGHT -> DaylightPreviewColors
}
