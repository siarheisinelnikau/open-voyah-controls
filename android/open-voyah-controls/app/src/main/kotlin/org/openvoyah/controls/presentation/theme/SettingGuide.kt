package org.openvoyah.controls.presentation.theme

import androidx.annotation.ArrayRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText

internal data class SettingGuide(@ArrayRes val copyRes: Int, val picture: GuidePicture)

internal enum class GuidePicture(val icon: CockpitIcon) {
    GRID(CockpitIcon.APPS), DESKTOP(CockpitIcon.HOME), WINDOW(CockpitIcon.DISPLAY),
    DOCK(CockpitIcon.BUTTONS), BUTTON(CockpitIcon.BUTTONS), TEXT(CockpitIcon.LANGUAGE),
    THEME(CockpitIcon.LIGHT), SOUND(CockpitIcon.PLAY), VOICE(CockpitIcon.VOICE),
    CLIMATE(CockpitIcon.CLIMATE), LIGHT(CockpitIcon.LIGHT), CAR(CockpitIcon.CAR),
    TIME(CockpitIcon.ROUTINE), RULE(CockpitIcon.ROUTINE), FILE(CockpitIcon.DOWNLOAD),
    ACCESS(CockpitIcon.LOCK), SYSTEM(CockpitIcon.SYSTEM), PALETTE(CockpitIcon.LIGHT),
    ACCENT(CockpitIcon.LIGHT), DIM(CockpitIcon.LIGHT), SPACING(CockpitIcon.APPS),
    CORNERS(CockpitIcon.APPS), MOTION(CockpitIcon.DISPLAY), SWIPE(CockpitIcon.DISPLAY),
    VOLUME_DOWN(CockpitIcon.PLAY), VOLUME_UP(CockpitIcon.PLAY),
    SIDE(CockpitIcon.DISPLAY), EDGE(CockpitIcon.DISPLAY), STACK(CockpitIcon.DISPLAY), ORDER(CockpitIcon.APPS),
    DRIVER_MEDIA_SOURCES(CockpitIcon.PLAY), DRIVER_LARGE_PLAYER(CockpitIcon.PLAY), DRIVER_NAVIGATION(CockpitIcon.DISPLAY),
}

/** A separate touch target, including beside controls that are currently disabled. */
@Composable
internal fun SettingInfo(title: String, guideId: Int?, modifier: Modifier = Modifier) {
    val guide = guideId?.let(settingGuides::get) ?: return
    var open by remember(guideId) { mutableStateOf(false) }
    val description = UiText.get(R.string.setting_guide_open).format(title)
    IconButton(
        { open = true },
        modifier.size(48.dp).semantics { contentDescription = description },
    ) {
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(Modifier.size(19.dp)) {
            val stroke = 1.35.dp.toPx()
            drawCircle(color, size.minDimension / 2 - stroke, style = Stroke(stroke))
            drawCircle(color, stroke * .65f, Offset(center.x, size.height * .34f))
            drawLine(color, Offset(center.x, size.height * .49f), Offset(center.x, size.height * .72f), stroke, StrokeCap.Round)
        }
    }
    if (open) SettingGuideDialog(title, guide) { open = false }
}

@Composable
internal fun SettingGuideHeading(title: String, guideId: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        SettingInfo(title, guideId)
    }
}

@Composable
private fun SettingGuideDialog(title: String, guide: SettingGuide, dismiss: () -> Unit) {
    val (description, before, after) = UiText.getArray(guide.copyRes).toList()
    val motion = LocalCockpitMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val progress = remember { Animatable(0f) }
    var replay by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(replay, motion.enabled, lifecycle) {
            progress.snapTo(0f)
            // Stop when the app hides and keep the end state when it returns.
            lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }.distinctUntilChanged()
                .collectLatest { visible ->
                    if (!visible || !motion.enabled) progress.snapTo(1f)
                    else if (progress.value < 1f) progress.animateTo(1f, tween(720, easing = CockpitMotion.Emphasized))
                }
        }
        Box(Modifier.padding(horizontal = 16.dp).widthIn(max = 460.dp)) {
            SettingGuideContent(title, guide.picture, description, before, after, dismiss,
                replay = if (motion.enabled) ({ replay++ }) else null, progress = { progress.value })
        }
    }
}

