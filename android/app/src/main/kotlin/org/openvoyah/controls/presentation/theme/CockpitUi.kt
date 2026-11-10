package org.openvoyah.controls.presentation.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText

internal enum class CockpitIcon { HOME, APPS, VOICE, DISPLAY, BUTTONS, SETTINGS, ARROW, BACK, DOWNLOAD, LOCK, CAR, LANGUAGE, ENERGY }

@Composable
internal fun CockpitGlyph(icon: CockpitIcon, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            val stroke = Stroke(1.55f, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, endX: Float, endY: Float) =
                drawLine(color, Offset(x, y), Offset(endX, endY), 1.55f, StrokeCap.Round)
            fun box(x: Float, y: Float, w: Float, h: Float, radius: Float = 1.6f) =
                drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(radius), style = stroke)
            fun path(points: Path.() -> Unit) = drawPath(Path().apply(points), color, style = stroke)
            when (icon) {
                CockpitIcon.HOME -> path {
                    moveTo(3f, 11f); lineTo(12f, 3.5f); lineTo(21f, 11f)
                    lineTo(20f, 20f); lineTo(15f, 20f); lineTo(15f, 15f)
                    lineTo(9f, 15f); lineTo(9f, 20f); lineTo(4f, 20f); close()
                }
                CockpitIcon.APPS -> {
                    box(3.5f, 3.5f, 6.5f, 6.5f); box(14f, 3.5f, 6.5f, 6.5f)
                    box(3.5f, 14f, 6.5f, 6.5f); box(14f, 14f, 6.5f, 6.5f)
                }
                CockpitIcon.VOICE -> {
                    box(9f, 3f, 6f, 12f, 3f)
                    path { moveTo(5.5f, 11f); cubicTo(5.5f, 20f, 18.5f, 20f, 18.5f, 11f) }
                    line(12f, 18f, 12f, 21f)
                }
                CockpitIcon.DISPLAY -> {
                    box(2.5f, 4.5f, 19f, 13f, 2f); line(9f, 21f, 15f, 21f); line(12f, 18f, 12f, 21f)
                }
                CockpitIcon.BUTTONS -> {
                    drawCircle(color, 9f, Offset(12f, 12f), style = stroke)
                    drawCircle(color, 2.2f, Offset(12f, 12f), style = stroke)
                    line(3.5f, 9f, 10f, 11f); line(14f, 11f, 20.5f, 9f); line(12f, 14f, 12f, 20.5f)
                }
                CockpitIcon.SETTINGS -> {
                    line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f)
                    line(9f, 4f, 9f, 8f); line(15f, 10f, 15f, 14f); line(10f, 16f, 10f, 20f)
                }
                CockpitIcon.ARROW -> path { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
                CockpitIcon.BACK -> {
                    line(5f, 12f, 19f, 12f)
                    path { moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) }
                }
                CockpitIcon.DOWNLOAD -> {
                    line(12f, 3f, 12f, 15f)
                    path { moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f) }
                    path { moveTo(4f, 16f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 16f) }
                }
                CockpitIcon.LOCK -> {
                    box(5f, 10f, 14f, 10f)
                    path { moveTo(8f, 10f); lineTo(8f, 7f); cubicTo(8f, 2f, 16f, 2f, 16f, 7f); lineTo(16f, 10f) }
                    line(12f, 14f, 12f, 16f)
                }
                CockpitIcon.CAR -> {
                    path { moveTo(3f, 12f); lineTo(6f, 6f); lineTo(18f, 6f); lineTo(21f, 12f) }
                    box(3f, 11f, 18f, 7f)
                    line(6f, 18f, 6f, 20f); line(18f, 18f, 18f, 20f)
                    line(6f, 14.5f, 8f, 14.5f); line(16f, 14.5f, 18f, 14.5f)
                }
                CockpitIcon.LANGUAGE -> {
                    drawCircle(color, 9f, Offset(12f, 12f), style = stroke)
                    line(3f, 12f, 21f, 12f)
                    path { moveTo(12f, 3f); cubicTo(7f, 7f, 7f, 17f, 12f, 21f); cubicTo(17f, 17f, 17f, 7f, 12f, 3f) }
                }
                CockpitIcon.ENERGY -> path {
                    moveTo(14f, 2.5f); lineTo(5.5f, 13.5f); lineTo(11.5f, 13.5f)
                    lineTo(10f, 21.5f); lineTo(18.5f, 10.5f); lineTo(12.5f, 10.5f); close()
                }
            }
        }
    }
}

@Composable
internal fun CockpitHeading(title: String, subtitle: String? = null, back: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (back != null) {
            TextButton(
                onClick = back,
                modifier = Modifier.heightIn(min = 52.dp).border(
                    1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                CockpitGlyph(CockpitIcon.BACK, Modifier.size(20.dp))
                Text(UiText.get(R.string.cockpit_back), Modifier.padding(start = 6.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
