package org.openvoyah.controls.presentation.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.content.res.ResourcesCompat
import org.openvoyah.controls.R
import androidx.compose.ui.unit.dp
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.min

// Coordinates in each panel run from (0, 0) to (132, 78). A stable key identifies
// the same object across frames, so changing its bounds moves or resizes it.
internal const val GUIDE_WIDTH = 132f
internal const val GUIDE_HEIGHT = 78f

internal enum class Ink { MUTED, FG, ACCENT, ACTION, SURFACE, BORDER, PAPER, NIGHT, WHITE, TEAL, AMBER }
internal enum class MarkStyle { FILL, OUTLINE, TEXT, BOLD, GOLOS, GLYPH }

internal data class GuideMark(
    val key: String,
    val style: MarkStyle,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val ink: Ink,
    val radius: Float = 0f,
    val text: String = "",
    val glyph: CockpitIcon? = null,
)

internal typealias GuideFrame = List<GuideMark>

internal data class GuideStory(val before: GuideFrame, val after: GuideFrame, val event: GuideFrame? = null)

internal class GuideFrameBuilder {
    private val marks = mutableListOf<GuideMark>()

    fun box(key: String, x: Int, y: Int, width: Int, height: Int, ink: Ink = Ink.ACTION, radius: Int = 4,
            outline: Boolean = false) {
        marks += GuideMark(key, if (outline) MarkStyle.OUTLINE else MarkStyle.FILL, x.toFloat(), y.toFloat(),
            width.toFloat(), height.toFloat(), ink, radius.toFloat())
    }

    fun word(key: String, text: String, x: Int, y: Int, size: Int = 16, ink: Ink = Ink.FG,
             style: MarkStyle = MarkStyle.BOLD, width: Int = 112) {
        marks += GuideMark(key, style, x.toFloat(), y.toFloat(), width.toFloat(), size.toFloat(), ink, text = text)
    }

    fun icon(key: String, glyph: CockpitIcon, x: Int, y: Int, size: Int = 24, ink: Ink = Ink.ACCENT) {
        marks += GuideMark(key, MarkStyle.GLYPH, x.toFloat(), y.toFloat(), size.toFloat(), size.toFloat(), ink, glyph = glyph)
    }

    fun build(): GuideFrame = marks.toList()
}

internal fun frame(content: GuideFrameBuilder.() -> Unit): GuideFrame = GuideFrameBuilder().apply(content).build()

internal fun story(before: GuideFrameBuilder.() -> Unit, after: GuideFrameBuilder.() -> Unit): GuideStory =
    GuideStory(frame(before), frame(after))

private fun merge(from: GuideFrame, to: GuideFrame): List<Pair<GuideMark?, GuideMark?>> {
    val previous = from.associateBy { it.key }
    val next = to.associateBy { it.key }
    // Insert outgoing marks first, while keeping backgrounds and overlays in scene order.
    return (to.map { it.key } + previous.keys.filterNot(next::containsKey))
        .map { previous[it] to next[it] }
}

