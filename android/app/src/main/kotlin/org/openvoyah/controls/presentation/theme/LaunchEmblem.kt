package org.openvoyah.controls.presentation.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil

private const val LEFT_RIM = "M0 0L26 28C30.5 33 32 39 32.3 48L33 88C29 81 26 68 24 56C22 47 17 40 8 30C2 23 0 17 0 11Z"
private const val LEFT_FACE = "M1.4 3.5L25 28.9C29.6 34 30.8 40 31 48L31.6 82.8C29 77 26.9 66 25.1 55.6C23 46 18.4 39.2 9.1 29.3C3.8 23.2 1.4 17.4 1.4 11Z"
private const val CENTER_RIM = "M38 25C40.1 27.5 41.6 29.6 41.5 33L40.4 85C40.2 92 39.4 97 38 100C36.6 97 35.8 92 35.6 85L34.5 33C34.4 29.6 35.9 27.5 38 25Z"
private const val CENTER_FACE = "M38 27C39.7 29.2 40.3 31 40.2 33L39.2 85C39 90 38.6 94 38 96.4C37.4 94 37 90 36.8 85L35.8 33C35.7 31 36.3 29.2 38 27Z"

// All the feathers share a hinge at the upper tip of their metal branch.
private val FEATHERS = listOf(
    "M0 0C-13 -4 -36 -2 -48 -24C-47 -6 -24 11 -10 10C-5 10 0 7 4 8Z",
    "M1 5C-14 7 -31 7 -47 -12C-44 6 -24 20 -9 18C-3 17 2 15 8 17Z",
    "M4 12C-9 17 -26 18 -43 3C-39 19 -23 28 -8 26C0 24 6 24 11 26Z",
    "M8 20C-3 25 -21 30 -34 21C-28 34 -14 38 -1 34C5 32 10 31 15 32Z",
    "M14 29C3 33 -8 41 -23 37C-17 46 -6 44 3 41C10 37 15 38 19 39Z",
    "M18 36C10 40 1 47 -12 45C-5 50 6 49 12 47C17 44 20 46 23 48Z",
)

/** The metal and glass are rasterized once at the cockpit's rendering scale. */
internal class LaunchEmblem(density: Density, direction: LayoutDirection, renderScale: Float) {
    private val left = PathParser().parsePathString(LEFT_RIM).toPath()
    private val spinePath = PathParser().parsePathString(CENTER_RIM).toPath()
    private val crimson = ColorFilter.lighting(Color(0xFFB3233D), Color.Black)
    private val glintColors = listOf(Color.Transparent, Color(0xFFE5F5FF), Color.Transparent)
    private val badge = renderLayer(density, direction, IntRect(-4, -3, 81, 107), renderScale) {
        val leftFace = PathParser().parsePathString(LEFT_FACE).toPath()
        val centerFace = PathParser().parsePathString(CENTER_FACE).toPath()
        val depth = Brush.linearGradient(listOf(Color(0xFFF0F6FE), Color(0xFF293C54), Color(0xFF8097B5)),
            start = Offset(1f, 0f), end = Offset(33f, 76f))
        val bevel = Brush.linearGradient(
            0f to Color(0xFFFAFCFF), .38f to Color(0xFFCED7E3), .57f to Color(0xFF596F8C),
            .72f to Color(0xFFF5FAFF), 1f to Color(0xFF8D9BB0),
            start = Offset(2f, 3f), end = Offset(31f, 57f),
        )
        val satin = Brush.linearGradient(
            0f to Color(0xFFB3C2D6), .20f to Color(0xFFF9FBFE), .38f to Color(0xFFE1EBF7),
            .54f to Color(0xFF8F9FB6), .70f to Color(0xFFF1F6FD), .84f to Color(0xFFC7D4E6),
            1f to Color(0xFF849AB5),
            start = Offset(-3f, 29f), end = Offset(36f, 48f),
        )
        fun DrawScope.branch() {
            translate(1.6f, 2.7f) { drawPath(left, depth, alpha = .9f) }
            drawPath(left, bevel)
            drawPath(leftFace, satin)
            drawPath(leftFace, Color(0xFFF5F9FE), alpha = .17f, style = Stroke(.36f))
        }
        branch()
        mirror { branch() }
        val spine = Brush.horizontalGradient(listOf(Color(0xFF8C9FB9), Color(0xFFEDF6FF), Color(0xFFF7FBFF),
            Color(0xFF99AFC9), Color(0xFFCAD5E5)), startX = 34.5f, endX = 41.5f)
        translate(1.0f, 2.4f) { drawPath(spinePath, depth) }
        drawPath(spinePath, bevel)
        drawPath(centerFace, spine)
    }
    private val wing = renderLayer(density, direction, IntRect(-51, -27, 26, 54), renderScale) {
        for (outline in FEATHERS.asReversed()) {
            val path = PathParser().parsePathString(outline).toPath()
            val bounds = path.getBounds()
            val glass = Brush.linearGradient(
                0f to Color(0xA0DBEBFB), .22f to Color(0x3CB6CBDF), .54f to Color(0x244A6786),
                .79f to Color(0x90CADDF1), 1f to Color(0xB2EFF7FF),
                start = Offset(bounds.left + 7f, bounds.top), end = Offset(bounds.right - 5f, bounds.bottom),
            )
            val edge = Brush.horizontalGradient(listOf(Color(0x3FCADBF0), Color(0xB5EAF4FD), Color(0x40ABC9E5)),
                startX = bounds.left, endX = bounds.right)
            drawPath(path, glass)
            drawPath(path, edge, style = Stroke(.46f))
        }
    }

