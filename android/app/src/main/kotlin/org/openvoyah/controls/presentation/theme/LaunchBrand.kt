package org.openvoyah.controls.presentation.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.settings.AppAppearance
import org.openvoyah.controls.domain.settings.AppPalette

internal const val LAUNCH_BRAND_TAG = "launchBrand"

/**
 * The startup intro as fractions of [DURATION_MILLIS], each written as its time from the first frame. Every stage is
 * finite: the screen is plain first, the wings open, the emblem and the name follow, the accent charges the metal
 * once, the finished mark holds still and then leaves.
 *
 * The entrance is over in under 1.8 s. The hold that follows keeps the whole mark on screen for about 2.4 s more:
 * the intro appears about 40 s after the head unit starts, when the launcher agent is ready, and a mark that left
 * after one second was easy to miss. A tap closes it at any point.
 */
internal object LaunchTimeline {
    const val DURATION_MILLIS = 4500

    const val WINGS_START = 220f / DURATION_MILLIS
    const val WINGS_VISIBLE = 660f / DURATION_MILLIS
    const val WINGS_OPEN = 1012f / DURATION_MILLIS
    const val BODY_START = 440f / DURATION_MILLIS
    const val BODY_VISIBLE = 924f / DURATION_MILLIS
    const val TITLE_START = 572f / DURATION_MILLIS
    const val TITLE_VISIBLE = 924f / DURATION_MILLIS
    const val RULE_START = 748f / DURATION_MILLIS
    const val RULE_DRAWN = 1232f / DURATION_MILLIS
    const val CAPTION_START = 880f / DURATION_MILLIS
    const val CAPTION_VISIBLE = 1232f / DURATION_MILLIS
    const val GLINT_START = 528f / DURATION_MILLIS
    const val GLINT_END = 1760f / DURATION_MILLIS
    const val CHARGE_START = 924f / DURATION_MILLIS
    const val CHARGE_FULL = 1188f / DURATION_MILLIS
    const val CHARGE_FADE = 1364f / DURATION_MILLIS
    const val CHARGE_END = 1716f / DURATION_MILLIS
    const val PULSE_START = 1012f / DURATION_MILLIS
    const val PULSE_END = 1672f / DURATION_MILLIS
    const val EXIT_START = 4192f / DURATION_MILLIS
}

/** The intro uses the cockpit's own roles, so an accent or contrast choice in Appearance also colors the startup. */
internal data class LaunchPalette(
    val metal: LaunchMetal,
    val energy: Color,
    val warm: Color,
    val title: Color,
    val caption: Color,
    val glowCore: Color,
    val glowEdge: Color,
)

/** A three-point steel ramp. Level 0 is the deepest shadow and 1 the brightest edge. */
internal class LaunchMetal(private val shade: Color, private val body: Color, private val light: Color) {
    fun tone(level: Float, alpha: Float = 1f): Color {
        val color = if (level < .5f) lerp(shade, body, level * 2f) else lerp(body, light, (level - .5f) * 2f)
        return if (alpha == 1f) color else color.copy(alpha = alpha)
    }
}

internal fun launchPalette(colors: ColorScheme): LaunchPalette {
    val depth = lerp(lerp(colors.secondary, colors.background, .78f), colors.primary, .12f)
    return LaunchPalette(
        metal = LaunchMetal(depth, lerp(colors.secondary, colors.background, .32f), lerp(colors.secondary, Color.White, .86f)),
        energy = colors.primary,
        warm = colors.tertiary,
        title = colors.onBackground,
        caption = colors.onSurfaceVariant,
        glowCore = lerp(colors.background, colors.primary, .30f),
        glowEdge = lerp(colors.background, colors.primary, .13f).copy(alpha = .64f),
    )
}

private val WingEasing = CubicBezierEasing(.22f, 1.18f, .36f, 1f)

/** The cockpit backdrop fades in before the mark and out after it; the window itself is transparent. */
internal const val LAUNCH_BACKDROP_MILLIS = CockpitMotion.ENTER

/** The longest the intro waits, invisibly, for steady frames before it plays anyway. */
internal const val LAUNCH_SETTLE_LIMIT_MILLIS = 5_000L

/** A point at which nearly every part of the mark is drawn, used to prepare the drawing before the intro shows. */
private const val WARM_PHASE = (LaunchTimeline.CHARGE_FULL + LaunchTimeline.CHARGE_FADE) / 2

/** One step of the 8-bit color range: drawn, so paths, text and shaders are prepared, yet not visible. */
private const val WARM_ALPHA = 1f / 255f

