package org.openvoyah.controls.presentation.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun CockpitDetail(title: String, subtitle: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 22.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CockpitHeading(title, subtitle, back)
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp), content = content,
            )
        }
    }
}

@Composable
internal fun CockpitColumns(
    first: @Composable ColumnScope.() -> Unit,
    second: @Composable ColumnScope.() -> Unit,
    firstWeight: Float = 1f,
    minimumWidth: Dp = 730.dp,
) {
    val spacing = Arrangement.spacedBy(14.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= minimumWidth) {
            Row(horizontalArrangement = spacing) {
                Column(Modifier.weight(firstWeight), verticalArrangement = spacing, content = first)
                Column(Modifier.weight(1f), verticalArrangement = spacing, content = second)
            }
        } else {
            Column(verticalArrangement = spacing) {
                first()
                second()
            }
        }
    }
}

@Composable
internal fun CockpitSection(
    title: String,
    icon: CockpitIcon,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceVariant) {
                    CockpitGlyph(icon, Modifier.padding(9.dp).size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    subtitle?.let { Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            content()
        }
    }
}

@Composable
internal fun CockpitAction(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val target = modifier.heightIn(min = 50.dp)
    if (primary) Button(onClick, target, enabled, shape = MaterialTheme.shapes.medium) { Text(title) }
    else OutlinedButton(onClick, target, enabled, shape = MaterialTheme.shapes.medium) { Text(title) }
}

@Composable
internal fun CockpitFeedback(message: String?, error: Boolean = false) {
    AnimatedContent(message, transitionSpec = { fadeIn(tween(160)).togetherWith(fadeOut(tween(90))) },
        label = "operation status") { status ->
        if (!status.isNullOrBlank()) {
            Text(status, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
