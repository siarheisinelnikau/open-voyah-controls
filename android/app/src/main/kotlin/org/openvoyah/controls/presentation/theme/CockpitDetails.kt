package org.openvoyah.controls.presentation.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun CockpitDetail(
    title: String,
    subtitle: String?,
    back: () -> Unit,
    horizontalPadding: Dp = 22.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val layout = LocalCockpitLayout.current
    val spacing = Arrangement.spacedBy(layout.spacing(14.dp))
    CockpitBackdrop {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = layout.spacing(horizontalPadding), vertical = layout.spacing(14.dp)),
            verticalArrangement = spacing,
        ) {
            CockpitHeading(title, subtitle, back)
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = layout.spacing(10.dp)),
                verticalArrangement = spacing, content = content,
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
    val layout = LocalCockpitLayout.current
    val spacing = Arrangement.spacedBy(layout.spacing(14.dp))
    val wideEnough = layout.columnsMinimum(minimumWidth, LocalDensity.current.fontScale)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= wideEnough) {
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
    val layout = LocalCockpitLayout.current
    Surface(
        Modifier.fillMaxWidth().animateContentSize(tween(CockpitMotion.CHANGE)), shape = MaterialTheme.shapes.large, color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(layout.spacing(16.dp)), verticalArrangement = Arrangement.spacedBy(layout.spacing(11.dp))) {
            Row(horizontalArrangement = Arrangement.spacedBy(layout.spacing(12.dp)), verticalAlignment = Alignment.CenterVertically) {
                CockpitGlyph(icon, Modifier.size(24.dp))
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
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    labelStyle: TextStyle = MaterialTheme.typography.labelLarge,
    colors: ButtonColors? = null,
) {
    val press = rememberPressMotion(enabled)
    val target = modifier.then(press.modifier).heightIn(min = 50.dp)
    val label: @Composable RowScope.() -> Unit = { Text(title, style = labelStyle) }
    if (primary) {
        Button(onClick, target, enabled, shape = MaterialTheme.shapes.medium, interactionSource = press.source,
            colors = colors ?: ButtonDefaults.buttonColors(), contentPadding = contentPadding, content = label)
    } else {
        OutlinedButton(onClick, target, enabled, shape = MaterialTheme.shapes.medium, interactionSource = press.source,
            colors = colors ?: ButtonDefaults.outlinedButtonColors(), contentPadding = contentPadding, content = label)
    }
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
