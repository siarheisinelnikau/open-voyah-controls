package org.openvoyah.controls.presentation.theme

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.R

internal const val LAUNCH_BRAND_TAG = "launchBrand"

@Composable
internal fun LaunchBrand(onFinished: () -> Unit) {
    val phase = remember { Animatable(0f) }
    val finish = rememberUpdatedState(onFinished)
    val title = stringResource(R.string.app_name)
    val measureText = rememberTextMeasurer()

    LaunchedEffect(phase) {
        if (BuildConfig.DEBUG) Log.d("OpenVoyahControls", "event=launch_brand phase=started")
        phase.animateTo(1f, tween(1850, easing = LinearEasing))
        if (BuildConfig.DEBUG) Log.d("OpenVoyahControls", "event=launch_brand phase=completed")
        finish.value()
    }

    Spacer(Modifier.fillMaxSize()
        .semantics {
            hideFromAccessibility()
            testTag = LAUNCH_BRAND_TAG
        }
        .pointerInput(Unit) { detectTapGestures(onPress = { finish.value() }) }
        .drawWithCache {
            val titleLayout = measureText.measure(title,
                style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = (size.width - 32.dp.toPx()).toInt().coerceAtLeast(0)),
            )
            val markHeight = minOf(132.dp.toPx(), size.height * .37f, size.width * .43f)
            val markScale = markHeight / LaunchEmblem.HEIGHT
            val emblem = LaunchEmblem(this, layoutDirection, markScale.coerceIn(.5f, 3f))
            val centerX = size.width / 2f
            val titleGap = 20.dp.toPx()
            val titleShift = 6.dp.toPx()
            val shadowShift = 2.dp.toPx()
            val top = (size.height - markHeight * .76f - titleGap - titleLayout.size.height) / 2f
            val glowCenter = Offset(centerX, top + markHeight * .32f)
            val glowRadius = (markHeight * 1.25f).coerceAtLeast(1f)
            val ambient = Brush.radialGradient(listOf(Color(0xFF243952), Color(0xA31B2839), Color.Transparent),
                center = glowCenter, radius = glowRadius)
            val warmAmbient = Brush.radialGradient(listOf(Color(0xFF481727), Color.Transparent),
                center = glowCenter, radius = glowRadius * .86f)
            val shadow = Brush.radialGradient(listOf(Color(0xA6010409), Color.Transparent), center = Offset.Zero, radius = 1f)

            onDrawBehind {
                // Reading the phase in draw keeps composition, layout and text measurement out of the frame loop.
                val progress = phase.value
                val out = 1f - ease(progress, .86f, 1f)
                val reveal = ease(progress, .02f, .32f)
                val spread = ease(progress, .07f, .48f)
                val wingAlpha = ease(progress, .07f, .43f) * out
                val heat = ease(progress, .33f, .52f) * (1f - ease(progress, .60f, .76f))
                val titleAlpha = ease(progress, .21f, .49f) * out
                drawCircle(ambient, radius = glowRadius, center = glowCenter, alpha = reveal * out * (1f - .43f * heat))
                if (heat > 0f) {
                    drawCircle(warmAmbient, radius = glowRadius * .86f, center = glowCenter, alpha = .64f * heat)
                }
                if (markScale > 0f) withTransform({
                    translate(centerX + shadowShift * (1f - reveal), top + markHeight * 1.02f)
                    scale(markHeight * .23f, markHeight * .026f, pivot = Offset.Zero)
                }) { drawCircle(shadow, center = Offset.Zero, radius = 1f, alpha = reveal * out) }

                val emblemScale = markScale * (.91f + .09f * reveal)
                val horizontalScale = emblemScale * (.90f + .10f * reveal)
                val lightX = if (progress in .18f.. .81f) -54f + 164f * ((progress - .18f) / .63f) else null
                withTransform({
                    translate(centerX - LaunchEmblem.WIDTH * horizontalScale / 2f, top + (markHeight - LaunchEmblem.HEIGHT * emblemScale) / 2f)
                    scale(horizontalScale, emblemScale, pivot = Offset.Zero)
                }) {
                    with(emblem) { draw(reveal * out, spread, wingAlpha, lightX, heat) }
                }
                val titleColor = lerp(Color(0xFFEAF0FB), Color(0xFFD34459), heat)
                drawText(titleLayout, color = titleColor, alpha = titleAlpha,
                    topLeft = Offset(centerX - titleLayout.size.width / 2f, top + markHeight + titleGap + titleShift * (1f - titleAlpha)))
            }
        })
}

private fun ease(progress: Float, begin: Float, end: Float): Float =
    FastOutSlowInEasing.transform(((progress - begin) / (end - begin)).coerceIn(0f, 1f))
