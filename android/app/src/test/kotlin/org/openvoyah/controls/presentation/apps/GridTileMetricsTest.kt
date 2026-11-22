package org.openvoyah.controls.presentation.apps

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openvoyah.controls.domain.apps.AppGridRows

class GridTileMetricsTest {
    private val density = Density(1f)
    private val label = TextStyle(lineHeight = 24.sp)
    // The large tile: 16 + 80 + 12 + 2 x 24 + 16 = 172 dp of content.
    private val large = GridTileMetrics(168.dp, 160.dp, 80.dp, 16.dp, 12.dp, labelScale = 19f / 17f, fillsHeight = true)

    @Test fun theNormalLayoutIsUnchangedByTheLoweredOption() {
        for (height in listOf(300.dp, 420.dp, 600.dp)) {
            assertEquals(large.pageLayout(height, 12.dp, label, true, density),
                large.pageLayout(height, 12.dp, label, true, density, minimumRows = 1))
        }
        val content = large.contentHeight(label, true, density)
        assertEquals(172f, content.value, 0.01f)
        assertEquals(large.copy(height = 200.dp), large.fittedTo(200.dp, content))
        assertEquals(large.copy(height = 172.dp), large.fittedTo(172.dp, content))
    }

    @Test fun aLoweredPageKeepsTwoRowsThatShareItsHeight() {
        // The visible band leaves about 300 dp for the grid: one large row normally, two shared rows when lowered.
        val one = large.pageLayout(300.dp, 12.dp, label, true, density)
        assertEquals(1, one.rows)
        val two = large.pageLayout(300.dp, 12.dp, label, true, density, minimumRows = 2)
        assertEquals(2, two.rows)
        assertEquals(144f, two.rowHeight.value, 0.01f)
        assertEquals(144, AppGridRows.sharedHeight(300, 2, 12))
        // Pages tall enough for two rows keep their own layout.
        assertEquals(large.pageLayout(420.dp, 12.dp, label, true, density),
            large.pageLayout(420.dp, 12.dp, label, true, density, minimumRows = 2))
    }

    @Test fun aLowRowScalesTheIconSpacingAndCaptionTogether() {
        val content = large.contentHeight(label, true, density)
        val fitted = large.fittedTo(129.dp, content)
        val scale = 129f / 172f
        assertEquals(129.dp, fitted.height)
        assertEquals(80f * scale, fitted.iconSize.value, 0.01f)
        assertEquals(16f * scale, fitted.verticalPadding.value, 0.01f)
        assertEquals(12f * scale, fitted.labelSpacing.value, 0.01f)
        assertEquals(19f / 17f * scale, fitted.labelScale, 0.0001f)
        assertEquals(large.cellWidth, fitted.cellWidth)
    }

    @Test fun sharedRowsNeverCollapse() {
        assertEquals(1, AppGridRows.sharedHeight(0, 2, 12))
        assertEquals(50, AppGridRows.sharedHeight(100, 2, 0))
    }
}
