package org.openvoyah.controls.presentation.setup

import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.openvoyah.controls.domain.setup.SetupProgress
import org.openvoyah.controls.domain.setup.SetupStep
import org.openvoyah.controls.domain.setup.StepState
import org.openvoyah.controls.presentation.theme.CockpitMotion
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout

/**
 * The clock the rows read: it ticks once a second only while a step runs, so a finished or idle page draws no
 * frames. Elapsed seconds come from each step's own start time, never from a timer of their own.
 */
@Composable
internal fun rememberSetupClock(running: Boolean): Long {
    val now by produceState(SystemClock.elapsedRealtime(), running) {
        value = SystemClock.elapsedRealtime()
        while (running) {
            delay(1_000 - value % 1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    return now
}

@Composable
internal fun SetupSteps(progress: SetupProgress, text: SetupStepText, now: Long, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val layout = LocalCockpitLayout.current
    Surface(
        modifier.fillMaxWidth().animateContentSize(CockpitMotion.change()),
        shape = MaterialTheme.shapes.large, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(layout.spacing(16.dp)), verticalArrangement = Arrangement.spacedBy(layout.spacing(12.dp))) {
            for (step in progress.steps) SetupStepRow(step, text, now)
        }
    }
}

@Composable
private fun SetupStepRow(step: SetupStep, text: SetupStepText, now: Long) {
    val colors = MaterialTheme.colorScheme
    val state = step.state
    val title = text.title(step.id)
    val status = text.status(state, now)
    val detail = text.detail(state)
    val tone = when (state) {
        is StepState.Failed -> colors.error
        is StepState.Attention -> colors.tertiary
        is StepState.Done, is StepState.Running -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().testTag("setup-step-${step.id.name.lowercase()}")
            // One stop for screen readers: title, reason and status are read together, in that order.
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top,
    ) {
        StepMarker(state, tone, Modifier.padding(top = 3.dp).size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!detail.isNullOrBlank()) {
                Text(detail, style = MaterialTheme.typography.bodyMedium,
                    color = if (state is StepState.Failed) colors.error else colors.onSurfaceVariant)
            }
        }
        Text(status, style = MaterialTheme.typography.bodyMedium, color = tone, textAlign = TextAlign.End)
    }
}

/** Static marks only: a running step is shown by its counting seconds and the page's progress bar, not a spinner. */
@Composable
private fun StepMarker(state: StepState, tone: Color, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val cut = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        val radius = size.minDimension / 2 - stroke
        val center = Offset(size.width / 2, size.height / 2)
        fun line(x: Float, y: Float, endX: Float, endY: Float) = drawLine(cut, Offset(x * size.width, y * size.height),
            Offset(endX * size.width, endY * size.height), stroke, StrokeCap.Round)
        when (state) {
            StepState.Pending -> drawCircle(outline, radius, center, style = Stroke(stroke))
            is StepState.Running -> {
                drawCircle(tone, radius, center, style = Stroke(stroke))
                drawCircle(tone, radius * 0.45f, center)
            }
            is StepState.Done -> {
                drawCircle(tone, radius + stroke / 2, center)
                line(0.3f, 0.52f, 0.45f, 0.67f); line(0.45f, 0.67f, 0.72f, 0.36f)
            }
            is StepState.Attention -> {
                drawCircle(tone, radius + stroke / 2, center)
                line(0.5f, 0.28f, 0.5f, 0.56f); line(0.5f, 0.72f, 0.5f, 0.73f)
            }
            is StepState.Skipped -> {
                drawCircle(outline, radius, center, style = Stroke(stroke))
                drawLine(outline, Offset(size.width * 0.32f, center.y), Offset(size.width * 0.68f, center.y), stroke, StrokeCap.Round)
            }
            is StepState.Failed -> {
                drawCircle(tone, radius + stroke / 2, center)
                line(0.34f, 0.34f, 0.66f, 0.66f); line(0.66f, 0.34f, 0.34f, 0.66f)
            }
        }
    }
}
