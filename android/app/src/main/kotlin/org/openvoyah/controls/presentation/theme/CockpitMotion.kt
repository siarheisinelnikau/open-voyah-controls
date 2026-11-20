package org.openvoyah.controls.presentation.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.openvoyah.controls.domain.settings.RouteTransitionSpeed

/**
 * Finite motion only. Compose uses the system animator duration scale, including zero, and nothing here keeps
 * the frame loop alive once a change has settled: the head unit must render no frames while a page is idle.
 */
internal object CockpitMotion {
    const val PRESS = 120
    const val CHANGE = 180
    const val ENTER = 240
    const val EXIT = 110

    /** Blocks later than this position share the last step of an arrival cascade. */
    const val MAX_STAGGERED = 7

    /** Content that arrives decelerates into place; content that leaves accelerates away. */
    val Arrive: Easing = LinearOutSlowInEasing
    val Leave: Easing = FastOutLinearInEasing
    val Settle: Easing = FastOutSlowInEasing

    /** A longer, calmer curve for large surfaces such as dialogs and the first block of a page. */
    val Emphasized: Easing = CubicBezierEasing(.2f, 0f, 0f, 1f)

    private val shortRoute = RouteTransitionDurations(120, 75)
    private val standardRoute = RouteTransitionDurations(CHANGE, EXIT)
    private val smoothRoute = RouteTransitionDurations(240, 150)

    fun routeDurations(speed: RouteTransitionSpeed): RouteTransitionDurations = when (speed) {
        RouteTransitionSpeed.SHORT -> shortRoute
        RouteTransitionSpeed.STANDARD -> standardRoute
        RouteTransitionSpeed.SMOOTH -> smoothRoute
    }

    /** The arrival of a page follows the chosen transition speed, so a short route change is not outlasted by it. */
    fun cascade(speed: RouteTransitionSpeed): CascadeTiming = when (speed) {
        RouteTransitionSpeed.SHORT -> CascadeTiming(riseMillis = 140, staggerMillis = 16)
        RouteTransitionSpeed.STANDARD -> CascadeTiming(riseMillis = 220, staggerMillis = 26)
        RouteTransitionSpeed.SMOOTH -> CascadeTiming(riseMillis = 280, staggerMillis = 34)
    }

    /** A value or layout change inside a visible block. */
    fun <T> change(): FiniteAnimationSpec<T> = tween(CHANGE, easing = Settle)

    fun <T> enter(delayMillis: Int = 0): FiniteAnimationSpec<T> = tween(ENTER, delayMillis, Arrive)

    fun <T> exit(): FiniteAnimationSpec<T> = tween(EXIT, easing = Leave)

    /** The slightly elastic return of a released control. It settles within a few frames and then stops. */
    fun <T> release(): FiniteAnimationSpec<T> = spring(dampingRatio = .62f, stiffness = Spring.StiffnessMedium)
}

internal data class RouteTransitionDurations(val enterMillis: Int, val exitMillis: Int)

/** One block rises for [riseMillis]; each later block starts [staggerMillis] after its neighbour. */
internal data class CascadeTiming(val riseMillis: Int, val staggerMillis: Int) {
    /** Time after which every block of an arriving page has settled. */
    val totalMillis: Int get() = riseMillis + staggerMillis * CockpitMotion.MAX_STAGGERED

    fun delayMillis(order: Int): Int = order.coerceIn(0, CockpitMotion.MAX_STAGGERED) * staggerMillis
}

/** The motion choices from Appearance. With [enabled] off, decorative motion changes at once. */
internal data class CockpitMotionSettings(
    val enabled: Boolean = true,
    val speed: RouteTransitionSpeed = RouteTransitionSpeed.STANDARD,
) {
    val cascade: CascadeTiming get() = CockpitMotion.cascade(speed)
    val route: RouteTransitionDurations get() = CockpitMotion.routeDurations(speed)
}

internal val LocalCockpitMotion = staticCompositionLocalOf { CockpitMotionSettings() }

/** False for a page the driver is returning to: it was already seen, so it appears without the arrival cascade. */
internal val LocalCockpitEntrance = compositionLocalOf { true }

/** Hands out positions to the blocks of one arriving page in composition order. */
internal class EntranceCascade {
    private var next = 0
    private var settled = false

    /** Blocks composed after the page settled, such as a result card, arrive on their own without a delay. */
    fun take(): Int = if (settled) 0 else next++.coerceAtMost(CockpitMotion.MAX_STAGGERED)

    fun settle() { settled = true }
}

internal val LocalEntranceCascade = compositionLocalOf<EntranceCascade?> { null }

/** Creates the cascade of one page and closes it once the first blocks have arrived. */
@Composable
internal fun rememberEntranceCascade(): EntranceCascade {
    val cascade = remember { EntranceCascade() }
    val settleAfter = LocalCockpitMotion.current.cascade.totalMillis.toLong()
    LaunchedEffect(cascade) {
        delay(settleAfter)
        cascade.settle()
    }
    return cascade
}

/**
 * Lets a block arrive once: a short rise and fade, delayed by its position on the page. Only the draw layer
 * changes, so text is neither measured nor laid out again, and the animation ends.
 */
@Composable
internal fun Modifier.cockpitEntrance(order: Int = 0): Modifier {
    val motion = LocalCockpitMotion.current
    if (!motion.enabled || !LocalCockpitEntrance.current) return this
    val timing = motion.cascade
    val progress = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        progress.animateTo(1f, tween(timing.riseMillis, timing.delayMillis(order), CockpitMotion.Arrive))
    }
    val rise = with(LocalDensity.current) { 14.dp.toPx() }
    return graphicsLayer {
        val arrived = progress.value
        alpha = arrived
        translationY = rise * (1f - arrived)
    }
}

/** The position of the calling block in the cascade of its page, or null outside an arriving page. */
@Composable
internal fun rememberEntranceOrder(): Int? {
    val cascade = LocalEntranceCascade.current ?: return null
    return remember(cascade) { cascade.take() }
}

internal data class PressMotion(val source: MutableInteractionSource, val modifier: Modifier, val color: Color)

/**
 * Shared press feedback. [depth] is how far the control sinks: rows and buttons barely move, while an icon tile
 * without a surface of its own answers with a deeper press and a short elastic release.
 */
@Composable
internal fun rememberPressMotion(enabled: Boolean = true, depth: Float = .015f): PressMotion {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val down = pressed && enabled
    val scale = animateFloatAsState(
        targetValue = if (down) 1f - depth else 1f,
        animationSpec = if (down) tween(CockpitMotion.PRESS, easing = CockpitMotion.Settle) else CockpitMotion.release(),
        label = "control press",
    )
    val color by animateColorAsState(
        if (down) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface,
        tween(CockpitMotion.PRESS), label = "control surface")
    return PressMotion(source, Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }, color)
}
