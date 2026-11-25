package org.openvoyah.controls.presentation.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * True while a page is drawn inside a Settings section instead of on its own: [CockpitDetail] then draws a section
 * heading and its content in the section's own scrolling column, without a backdrop, a Back button or a scroll.
 */
internal val LocalCockpitEmbedded = compositionLocalOf { false }

/** The heading of a page drawn inside a Settings section. */
@Composable
internal fun CockpitEmbeddedHeading(title: String, subtitle: String?) {
    val colors = MaterialTheme.colorScheme
    val layout = LocalCockpitLayout.current
    Column(Modifier.fillMaxWidth().padding(top = layout.spacing(10.dp), start = layout.spacing(4.dp))) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = colors.primary)
        if (!subtitle.isNullOrBlank()) Text(subtitle, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun CockpitDetail(
    title: String,
    subtitle: String?,
    back: () -> Unit,
    horizontalPadding: Dp = 22.dp,
    /** An editor opened from a page, such as a routine's: drawn inside a section it keeps its Back button. */
    nested: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val layout = LocalCockpitLayout.current
    val spacing = Arrangement.spacedBy(layout.spacing(14.dp))
    if (LocalCockpitEmbedded.current) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = spacing) {
            if (nested) CockpitHeading(title, subtitle, back, closes = true) else CockpitEmbeddedHeading(title, subtitle)
            content()
        }
        return
    }
    val cascade = rememberEntranceCascade()
    CockpitBackdrop {
        CompositionLocalProvider(LocalEntranceCascade provides cascade) {
            Column(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = layout.spacing(horizontalPadding), vertical = layout.spacing(14.dp)),
                verticalArrangement = spacing,
            ) {
                CockpitHeading(title, subtitle, back)
                val scroll = rememberScrollState()
                Column(
                    Modifier.fillMaxWidth().weight(1f).cockpitScrollFade(scroll, MaterialTheme.colorScheme.background)
                        .verticalScroll(scroll).padding(bottom = layout.spacing(10.dp)),
                    verticalArrangement = spacing, content = content,
                )
            }
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
    guideId: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val layout = LocalCockpitLayout.current
    val arrival = rememberEntranceOrder()?.let { Modifier.cockpitEntrance(it) } ?: Modifier
    Surface(
        Modifier.fillMaxWidth().then(arrival).animateContentSize(CockpitMotion.change()),
        shape = MaterialTheme.shapes.large, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(layout.spacing(16.dp)), verticalArrangement = Arrangement.spacedBy(layout.spacing(11.dp))) {
            // The glyph stays beside the first line of the title, also when a long subtitle wraps below it.
            Row(horizontalArrangement = Arrangement.spacedBy(layout.spacing(12.dp)), verticalAlignment = Alignment.Top) {
                CockpitGlyph(icon, Modifier.padding(top = 2.dp).size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                SettingInfo(title, guideId)
            }
            CockpitGrid(Modifier.fillMaxWidth(), spacing = layout.spacing(11.dp), content = content)
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
    destructive: Boolean = false,
    /** Every title this action can show; the button keeps the width of the longest, so a new title moves nothing. */
    titles: List<String> = emptyList(),
) {
    val press = rememberPressMotion(enabled)
    val scheme = MaterialTheme.colorScheme
    val target = modifier.then(press.modifier).heightIn(min = CockpitTouch)
    val label: @Composable RowScope.() -> Unit = if (titles.isEmpty()) {
        { Text(title, style = labelStyle) }
    } else {
        {
            // The other titles only take their space: they are not drawn and not read out.
            Box(contentAlignment = Alignment.Center) {
                titles.forEach { other ->
                    if (other != title) Text(other, Modifier.alpha(0f).clearAndSetSemantics {}, style = labelStyle)
                }
                Text(title, style = labelStyle)
            }
        }
    }
    // An action that removes something uses the error roles, so it cannot be mistaken for the safe choice.
    if (primary) {
        val fill = colors ?: if (destructive) {
            ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError)
        } else ButtonDefaults.buttonColors()
        Button(onClick, target, enabled, shape = MaterialTheme.shapes.medium, interactionSource = press.source,
            colors = fill, contentPadding = contentPadding, content = label)
    } else {
        val outline = colors ?: if (destructive) {
            ButtonDefaults.outlinedButtonColors(contentColor = scheme.error)
        } else ButtonDefaults.outlinedButtonColors()
        OutlinedButton(onClick, target, enabled, shape = MaterialTheme.shapes.medium, interactionSource = press.source,
            colors = outline, contentPadding = contentPadding, content = label)
    }
}

/**
 * The text field of every form: the corner style from Appearance instead of the 4 dp Material default, a full
 * touch height and one place for the label, supporting text and error state.
 */
@Composable
internal fun CockpitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: Boolean = false,
    supporting: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
    guideId: Int? = null,
) {
    OutlinedTextField(value, onValueChange, modifier.heightIn(min = CockpitTouch), enabled = enabled,
        label = { Text(label) }, isError = error, singleLine = singleLine, shape = MaterialTheme.shapes.medium,
        supportingText = supporting?.let { message -> { Text(message) } },
        trailingIcon = if (guideId == null) trailingIcon else {
            { Row(verticalAlignment = Alignment.CenterVertically) { trailingIcon?.invoke(); SettingInfo(label, guideId) } }
        },
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions)
}

/**
 * Shows that a scrolling page continues: a short fade in the page color at an edge that still hides content. It
 * is drawn over the content from the scroll position, so it costs no offscreen layer and no recomposition.
 */
internal fun Modifier.cockpitScrollFade(scroll: ScrollState, color: Color, length: Dp = 26.dp): Modifier = drawWithContent {
    drawContent()
    val edge = length.toPx().coerceAtMost(size.height / 3f)
    if (edge <= 0f) return@drawWithContent
    val above = (scroll.value / edge).coerceIn(0f, 1f)
    val below = ((scroll.maxValue - scroll.value) / edge).coerceIn(0f, 1f)
    if (above > 0f) {
        drawRect(Brush.verticalGradient(listOf(color, color.copy(alpha = 0f)), startY = 0f, endY = edge),
            size = Size(size.width, edge), alpha = above)
    }
    if (below > 0f && scroll.maxValue != Int.MAX_VALUE) {
        drawRect(Brush.verticalGradient(listOf(color.copy(alpha = 0f), color), startY = size.height - edge, endY = size.height),
            topLeft = Offset(0f, size.height - edge), size = Size(size.width, edge), alpha = below)
    }
}

@Composable
internal fun CockpitFeedback(message: String?, error: Boolean = false) {
    AnimatedContent(message, transitionSpec = { fadeIn(CockpitMotion.change()).togetherWith(fadeOut(CockpitMotion.exit())) },
        label = "operation status") { status ->
        if (!status.isNullOrBlank()) {
            Text(status, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