/** Backdrop opacity at [introMillis] of the intro's own time. */
internal fun launchBackdrop(introMillis: Float): Float =
    if (introMillis < LaunchTimeline.DURATION_MILLIS) {
        CockpitMotion.Arrive.transform((introMillis / LAUNCH_BACKDROP_MILLIS).coerceIn(0f, 1f))
    } else {
        1f - CockpitMotion.Leave.transform(
            ((introMillis - LaunchTimeline.DURATION_MILLIS) / LAUNCH_BACKDROP_MILLIS).coerceIn(0f, 1f))
    }

private class LaunchRun {
    var done = false
    var settled = false
    var settleNanos = 0L
    var clock: LaunchClock? = null

    fun report(completed: Boolean) = LaunchReport(
        completed = completed,
        settled = settled,
        settleMillis = settleNanos / 1_000_000L,
        frames = clock?.frames ?: 0,
        slowFrames = clock?.slowFrames ?: 0,
        longestGapMillis = (clock?.longestGapNanos ?: 0L) / 1_000_000L,
        elapsedMillis = (clock?.wallNanos ?: 0L) / 1_000_000L,
    )
}

/**
 * Runs the intro in two parts. First it draws nothing visible and waits until frames arrive steadily (see
 * [LaunchSettle]), drawing the whole mark once per frame at an invisible alpha so that the first visible frames do
 * not pay for text, paths and shaders. [onShown] then lets the window take touches, the backdrop fades in and the
 * timeline plays on [LaunchClock]. [onFinished] receives what the run looked like; a tap ends it early.
 *
 * The backdrop covers the whole window, including the strip that [ControlsTheme] keeps free for the launcher's
 * panel, as the opaque window background used to; the mark stays centered beside the panel.
 */
@Composable
internal fun LaunchBrand(appearance: AppAppearance, onShown: () -> Unit, onFinished: (LaunchReport) -> Unit) {
    val shown = rememberUpdatedState(onShown)
    val finish = rememberUpdatedState(onFinished)
    val run = remember { LaunchRun() }
    var warming by remember { mutableStateOf(true) }
    val warmFrame = remember { mutableLongStateOf(0L) }
    val introMillis = remember { mutableFloatStateOf(0f) }
    val refreshRate = LocalView.current.display?.refreshRate?.takeIf { it >= 20f } ?: 60f

    fun end(completed: Boolean) {
        if (run.done) return
        run.done = true
        finish.value(run.report(completed))
    }

    LaunchedEffect(run) {
        val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        val frameNanos = (1_000_000_000L / refreshRate).toLong()
        val settle = LaunchSettle(frameNanos)
        val first = withFrameNanos { it }
        var now = first
        while (now - first < LAUNCH_SETTLE_LIMIT_MILLIS * 1_000_000L) {
            now = withFrameNanos { it }
            warmFrame.longValue = now
            if (settle.onFrame(now)) {
                run.settled = true
                break
            }
        }
        run.settleNanos = now - first
        warming = false
        shown.value()
        ControlsLog.debug("launch_brand", "phase", "started", "settled", run.settled)
        val totalMillis = LaunchTimeline.DURATION_MILLIS + LAUNCH_BACKDROP_MILLIS
        val clock = LaunchClock(frameNanos, (totalMillis * scale.coerceAtLeast(0f) * 1_000_000.0).toLong())
        run.clock = clock
        while (scale > 0f && introMillis.floatValue < totalMillis) {
            introMillis.floatValue = clock.onFrame(withFrameNanos { it }) / 1_000_000f / scale
        }
        ControlsLog.debug("launch_brand", "phase", "completed")
        end(completed = true)
    }

    val backdropColor = colorResource(R.color.cockpit_background)
    Box(Modifier.fillMaxSize()
        .drawBehind {
            val alpha = launchBackdrop(introMillis.floatValue)
            if (alpha > 0f) drawRect(backdropColor, alpha = alpha)
        }
        .pointerInput(Unit) { detectTapGestures(onPress = { end(completed = false) }) }) {
        // The intro keeps the dark roles in every palette and only takes accent, contrast and type from Appearance.
        ControlsTheme(appearance = appearance, temporaryPalette = AppPalette.DARK) {
            Box(Modifier.fillMaxSize()) {
                LaunchBrandFrame({
                    warmFrame.longValue
                    WARM_PHASE
                }, Modifier.graphicsLayer { alpha = if (warming) WARM_ALPHA else 0f }, backdrop = { 0f })
                LaunchBrandFrame({ (introMillis.floatValue / LaunchTimeline.DURATION_MILLIS).coerceIn(0f, 1f) },
                    backdrop = { 0f })
            }
        }
    }
}