    fun DrawScope.draw(bodyOpacity: Float, spread: Float, wingOpacity: Float, lightX: Float?, heat: Float) {
        if (wingOpacity > 0f) {
            fun DrawScope.openWing() {
                rotate(-24f * (1f - spread), pivot = Offset.Zero) {
                    scale(.70f + .30f * spread, .78f + .22f * spread, pivot = Offset.Zero) {
                        wing.paint(this, wingOpacity)
                    }
                }
            }
            openWing()
            mirror { openWing() }
        }
        if (bodyOpacity <= 0f) return
        badge.paint(this, bodyOpacity)
        if (heat > 0f) badge.paint(this, heat * bodyOpacity, crimson)
        if (lightX != null) {
            val reflection = Brush.linearGradient(glintColors,
                start = Offset(lightX, 0f), end = Offset(lightX + 30f, -17f))
            val shine = (.66f - .36f * heat) * bodyOpacity
            drawPath(left, reflection, alpha = shine)
            drawPath(spinePath, reflection, alpha = shine)
            mirror {
                val mirrorReflection = Brush.linearGradient(glintColors,
                    start = Offset(WIDTH - lightX, 0f), end = Offset(WIDTH - lightX - 30f, -17f))
                drawPath(left, mirrorReflection, alpha = shine)
            }
        }
    }

    private fun DrawScope.mirror(draw: DrawScope.() -> Unit) = scale(-1f, 1f, Offset(WIDTH / 2f, 0f), draw)

    companion object {
        const val WIDTH = 76f
        const val HEIGHT = 106f
    }
}

private class EmblemLayer(private val image: ImageBitmap, private val bounds: IntRect) {
    fun paint(scope: DrawScope, alpha: Float, colorFilter: ColorFilter? = null) = with(scope) {
        drawImage(image, dstOffset = IntOffset(bounds.left, bounds.top), dstSize = IntSize(bounds.width, bounds.height),
            alpha = alpha, colorFilter = colorFilter, filterQuality = FilterQuality.Medium)
    }
}

private fun renderLayer(density: Density, direction: LayoutDirection, bounds: IntRect, rasterScale: Float,
                        paint: DrawScope.() -> Unit): EmblemLayer {
    val raster = ImageBitmap(ceil(bounds.width * rasterScale).toInt(), ceil(bounds.height * rasterScale).toInt())
    CanvasDrawScope().draw(density, direction, Canvas(raster), Size(raster.width.toFloat(), raster.height.toFloat())) {
        withTransform({
            scale(rasterScale, rasterScale, pivot = Offset.Zero)
            translate(-bounds.left.toFloat(), -bounds.top.toFloat())
        }, paint)
    }
    return EmblemLayer(raster, bounds)
}
