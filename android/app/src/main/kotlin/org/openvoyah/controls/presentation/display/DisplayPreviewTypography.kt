package org.openvoyah.controls.presentation.display

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.domain.settings.AppRoleTextSize
import org.openvoyah.controls.domain.settings.AppTextSize
import org.openvoyah.controls.presentation.theme.cockpitTypography
import org.openvoyah.controls.presentation.theme.multiplier
import org.openvoyah.controls.presentation.theme.scaledText

private val StandardPreviewTypography = cockpitTypography(AppTextSize.STANDARD)

@Composable
internal fun rememberPreviewTypography(size: AppRoleTextSize): Typography {
    val appTypography = MaterialTheme.typography
    return remember(appTypography, size) { previewTypography(appTypography, size) }
}

internal fun previewTypography(appTypography: Typography, size: AppRoleTextSize): Typography {
    val preset = size.preset ?: return appTypography
    val currentScale = appTypography.titleLarge.fontSize.value / PREVIEW_STANDARD_TITLE_SP
    val relativeScale = preset.multiplier / currentScale
    if (relativeScale == 1f) return appTypography
    return appTypography.copy(
        titleLarge = appTypography.titleLarge.scaledText(relativeScale),
        labelLarge = appTypography.labelLarge.scaledText(relativeScale),
        bodyLarge = appTypography.bodyLarge.scaledText(relativeScale),
        bodyMedium = appTypography.bodyMedium.scaledText(relativeScale),
    )
}

internal fun previewTextScale(typography: Typography, density: Density): Float = with(density) {
    fun TextStyle.measure(standard: TextStyle): Float = maxOf(
        fontSize.toDp().value / standard.fontSize.value,
        lineHeight.toDp().value / standard.lineHeight.value,
    )
    maxOf(
        typography.titleLarge.measure(StandardPreviewTypography.titleLarge),
        typography.labelLarge.measure(StandardPreviewTypography.labelLarge),
        typography.bodyLarge.measure(StandardPreviewTypography.bodyLarge),
        typography.bodyMedium.measure(StandardPreviewTypography.bodyMedium),
    )
}

internal fun effectivePreviewDpi(dpi: Int, deviceDpi: Int): Int = if (dpi == 0) deviceDpi else dpi

internal fun previewCanvasDensity(dpi: Int, fontScale: Float): Density = Density(dpi / 160f, fontScale)

internal fun previewContentWidth(widthPixels: Int, density: Density): Float = with(density) {
    (widthPixels - 2 * PREVIEW_PADDING_DP.dp.roundToPx()).coerceAtLeast(0) / this.density
}