/**
 * The intro at one point of [LaunchTimeline] on a [backdrop] of the given opacity. [phase] is read while drawing,
 * which keeps composition, layout and text measurement out of the frame loop; it also lets a test show a single
 * stage for as long as it needs.
 */
@Composable
internal fun LaunchBrandFrame(phase: () -> Float, modifier: Modifier = Modifier, backdrop: () -> Float = { 1f }) {
    val backdropColor = colorResource(R.color.cockpit_background)
    val title = stringResource(R.string.app_name)
    val caption = UiText.get(R.string.ui_33)
    val measureText = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val palette = remember(colors) { launchPalette(colors) }
    val titleStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 1.6.sp)
    val captionStyle = MaterialTheme.typography.bodyMedium.copy(letterSpacing = .6.sp)

    Spacer(Modifier.fillMaxSize()
        .semantics {
            hideFromAccessibility()
            testTag = LAUNCH_BRAND_TAG
        }
        .then(modifier)
        .drawWithCache {
            val textWidth = Constraints(maxWidth = (size.width - 32.dp.toPx()).toInt().coerceAtLeast(0))
            val titleLayout = measureText.measure(title, style = titleStyle, maxLines = 1,
                overflow = TextOverflow.Ellipsis, constraints = textWidth)
            val captionLayout = measureText.measure(caption, style = captionStyle, maxLines = 1,
                overflow = TextOverflow.Ellipsis, constraints = textWidth)
            val markHeight = minOf(132.dp.toPx(), size.height * .37f, size.width * .43f)
            val markScale = markHeight / LaunchEmblem.HEIGHT
            val emblem = LaunchEmblem(this, layoutDirection, markScale.coerceIn(.5f, 3f), palette)
            val centerX = size.width / 2f
            val titleGap = 20.dp.toPx()
            val ruleGap = 10.dp.toPx()
            val ruleHeight = 2.dp.toPx()
            val captionGap = 9.dp.toPx()
            val rise = 6.dp.toPx()
            val shadowShift = 2.dp.toPx()
            // The slim lower spine carries little weight, so the block is centered as if the mark were shorter.
            val blockHeight = markHeight * .82f + titleGap + titleLayout.size.height + ruleGap + ruleHeight +
                captionGap + captionLayout.size.height
            val top = ((size.height - blockHeight) / 2f).coerceAtLeast(0f)
            val titleTop = top + markHeight + titleGap
            val ruleTop = titleTop + titleLayout.size.height + ruleGap
            val captionTop = ruleTop + ruleHeight + captionGap
            val ruleWidth = maxOf(titleLayout.size.width.toFloat(), captionLayout.size.width.toFloat())
            val glowCenter = Offset(centerX, top + markHeight * .32f)
            val glowRadius = (markHeight * 1.25f).coerceAtLeast(1f)
            val pivot = Offset(centerX, top + blockHeight / 2f)
            val shortEdge = size.minDimension.coerceAtLeast(1f)

            // The same two quiet lights as the shared screens, so the intro sits on the cockpit's own backdrop.
            val coolLight = Brush.radialGradient(
                listOf(palette.energy.copy(alpha = .09f), palette.energy.copy(alpha = .025f), palette.energy.copy(alpha = 0f)),
                center = Offset(size.width * .94f, size.height * .06f), radius = shortEdge * 1.1f)
            val warmShade = Brush.radialGradient(listOf(palette.warm.copy(alpha = .035f), palette.warm.copy(alpha = 0f)),
                center = Offset(size.width * .03f, size.height * .98f), radius = shortEdge * .85f)
            val ambient = Brush.radialGradient(listOf(palette.glowCore, palette.glowEdge, Color.Transparent),
                center = glowCenter, radius = glowRadius)
            val chargeGlow = Brush.radialGradient(listOf(palette.energy.copy(alpha = .42f), palette.energy.copy(alpha = 0f)),
                center = glowCenter, radius = glowRadius * .9f)
            val shadow = Brush.radialGradient(listOf(Color.Black.copy(alpha = .65f), Color.Transparent),
                center = Offset.Zero, radius = 1f)
            val rule = Brush.horizontalGradient(
                listOf(palette.energy.copy(alpha = 0f), palette.energy, palette.energy.copy(alpha = 0f)),
                startX = centerX - ruleWidth / 2f, endX = centerX + ruleWidth / 2f)
            val pulseStroke = Stroke(1.5.dp.toPx())

            onDrawBehind {
                val backdropAlpha = backdrop()
                if (backdropAlpha > 0f) drawRect(backdropColor, alpha = backdropAlpha.coerceAtMost(1f))
                val progress = phase()
                if (progress <= LaunchTimeline.WINGS_START) return@onDrawBehind

                val out = 1f - ease(progress, LaunchTimeline.EXIT_START, 1f)
                val reveal = ease(progress, LaunchTimeline.BODY_START, LaunchTimeline.BODY_VISIBLE)
                val spread = WingEasing.transform(span(progress, LaunchTimeline.WINGS_START, LaunchTimeline.WINGS_OPEN))
                val wingAlpha = ease(progress, LaunchTimeline.WINGS_START, LaunchTimeline.WINGS_VISIBLE) * out
                val charge = ease(progress, LaunchTimeline.CHARGE_START, LaunchTimeline.CHARGE_FULL) *
                    (1f - ease(progress, LaunchTimeline.CHARGE_FADE, LaunchTimeline.CHARGE_END))
                val titleAlpha = ease(progress, LaunchTimeline.TITLE_START, LaunchTimeline.TITLE_VISIBLE) * out
                val ruleDrawn = ease(progress, LaunchTimeline.RULE_START, LaunchTimeline.RULE_DRAWN)
                val captionAlpha = ease(progress, LaunchTimeline.CAPTION_START, LaunchTimeline.CAPTION_VISIBLE) * out
                val pulse = span(progress, LaunchTimeline.PULSE_START, LaunchTimeline.PULSE_END)

                if (reveal > 0f) {
                    drawRect(coolLight, alpha = reveal * out)
                    drawRect(warmShade, alpha = reveal * out)
                }
                // The mark leaves by easing slightly toward the viewer while it fades.
                val exitScale = 1f + .045f * (1f - out)
                withTransform({ scale(exitScale, exitScale, pivot) }) {
                    if (reveal > 0f) {
                        drawCircle(ambient, radius = glowRadius, center = glowCenter, alpha = reveal * out)
                    }
                    if (charge > 0f) {
                        drawCircle(chargeGlow, radius = glowRadius * .9f, center = glowCenter, alpha = charge * out)
                    }
                    if (pulse > 0f && pulse < 1f) {
                        val fade = (1f - pulse) * (1f - pulse)
                        drawCircle(palette.energy, radius = markHeight * (.42f + .95f * FastOutSlowInEasing.transform(pulse)),
                            center = glowCenter, alpha = .5f * fade * out, style = pulseStroke)
                    }
                    if (reveal > 0f && markScale > 0f) withTransform({
                        translate(centerX + shadowShift * (1f - reveal), top + markHeight * 1.02f)
                        scale(markHeight * .23f, markHeight * .026f, pivot = Offset.Zero)
                    }) { drawCircle(shadow, center = Offset.Zero, radius = 1f, alpha = reveal * out) }

                    val emblemScale = markScale * (.91f + .09f * reveal)
                    val horizontalScale = emblemScale * (.90f + .10f * reveal)
                    val lightX = if (progress in LaunchTimeline.GLINT_START..LaunchTimeline.GLINT_END) {
                        -54f + 164f * span(progress, LaunchTimeline.GLINT_START, LaunchTimeline.GLINT_END)
                    } else null
                    withTransform({
                        translate(centerX - LaunchEmblem.WIDTH * horizontalScale / 2f,
                            top + (markHeight - LaunchEmblem.HEIGHT * emblemScale) / 2f)
                        scale(horizontalScale, emblemScale, pivot = Offset.Zero)
                    }) {
                        with(emblem) { draw(reveal * out, spread, wingAlpha, lightX, charge) }
                    }
                    if (titleAlpha > 0f) {
                        drawText(titleLayout, color = lerp(palette.title, palette.energy, charge * .85f), alpha = titleAlpha,
                            topLeft = Offset(centerX - titleLayout.size.width / 2f, titleTop + rise * (1f - titleAlpha)))
                    }
                    if (ruleDrawn > 0f) {
                        val width = ruleWidth * ruleDrawn
                        drawRect(rule, topLeft = Offset(centerX - width / 2f, ruleTop), size = Size(width, ruleHeight),
                            alpha = (.55f + .45f * charge) * out)
                    }
                    if (captionAlpha > 0f) {
                        drawText(captionLayout, color = palette.caption, alpha = captionAlpha,
                            topLeft = Offset(centerX - captionLayout.size.width / 2f, captionTop + rise * (1f - captionAlpha)))
                    }
                }
            }
        })
}

private fun span(progress: Float, begin: Float, end: Float): Float = ((progress - begin) / (end - begin)).coerceIn(0f, 1f)

private fun ease(progress: Float, begin: Float, end: Float): Float = FastOutSlowInEasing.transform(span(progress, begin, end))
