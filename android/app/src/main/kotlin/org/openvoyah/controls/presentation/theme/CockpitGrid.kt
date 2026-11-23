package org.openvoyah.controls.presentation.theme

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Marks a control that may share a row of a [CockpitGrid] with others. The grid asks its intrinsic width, so only
 * controls whose content can answer that (rows of text, switches and icons) carry the mark; anything else, such as a
 * hint paragraph, a slider or an animated group, keeps a row of its own.
 */
internal fun Modifier.cockpitCell(): Modifier = this.then(CockpitCellData)

private object CockpitCellData : ParentDataModifier {
    // A weight or alignment from a Row or Column around the control wins: the mark only matters directly in a grid.
    override fun Density.modifyParentData(parentData: Any?): Any = parentData ?: CockpitCellData
}

/**
 * The content of a [CockpitGrid] keeps the column scope of a section, so existing section bodies compile unchanged;
 * the column-only modifiers have no meaning in a grid and leave the modifier as it is.
 */
@LayoutScopeMarker
@Immutable
private object CockpitGridScope : ColumnScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier = this
    override fun Modifier.align(alignment: Alignment.Horizontal): Modifier = this
    override fun Modifier.alignBy(alignmentLine: VerticalAlignmentLine): Modifier = this
    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = this
}

/** How many grid columns of at least [cell] fit into [width] with [gap] between them, and how many one item needs. */
internal object CockpitGridMath {
    fun columns(width: Int, cell: Int, gap: Int): Int = max(1, (width + gap) / max(1, cell + gap))

    fun span(wanted: Int, columnWidth: Int, gap: Int, columns: Int): Int {
        var span = 1
        while (span < columns && span * columnWidth + (span - 1) * gap < wanted) span++
        return span
    }

    /** Fills rows in order; an item that does not fit the rest of a row starts the next one. */
    fun rows(spans: List<Int>, columns: Int): List<List<Int>> {
        val rows = mutableListOf<MutableList<Int>>()
        var used = columns
        spans.forEachIndexed { index, span ->
            if (used + span > columns) { rows += mutableListOf<Int>(); used = 0 }
            rows.last() += index
            used += span
        }
        return rows
    }
}

/**
 * Lays settings out across the width of the head unit: each marked control takes as many columns as its content
 * needs, so two, three or more short ones share a row and a long one spans the width. Controls in one row share the
 * tallest one's height, so their outlines line up. A narrow window, a large text size or a lowered screen simply gets
 * fewer columns, down to one.
 */
@Composable
internal fun CockpitGrid(
    modifier: Modifier = Modifier,
    spacing: Dp = 11.dp,
    minimumCell: Dp = 240.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val layout = LocalCockpitLayout.current
    val cell = layout.columnsMinimum(minimumCell, LocalDensity.current.fontScale)
    Layout(content = { CockpitGridScope.content() }, modifier = modifier) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val gap = spacing.roundToPx()
        val columns = if (constraints.hasBoundedWidth) CockpitGridMath.columns(width, cell.roundToPx(), gap) else 1
        val columnWidth = (width - gap * (columns - 1)) / columns
        val cells = measurables.map { it.parentData === CockpitCellData && columns > 1 }
        val spans = measurables.mapIndexed { index, measurable ->
            if (!cells[index]) columns
            else CockpitGridMath.span(measurable.maxIntrinsicWidth(Constraints.Infinity), columnWidth, gap, columns)
        }
        val rows = CockpitGridMath.rows(spans, columns)
        val placeables = arrayOfNulls<androidx.compose.ui.layout.Placeable>(measurables.size)
        val tops = IntArray(measurables.size)
        val lefts = IntArray(measurables.size)
        var y = 0
        rows.forEach { row ->
            val widths = row.associateWith { index -> min(width, spans[index] * columnWidth + (spans[index] - 1) * gap) }
            val height = row.filter { cells[it] }.maxOfOrNull { measurables[it].minIntrinsicHeight(widths.getValue(it)) } ?: 0
            var x = 0
            var rowHeight = 0
            row.forEach { index ->
                val itemWidth = widths.getValue(index)
                val placeable = measurables[index].measure(
                    Constraints(itemWidth, itemWidth, if (cells[index]) height else 0, Constraints.Infinity))
                placeables[index] = placeable
                lefts[index] = x
                x += itemWidth + gap
                rowHeight = max(rowHeight, placeable.height)
            }
            // A collapsed group (a hidden reveal) takes no row and no gap.
            if (rowHeight > 0 && y > 0) y += gap
            row.forEach { index -> tops[index] = y }
            y += rowHeight
        }
        layout(width, max(y, constraints.minHeight)) {
            placeables.forEachIndexed { index, placeable -> placeable?.placeRelative(lefts[index], tops[index]) }
        }
    }
}
