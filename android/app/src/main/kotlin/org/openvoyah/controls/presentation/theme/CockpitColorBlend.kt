package org.openvoyah.controls.presentation.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

private const val THEME_BLEND_MILLIS = 260
private const val THEME_DISSOLVE_MILLIS = 420
private const val DISSOLVE_OUT = .38f
private const val DISSOLVE_IN = .62f

/**
 * Carries the screen from one palette, accent or contrast choice to the next instead of repainting it in a single
 * frame, which at night is a flash across the whole display. The change runs once and then returns [target]
 * itself, so a settled theme costs nothing. With transitions turned off the new roles apply at once.
 */
@Composable
internal fun rememberBlendedColors(target: ColorScheme, animate: Boolean): ColorScheme {
    // The caller can build an equal scheme again on every recomposition; only a change of roles is a new goal.
    val goal = remember { BlendGoal(target) }
    if (!sameRoles(goal.colors, target)) {
        goal.colors = target
        goal.revision++
    }
    val animated by rememberUpdatedState(animate)
    var from by remember { mutableStateOf(target) }
    var shown by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(goal.revision) {
        val next = goal.colors
        if (goal.revision == 0) return@LaunchedEffect
        if (!animated) {
            from = next
            shown = next
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        // A second change during a transition starts from the mix that is on screen now.
        from = themeTransition(from, shown, progress.value)
        shown = next
        progress.snapTo(0f)
        val millis = if (samePolarity(from, next)) THEME_BLEND_MILLIS else THEME_DISSOLVE_MILLIS
        progress.animateTo(1f, tween(millis, easing = CockpitMotion.Settle))
        from = next
    }
    val fraction = progress.value
    return if (fraction >= 1f) shown else themeTransition(from, shown, fraction)
}

private fun samePolarity(first: ColorScheme, second: ColorScheme): Boolean =
    (first.background.luminance() > .5f) == (second.background.luminance() > .5f)

/**
 * Between two dark or two light schemes every role is mixed directly. Between a dark and a light scheme that
 * would carry text and its background through the same grey, so the content dissolves into the page color, the
 * page color changes, and the content emerges in its new roles.
 */
internal fun themeTransition(start: ColorScheme, end: ColorScheme, fraction: Float): ColorScheme = when {
    fraction <= 0f -> start
    fraction >= 1f -> end
    samePolarity(start, end) -> blend(start, end, fraction)
    fraction < DISSOLVE_OUT -> blend(start, flat(start, start.background), fraction / DISSOLVE_OUT)
    fraction < DISSOLVE_IN -> flat(end, lerp(start.background, end.background, (fraction - DISSOLVE_OUT) / (DISSOLVE_IN - DISSOLVE_OUT)))
    else -> blend(flat(end, end.background), end, (fraction - DISSOLVE_IN) / (1f - DISSOLVE_IN))
}

/** [scheme] with every role a screen reads painted in one [color]. */
private fun flat(scheme: ColorScheme, color: Color): ColorScheme = scheme.copy(
    primary = color, onPrimary = color, primaryContainer = color, onPrimaryContainer = color, inversePrimary = color,
    secondary = color, onSecondary = color, secondaryContainer = color, onSecondaryContainer = color,
    tertiary = color, onTertiary = color, tertiaryContainer = color, onTertiaryContainer = color,
    background = color, onBackground = color, surface = color, onSurface = color, surfaceVariant = color,
    onSurfaceVariant = color, surfaceTint = color, inverseSurface = color, inverseOnSurface = color,
    error = color, onError = color, errorContainer = color, onErrorContainer = color, outline = color, outlineVariant = color,
    surfaceBright = color, surfaceDim = color, surfaceContainer = color, surfaceContainerHigh = color,
    surfaceContainerHighest = color, surfaceContainerLow = color, surfaceContainerLowest = color,
)

private class BlendGoal(var colors: ColorScheme) {
    var revision = 0
}

internal fun sameRoles(first: ColorScheme, second: ColorScheme): Boolean = first === second || (
    first.primary == second.primary && first.onPrimary == second.onPrimary &&
        first.primaryContainer == second.primaryContainer && first.secondary == second.secondary &&
        first.tertiary == second.tertiary && first.background == second.background &&
        first.onBackground == second.onBackground && first.surface == second.surface &&
        first.onSurface == second.onSurface && first.surfaceVariant == second.surfaceVariant &&
        first.onSurfaceVariant == second.onSurfaceVariant && first.outline == second.outline &&
        first.outlineVariant == second.outlineVariant && first.error == second.error &&
        first.surfaceContainerHighest == second.surfaceContainerHighest)

/** Every role a cockpit screen reads, mixed at [fraction] of the way from [start] to [end]. */
internal fun blend(start: ColorScheme, end: ColorScheme, fraction: Float): ColorScheme {
    if (fraction <= 0f) return start
    if (fraction >= 1f) return end
    return end.copy(
        primary = lerp(start.primary, end.primary, fraction),
        onPrimary = lerp(start.onPrimary, end.onPrimary, fraction),
        primaryContainer = lerp(start.primaryContainer, end.primaryContainer, fraction),
        onPrimaryContainer = lerp(start.onPrimaryContainer, end.onPrimaryContainer, fraction),
        inversePrimary = lerp(start.inversePrimary, end.inversePrimary, fraction),
        secondary = lerp(start.secondary, end.secondary, fraction),
        onSecondary = lerp(start.onSecondary, end.onSecondary, fraction),
        secondaryContainer = lerp(start.secondaryContainer, end.secondaryContainer, fraction),
        onSecondaryContainer = lerp(start.onSecondaryContainer, end.onSecondaryContainer, fraction),
        tertiary = lerp(start.tertiary, end.tertiary, fraction),
        onTertiary = lerp(start.onTertiary, end.onTertiary, fraction),
        tertiaryContainer = lerp(start.tertiaryContainer, end.tertiaryContainer, fraction),
        onTertiaryContainer = lerp(start.onTertiaryContainer, end.onTertiaryContainer, fraction),
        background = lerp(start.background, end.background, fraction),
        onBackground = lerp(start.onBackground, end.onBackground, fraction),
        surface = lerp(start.surface, end.surface, fraction),
        onSurface = lerp(start.onSurface, end.onSurface, fraction),
        surfaceVariant = lerp(start.surfaceVariant, end.surfaceVariant, fraction),
        onSurfaceVariant = lerp(start.onSurfaceVariant, end.onSurfaceVariant, fraction),
        surfaceTint = lerp(start.surfaceTint, end.surfaceTint, fraction),
        inverseSurface = lerp(start.inverseSurface, end.inverseSurface, fraction),
        inverseOnSurface = lerp(start.inverseOnSurface, end.inverseOnSurface, fraction),
        error = lerp(start.error, end.error, fraction),
        onError = lerp(start.onError, end.onError, fraction),
        errorContainer = lerp(start.errorContainer, end.errorContainer, fraction),
        onErrorContainer = lerp(start.onErrorContainer, end.onErrorContainer, fraction),
        outline = lerp(start.outline, end.outline, fraction),
        outlineVariant = lerp(start.outlineVariant, end.outlineVariant, fraction),
        surfaceBright = lerp(start.surfaceBright, end.surfaceBright, fraction),
        surfaceDim = lerp(start.surfaceDim, end.surfaceDim, fraction),
        surfaceContainer = lerp(start.surfaceContainer, end.surfaceContainer, fraction),
        surfaceContainerHigh = lerp(start.surfaceContainerHigh, end.surfaceContainerHigh, fraction),
        surfaceContainerHighest = lerp(start.surfaceContainerHighest, end.surfaceContainerHighest, fraction),
        surfaceContainerLow = lerp(start.surfaceContainerLow, end.surfaceContainerLow, fraction),
        surfaceContainerLowest = lerp(start.surfaceContainerLowest, end.surfaceContainerLowest, fraction),
    )
}
