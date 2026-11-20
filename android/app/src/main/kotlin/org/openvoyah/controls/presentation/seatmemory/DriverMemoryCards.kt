package org.openvoyah.controls.presentation.seatmemory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.seatmemory.SeatMemoryAction
import org.openvoyah.controls.domain.seatmemory.SeatMemorySlot
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout

@Composable
internal fun DriverMemoryCards(enabled: Boolean, action: (SeatMemorySlot, SeatMemoryAction) -> Unit) {
    val slots = SeatMemorySlot.entries
    val seat = ImageBitmap.imageResource(R.drawable.driver_seat_three_quarter)
    val layout = LocalCockpitLayout.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val titleStyle = MaterialTheme.typography.titleLarge
    val buttonStyle = MaterialTheme.typography.labelLarge.let { it.copy(fontSize = it.fontSize * (14f / 15f)) }
    val gutter = layout.spacing(12.dp)
    val inset = layout.spacing(8.dp)
    val actionGap = layout.spacing(6.dp)
    val actionInset = 4.dp
    val restore = UiText.get(R.string.seat_memory_recall)
    val save = UiText.get(R.string.seat_memory_save_position)
    fun textWidth(text: String, style: TextStyle): Dp =
        with(density) { measurer.measure(text, style = style, maxLines = 1).size.width.toDp() }
    val restoreWidth = maxOf(50.dp, textWidth(restore, buttonStyle) + actionInset * 2 + 1.dp)
    val saveWidth = maxOf(50.dp, textWidth(save, buttonStyle) + actionInset * 2 + 1.dp)
    val titleWidth = slots.maxOf { textWidth(driverTitle(it), titleStyle) }
    val minimumContentWidth = maxOf(140.dp, titleWidth, restoreWidth, saveWidth)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnWidth = (maxWidth - gutter * (slots.size - 1)) / slots.size
        val inColumns = columnWidth - inset * 2 >= minimumContentWidth
        val thumbnail = (maxWidth * 0.25f).coerceIn(64.dp, 112.dp)
        val leadingArt = thumbnail.takeIf { !inColumns && maxWidth - it - inset * 3 >= minimumContentWidth }
        val cardWidth = if (inColumns) columnWidth else maxWidth
        val contentWidth = cardWidth - inset * 2 - (leadingArt?.plus(inset) ?: 0.dp)
        val pairedWidth = contentWidth - actionGap
        val extraWidth = pairedWidth - restoreWidth - saveWidth
        val restoreShare = if (extraWidth >= 0.dp) (restoreWidth + extraWidth / 2) / pairedWidth else null
        val artHeight = (cardWidth * 0.79f).coerceIn(150.dp, 220.dp)
        val actionPadding = PaddingValues(horizontal = actionInset, vertical = 8.dp)

        val card: @Composable (SeatMemorySlot, Modifier) -> Unit = { slot, modifier ->
            DriverMemoryCard(driverTitle(slot), seat, artHeight, leadingArt, inset, modifier) {
                DriverMemoryActions(slot, enabled, buttonStyle, actionPadding, actionGap, restoreShare, action)
            }
        }
        if (inColumns) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gutter)) {
                for (slot in slots) card(slot, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(gutter)) {
                for (slot in slots) card(slot, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DriverMemoryCard(
    title: String,
    seat: ImageBitmap,
    artHeight: Dp,
    leadingArt: Dp?,
    inset: Dp,
    modifier: Modifier,
    buttons: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val titleStyle = MaterialTheme.typography.titleLarge
    Surface(
        modifier.semantics { isTraversalGroup = true },
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        if (leadingArt == null) {
            Column(Modifier.padding(inset), verticalArrangement = Arrangement.spacedBy(inset)) {
                Text(
                    title, Modifier.fillMaxWidth().padding(top = 4.dp).semantics { heading() },
                    style = titleStyle, textAlign = TextAlign.Center,
                )
                DriverSeatPreview(seat, title, Modifier.fillMaxWidth().height(artHeight))
                buttons()
            }
        } else {
            Row(
                Modifier.padding(inset),
                horizontalArrangement = Arrangement.spacedBy(inset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DriverSeatPreview(seat, title, Modifier.size(leadingArt))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(inset)) {
                    Text(title, Modifier.semantics { heading() }, style = titleStyle)
                    buttons()
                }
            }
        }
    }
}

@Composable
private fun DriverSeatPreview(seat: ImageBitmap, title: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val surface = colors.surfaceContainerHigh
    val highlight = lerp(surface, colors.primary, 0.17f)
    Box(
        modifier.clip(MaterialTheme.shapes.medium)
            .drawWithCache {
                val backdrop = Brush.radialGradient(
                    colors = listOf(highlight, surface),
                    center = Offset(size.width * 0.53f, size.height * 0.43f),
                    radius = size.minDimension * 0.78f,
                )
                onDrawBehind { drawRect(backdrop) }
            }
            .padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = seat,
            contentDescription = seatMemoryText(R.string.seat_memory_driver_seat, title),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun DriverMemoryActions(
    slot: SeatMemorySlot,
    enabled: Boolean,
    labelStyle: TextStyle,
    padding: PaddingValues,
    gap: Dp,
    restoreShare: Float?,
    action: (SeatMemorySlot, SeatMemoryAction) -> Unit,
) {
    val title = driverTitle(slot)
    val colors = MaterialTheme.colorScheme
    val buttons: @Composable (Modifier, Modifier) -> Unit = { restore, save ->
        CockpitAction(
            UiText.get(R.string.seat_memory_recall), { action(slot, SeatMemoryAction.RESTORE) },
            restore.semantics { contentDescription = seatMemoryText(R.string.seat_memory_restore_driver, title) },
            enabled = enabled, primary = true,
            contentPadding = padding, labelStyle = labelStyle,
            colors = ButtonDefaults.buttonColors(
                containerColor = lerp(colors.primaryContainer, colors.primary, 0.18f),
                contentColor = colors.onPrimaryContainer,
            ),
        )
        CockpitAction(
            UiText.get(R.string.seat_memory_save_position), { action(slot, SeatMemoryAction.SAVE) },
            save.semantics { contentDescription = seatMemoryText(R.string.seat_memory_save_driver, title) },
            enabled = enabled,
            contentPadding = padding, labelStyle = labelStyle,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = colors.surfaceContainerHigh,
                contentColor = colors.onSurface,
            ),
        )
    }
    if (restoreShare != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            buttons(Modifier.weight(restoreShare), Modifier.weight(1f - restoreShare))
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            buttons(Modifier.fillMaxWidth(), Modifier.fillMaxWidth())
        }
    }
}
