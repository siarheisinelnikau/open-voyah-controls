package org.openvoyah.controls.presentation.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import org.openvoyah.controls.domain.settings.RouteTransitionSpeed

/** Finite motion only. Compose uses the system animator duration scale, including zero. */
internal object CockpitMotion {
    const val PRESS = 120
    const val CHANGE = 180
    const val ENTER = 240
    const val EXIT = 110

    private val shortRoute = RouteTransitionDurations(120, 75)
    private val standardRoute = RouteTransitionDurations(CHANGE, EXIT)
    private val smoothRoute = RouteTransitionDurations(240, 150)

    fun routeDurations(speed: RouteTransitionSpeed): RouteTransitionDurations = when (speed) {
        RouteTransitionSpeed.SHORT -> shortRoute
        RouteTransitionSpeed.STANDARD -> standardRoute
        RouteTransitionSpeed.SMOOTH -> smoothRoute
    }
}

internal data class RouteTransitionDurations(val enterMillis: Int, val exitMillis: Int)

internal data class PressMotion(val source: MutableInteractionSource, val modifier: Modifier, val color: Color)

@Composable
internal fun rememberPressMotion(enabled: Boolean = true): PressMotion {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed && enabled) .985f else 1f,
        tween(CockpitMotion.PRESS, easing = FastOutSlowInEasing), label = "control press")
    val color by animateColorAsState(
        if (pressed && enabled) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface,
        tween(CockpitMotion.PRESS), label = "control surface")
    return PressMotion(source, Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }, color)
}
