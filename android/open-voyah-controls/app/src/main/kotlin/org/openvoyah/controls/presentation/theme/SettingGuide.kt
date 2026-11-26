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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

internal data class SettingGuide(@ArrayRes val copyRes: Int, val buildStory: (Int) -> GuideStory)

/** A separate touch target, including beside controls that are currently disabled. */
@Composable
internal fun SettingInfo(title: String, guideId: Int?, modifier: Modifier = Modifier, variant: Int = 0, effect: String? = null) {
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
    if (open) SettingGuideDialog(title, guide, variant, effect) { open = false }
}

@Composable
internal fun SettingGuideHeading(
    title: String, guideId: Int, modifier: Modifier = Modifier, variant: Int = 0, effect: String? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        SettingInfo(title, guideId, variant = variant, effect = effect)
    }
}

@Composable
private fun SettingGuideDialog(title: String, guide: SettingGuide, variant: Int, effect: String?, dismiss: () -> Unit) {
    val (description, before, after) = UiText.getArray(guide.copyRes).toList()
    val language = UiText.language()
    val story = remember(guide, variant, language) { guide.buildStory(variant).localizeSampleLabels() }
    val motion = LocalCockpitMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val progress = remember(motion.enabled) { Animatable(if (motion.enabled) 0f else 1f) }
    var replay by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(replay, motion.enabled, lifecycle) {
            if (!motion.enabled) return@LaunchedEffect
            progress.snapTo(0f)
            // Stop when the app hides and keep the end state when it returns.
            lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }.distinctUntilChanged()
                .collectLatest { visible ->
                    if (!visible) progress.snapTo(1f)
                    else if (progress.value < 1f) progress.animateTo(1f, tween(if (story.event == null) 1400 else 2100))
                }
        }
        Box(Modifier.padding(horizontal = 16.dp).widthIn(max = 460.dp)) {
            SettingGuideContent(title, story, description, effect, before, after, dismiss,
                replay = if (motion.enabled) ({ replay++ }) else null, progress = { progress.value })
        }
    }
}

@Composable
private fun SettingGuideContent(title: String, story: GuideStory, description: String, effect: String?, before: String, after: String,
                                dismiss: () -> Unit, replay: (() -> Unit)?, progress: () -> Float) {
    CockpitModal(title, headerAction = { CockpitTextAction(UiText.get(R.string.ui_172), dismiss) }) {
        Text(description, style = MaterialTheme.typography.bodyLarge)
        effect?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(UiText.get(R.string.setting_guide_example), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    replay?.let { CockpitTextAction(UiText.get(R.string.setting_guide_replay), it) }
                }
                GuidePreview(story, progress)
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
