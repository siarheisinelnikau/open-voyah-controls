package org.openvoyah.controls.presentation.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import org.openvoyah.controls.domain.settings.AppRoleTextSize
import org.openvoyah.controls.domain.settings.AppSpacing
import org.openvoyah.controls.domain.settings.AppTextSize

internal data class CockpitLayout(
    val spacingScale: Float = 1f,
    val textScale: Float = 1f,
    val navigationLabelScale: Float = textScale,
    val pageTitleScale: Float = textScale,
) {
    fun spacing(base: Dp): Dp = base * spacingScale

    fun columnsMinimum(base: Dp, systemFontScale: Float, labelScale: Float = textScale): Dp =
        base * (maxOf(textScale, labelScale) * systemFontScale).coerceAtLeast(1f)
}

internal val LocalCockpitLayout = staticCompositionLocalOf { CockpitLayout() }

internal fun cockpitLayout(
    spacing: AppSpacing,
    text: AppTextSize,
    navigationLabels: AppRoleTextSize = AppRoleTextSize.FOLLOW,
    pageTitles: AppRoleTextSize = AppRoleTextSize.FOLLOW,
): CockpitLayout = CockpitLayout(
    spacingScale = when (spacing) {
        AppSpacing.COMPACT -> 0.8f
        AppSpacing.STANDARD -> 1f
        AppSpacing.SPACIOUS -> 1.2f
    },
    textScale = text.multiplier,
    navigationLabelScale = (navigationLabels.preset ?: text).multiplier,
    pageTitleScale = (pageTitles.preset ?: text).multiplier,
)

internal val AppTextSize.multiplier: Float get() = when (this) {
    AppTextSize.STANDARD -> 1f
    AppTextSize.LARGE -> 1.15f
    AppTextSize.EXTRA_LARGE -> 1.3f
}