@Composable
private fun SettingGuideContent(title: String, picture: GuidePicture, description: String, before: String, after: String,
                                dismiss: () -> Unit, replay: (() -> Unit)?, progress: () -> Float) {
    CockpitModal(title, headerAction = { CockpitTextAction(UiText.get(R.string.ui_172), dismiss) }) {
        Text(description, style = MaterialTheme.typography.bodyLarge)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CockpitGlyph(picture.icon, Modifier.size(19.dp), MaterialTheme.colorScheme.primary)
                        Text(UiText.get(R.string.setting_guide_example), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    replay?.let { CockpitTextAction(UiText.get(R.string.setting_guide_replay), it) }
                }
                GuidePreview(picture, progress)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(before, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(after, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun GuidePreview(picture: GuidePicture, progress: () -> Float) {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        val side = minOf(size.width / 2f, 200.dp.toPx())
        val phase = progress()
        val startX = size.width * .25f
        val endX = size.width * .75f
        val factor = minOf(side * .76f / 100f, size.height / 62f)
        val bridgeStart = startX + 50f * factor
        val bridgeEnd = endX - 50f * factor
        drawLine(colors.outlineVariant, Offset(bridgeStart, center.y), Offset(bridgeEnd, center.y), 2.dp.toPx(), StrokeCap.Round)
        val marker = bridgeStart + (bridgeEnd - bridgeStart) * (.15f + .70f * phase)
        drawCircle(colors.primary, 3.dp.toPx(), Offset(marker, center.y))
        fun panel(x: Float, fraction: Float) {
            val origin = Offset(x - 50f * factor, center.y - 30f * factor)
            scale(factor, factor, origin) {
                drawGuidePicture(picture, origin, fraction, colors.primary, colors.outline, colors.surface)
            }
        }
        panel(startX, 0f)
        panel(endX, phase)
    }
}

private fun DrawScope.drawGuidePicture(picture: GuidePicture, origin: Offset, amount: Float, accent: Color, line: Color, fill: Color) {
    fun point(x: Float, y: Float) = origin + Offset(x, y)
    fun bar(x: Float, y: Float, length: Float, color: Color = accent, stroke: Float = 3f) =
        drawLine(color, point(x, y), point(x + length, y), stroke, StrokeCap.Round)
    fun block(x: Float, y: Float, width: Float, height: Float, color: Color, radius: Float = 4f) =
        drawRoundRect(color, point(x, y), Size(width, height), CornerRadius(radius))

    block(0f, 0f, 100f, 60f, line, 9f)
    block(1.5f, 1.5f, 97f, 57f, fill, 8f)
    when (picture) {
        GuidePicture.DRIVER_MEDIA_SOURCES, GuidePicture.DRIVER_LARGE_PLAYER, GuidePicture.DRIVER_NAVIGATION ->
            drawDriverMediaGuide(picture, origin, amount, accent, line, fill)
        GuidePicture.GRID, GuidePicture.DESKTOP -> {
            val gap = 7f - amount * 2f
            val width = 32f - amount * 9f
            for (row in 0..1) for (column in 0..2) {
                val color = if (column == 2) accent.copy(alpha = amount * .8f) else if (row == 0) accent else line
                block(10f + column * (width + gap), 12f + 21f * row, width, 15f, color)
            }
        }
        GuidePicture.WINDOW, GuidePicture.DOCK -> {
            val rail = 12f + 18f * amount
            block(9f, 9f, rail, 42f, if (picture == GuidePicture.DOCK) accent else line)
            block(14f + rail, 9f, 77f - rail, 42f, if (picture == GuidePicture.WINDOW) accent else line)
        }
        GuidePicture.SIDE -> {
            block(9f, 9f, 82f, 42f, line.copy(alpha = .26f))
            block(9f + 65f * amount, 9f, 17f, 42f, accent)
        }
        GuidePicture.EDGE -> {
            block(9f, 9f, 82f, 42f, line.copy(alpha = .26f))
            block(9f, 9f + 31f * amount, 82f, 11f, accent)
        }
        GuidePicture.STACK -> {
            block(9f, 9f, 38f + 44f * amount, 42f - 23f * amount, accent)
            block(53f - 44f * amount, 9f + 23f * amount, 38f + 44f * amount, 42f - 23f * amount, line)
        }
        GuidePicture.ORDER -> {
            block(12f, 13f, 27f, 34f, lerp(line, accent, amount))
            block(44f, 13f, 18f, 34f, lerp(accent, line, amount))
            block(67f, 13f, 21f, 34f, line.copy(alpha = .55f))
        }
        GuidePicture.TEXT -> {
            for (row in 0..2) bar(15f, 16f + row * 14f, (65f - row * 11f) * (.72f + .4f * amount),
                if (row == 0) accent else line, 3f + amount * 2f)
        }
        GuidePicture.PALETTE, GuidePicture.ACCENT, GuidePicture.DIM -> {
            val dark = Color(0xFF142028)
            val teal = Color(0xFF57CDB6)
            val background = when (picture) {
                GuidePicture.PALETTE -> lerp(dark, Color(0xFFE1EBED), amount)
                GuidePicture.DIM -> lerp(Color(0xFF528594), dark, amount * .75f)
                else -> dark
            }
            val highlight = if (picture == GuidePicture.ACCENT) lerp(Color(0xFF92BBFA), teal, amount) else teal
            block(2f, 2f, 96f, 56f, background, 8f)
            block(12f, 11f, 23f, 38f, highlight.copy(alpha = .55f))
            block(43f, 12f, 45f, 13f, highlight)
            bar(44f, 35f, 40f, if (picture == GuidePicture.PALETTE) lerp(Color.White, dark, amount) else Color.White)
            bar(44f, 44f, 29f, highlight)
        }
        GuidePicture.SPACING, GuidePicture.CORNERS -> {
            val gap = if (picture == GuidePicture.SPACING) 4f + amount * 10f else 7f
            val corner = if (picture == GuidePicture.CORNERS) 2f + amount * 8f else 4f
            block(10f, 12f, 80f, (36f - gap) / 2f, line, corner)
            block(10f, 12f + (36f + gap) / 2f, 80f, (36f - gap) / 2f, accent, corner)
        }
        GuidePicture.MOTION -> {
            bar(13f, 45f, 74f, line, 2f)
            block(13f + amount * 46f, 15f, 28f, 21f, accent)
        }
        GuidePicture.SWIPE -> {
            block(9f, 13f, 37f, 34f, line.copy(alpha = .35f))
            block(54f, 13f, 37f, 34f, line.copy(alpha = .35f))
            block(16f + 45f * amount, 20f, 23f, 20f, accent)
        }
        GuidePicture.VOLUME_DOWN, GuidePicture.VOLUME_UP -> {
            val level = .32f + .46f * if (picture == GuidePicture.VOLUME_UP) amount else 1f - amount
            bar(12f, 30f, 76f, line, 5f)
            bar(12f, 30f, 76f * level, accent, 5f)
            drawCircle(accent, 5f, point(12f + 76f * level, 30f))
        }
        GuidePicture.THEME, GuidePicture.LIGHT -> {
            drawCircle(accent, 8f + 9f * amount, point(50f, 30f))
            drawCircle(accent.copy(alpha = .32f), 14f + 9f * amount, point(50f, 30f), style = Stroke(2f))
        }
        GuidePicture.SOUND, GuidePicture.VOICE, GuidePicture.CLIMATE -> {
            val levels = listOf(.25f, .50f, .90f, .64f, .36f, .72f, .26f)
            levels.forEachIndexed { index, level ->
                val height = 6f + 36f * level * (.35f + amount * .65f)
                block(12f + index * 11f, 30f - height / 2, 7f, height, if (index % 2 == 0) line else accent)
            }
        }
        GuidePicture.TIME, GuidePicture.RULE -> {
            bar(14f, 30f, 72f, line)
            bar(14f, 30f, 10f + amount * 62f)
            for (index in 0..2) drawCircle(if (amount >= index * .5f) accent else line, 5f, point(14f + 36f * index, 30f))
        }
        GuidePicture.CAR, GuidePicture.BUTTON, GuidePicture.FILE, GuidePicture.ACCESS, GuidePicture.SYSTEM -> {
            block(12f, 13f, 23f, 34f, line)
            block(65f, 13f, 23f, 34f, accent.copy(alpha = .25f + amount * .75f))
            bar(41f, 30f, 3f + 15f * amount)
        }
    }
}

/** Use the same left slot and gauge in each illustration so the guide shows the affected area. */
private fun DrawScope.drawDriverMediaGuide(
    picture: GuidePicture, origin: Offset, amount: Float, accent: Color, line: Color, fill: Color,
) {
    fun point(x: Float, y: Float) = origin + Offset(x, y)
    fun bar(x: Float, y: Float, length: Float, color: Color) =
        drawLine(color, point(x, y), point(x + length, y), 2f, StrokeCap.Round)
    fun card(x: Float, y: Float, width: Float, height: Float, color: Color, radius: Float = 4f) =
        drawRoundRect(color, point(x, y), Size(width, height), CornerRadius(radius))

    card(8f, 12f, 46f, 36f, line.copy(alpha = .18f))
    val dial = point(75f, 30f)
    drawCircle(line.copy(alpha = .74f), 12f, dial, style = Stroke(1.5f))
    drawLine(line, dial, point(79f, 23f), 1.5f, StrokeCap.Round)
    drawCircle(line, 1.5f, dial)

    if (amount < 1f) {
        val stock = line.copy(alpha = 1f - amount)
        drawCircle(stock, 6.5f, point(20f, 30f), style = Stroke(1.6f))
        drawCircle(stock, 1.5f, point(20f, 30f))
        bar(31f, 27f, 15f, stock)
        bar(31f, 34f, 10f, stock)
    }
    if (amount == 0f) return

    val highlight = accent.copy(alpha = amount)
    when (picture) {
        GuidePicture.DRIVER_MEDIA_SOURCES -> {
            card(13f, 23f, 14f, 14f, highlight)
            drawLine(fill.copy(alpha = amount), point(20f, 32f), point(20f, 26f), 1.5f, StrokeCap.Round)
            drawLine(fill.copy(alpha = amount), point(20f, 26f), point(23f, 27f), 1.5f, StrokeCap.Round)
            drawCircle(fill.copy(alpha = amount), 1.6f, point(18.5f, 32f))
            bar(31f, 27f, 15f, highlight)
            bar(31f, 34f, 10f, line.copy(alpha = amount))
        }
        GuidePicture.DRIVER_LARGE_PLAYER -> {
            card(8f, 12f, 46f, 36f, accent.copy(alpha = .22f * amount))
            val artSize = 14f + 5f * amount
            card(12f, 30f - artSize / 2, artSize, artSize, highlight)
            drawCircle(fill.copy(alpha = amount), 5f, point(12f + artSize / 2, 30f), style = Stroke(1.5f))
            bar(35f, 24f, 14f, highlight)
            bar(35f, 30f, 10f, line.copy(alpha = amount))
            bar(35f, 38f, 14f, line.copy(alpha = .52f * amount))
            bar(35f, 38f, 4f + 5f * amount, highlight)
        }
        GuidePicture.DRIVER_NAVIGATION -> {
            val top = 23f - 3f * amount
            card(12f, top, 38f, 20f, fill.copy(alpha = amount))
            drawRoundRect(highlight, point(12f, top), Size(38f, 20f), CornerRadius(4f), style = Stroke(1.4f))
            bar(17f, top + 5f, 11f, highlight)
            bar(17f, top + 10f, 27f, line.copy(alpha = amount))
            bar(17f, top + 15f, 9f, line.copy(alpha = .72f * amount))
        }
        else -> Unit
    }
}