@Composable
internal fun GuidePreview(story: GuideStory, progress: () -> Float) {
    val palette = MaterialTheme.colorScheme
    val inks = remember(palette) {
        mapOf(
            Ink.FG to palette.onSurface, Ink.MUTED to palette.onSurfaceVariant, Ink.ACCENT to palette.primary,
            Ink.ACTION to palette.primaryContainer, Ink.SURFACE to palette.surface,
            Ink.BORDER to palette.outlineVariant, Ink.PAPER to Color(0xFFE9F0F4), Ink.NIGHT to Color(0xFF182532),
            Ink.WHITE to Color(0xFFF4F6F8), Ink.TEAL to Color(0xFF148B82), Ink.AMBER to Color(0xFFB97724),
        )
    }
    val first = remember(story) { merge(story.before, story.event ?: story.after) }
    val second = remember(story) { story.event?.let { merge(it, story.after) } }
    val context = LocalContext.current
    val usesGolos = remember(story) { (story.before + story.after).any { it.style == MarkStyle.GOLOS } }
    val golos = remember(context, usesGolos) {
        if (usesGolos) ResourcesCompat.getFont(context, R.font.golos_text) ?: Typeface.DEFAULT else Typeface.DEFAULT
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    Canvas(Modifier.fillMaxWidth().height(96.dp).clearAndSetSemantics {}) {
        val panelScale = min((size.width / 2 - 9.dp.toPx()) / GUIDE_WIDTH, size.height / GUIDE_HEIGHT)
        if (panelScale <= 0f) return@Canvas
        fun panel(centerX: Float, content: DrawScope.() -> Unit) {
            translate(centerX - GUIDE_WIDTH * panelScale / 2, center.y - GUIDE_HEIGHT * panelScale / 2) {
                scale(panelScale, panelScale, Offset.Zero) {
                    drawRoundRect(palette.surface, size = Size(GUIDE_WIDTH, GUIDE_HEIGHT), cornerRadius = CornerRadius(7f))
                    clipRect(1f, 1f, GUIDE_WIDTH - 1, GUIDE_HEIGHT - 1, block = content)
                    drawRoundRect(palette.outlineVariant, size = Size(GUIDE_WIDTH, GUIDE_HEIGHT),
                        cornerRadius = CornerRadius(7f), style = Stroke(1.5f))
                }
            }
        }
        panel(size.width * .25f) {
            story.before.forEach { drawMark(it, inks.getValue(it.ink), paint, golos) }
        }
        panel(size.width * .75f) {
            val phase = progress()
            if (phase >= 1f) {
                story.after.forEach { drawMark(it, inks.getValue(it.ink), paint, golos) }
            } else {
                val leg = ((phase - .14f) / .72f).coerceIn(0f, 1f)
                if (second == null) drawTransition(first, leg, inks, paint, golos)
                else if (leg < .55f) drawTransition(first, (leg / .42f).coerceAtMost(1f), inks, paint, golos)
                else drawTransition(second, (leg - .55f) / .45f, inks, paint, golos)
            }
        }
        translate(center.x - 7.dp.toPx(), center.y - 7.dp.toPx()) {
            scale(14.dp.toPx() / 24f, 14.dp.toPx() / 24f, Offset.Zero) {
                drawCockpitGlyph(CockpitIcon.ARROW, palette.primary)
            }
        }
    }
}

private fun DrawScope.drawTransition(
    track: List<Pair<GuideMark?, GuideMark?>>, fraction: Float, inks: Map<Ink, Color>, paint: Paint, golos: Typeface,
) {
    if (fraction == 0f) {
        track.forEach { (from, _) -> from?.let { drawMark(it, inks.getValue(it.ink), paint, golos) } }
        return
    }
    fun interpolate(start: Float, end: Float) = start + (end - start) * fraction
    track.forEach { (from, to) ->
        when {
            from != null && to != null && from.style == to.style && from.text == to.text && from.glyph == to.glyph -> {
                val mark = to.copy(x = interpolate(from.x, to.x), y = interpolate(from.y, to.y),
                    width = interpolate(from.width, to.width), height = interpolate(from.height, to.height),
                    radius = interpolate(from.radius, to.radius))
                drawMark(mark, lerp(inks.getValue(from.ink), inks.getValue(to.ink), fraction), paint, golos)
            }
            else -> {
                from?.let { drawMark(it, inks.getValue(it.ink).copy(alpha = 1f - fraction), paint, golos) }
                to?.let { drawMark(it, inks.getValue(it.ink).copy(alpha = fraction), paint, golos) }
            }
        }
    }
}

private fun DrawScope.drawMark(mark: GuideMark, color: Color, paint: Paint, golos: Typeface) {
    if (color.alpha <= 0f) return
    when (mark.style) {
        MarkStyle.FILL, MarkStyle.OUTLINE -> {
            val origin = Offset(mark.x, mark.y)
            val bounds = Size(mark.width, mark.height)
            val radius = CornerRadius(mark.radius)
            if (mark.style == MarkStyle.OUTLINE) drawRoundRect(color, origin, bounds, radius, style = Stroke(1.6f))
            else drawRoundRect(color, origin, bounds, radius)
        }
        MarkStyle.GLYPH -> translate(mark.x, mark.y) {
            scale(mark.width / 24f, mark.height / 24f, Offset.Zero) {
                drawCockpitGlyph(checkNotNull(mark.glyph), color)
            }
        }
        MarkStyle.TEXT, MarkStyle.BOLD, MarkStyle.GOLOS -> {
            paint.typeface = when (mark.style) {
                MarkStyle.BOLD -> Typeface.DEFAULT_BOLD
                MarkStyle.GOLOS -> golos
                else -> Typeface.DEFAULT
            }
            paint.textSize = mark.height
            val measured = paint.measureText(mark.text)
            if (measured > mark.width) paint.textSize *= mark.width / measured
            paint.setARGB((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(),
                (color.blue * 255).toInt())
            drawContext.canvas.nativeCanvas.drawText(mark.text, mark.x, mark.y - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }
}
