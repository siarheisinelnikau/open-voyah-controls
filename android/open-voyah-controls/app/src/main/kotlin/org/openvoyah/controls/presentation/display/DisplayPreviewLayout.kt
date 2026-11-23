package org.openvoyah.controls.presentation.display

import org.openvoyah.controls.domain.display.DisplayPreviewArrangement
import org.openvoyah.controls.domain.display.DisplayPreviewPaneBalance

internal const val PREVIEW_PADDING_DP = 16
internal const val PREVIEW_COLUMN_GAP_DP = 20
internal const val PREVIEW_ROW_GAP_DP = 12
internal const val PREVIEW_ARTWORK_DP = 72
internal const val PREVIEW_ARTWORK_GAP_DP = 12
internal const val PREVIEW_STANDARD_TITLE_SP = 21f

internal fun DisplayPreviewArrangement.usesSideBySide(availableWidth: Float, textScale: Float, artwork: Boolean): Boolean {
    if (this == DisplayPreviewArrangement.STACKED) return false
    val scale = textScale.coerceAtLeast(1f)
    val minimumWidth = 2 * minimumPaneWidth(scale, artwork) + PREVIEW_COLUMN_GAP_DP
    val automaticWidth = if (this == DisplayPreviewArrangement.AUTO) 420 * scale else 0f
    return availableWidth >= maxOf(minimumWidth, automaticWidth)
}

internal fun DisplayPreviewPaneBalance.mediaShare(availableWidth: Float, textScale: Float, artwork: Boolean): Float {
    if (this == DisplayPreviewPaneBalance.EQUAL) return .5f
    val paneSpace = (availableWidth - PREVIEW_COLUMN_GAP_DP).coerceAtLeast(1f)
    val minimumShare = (minimumPaneWidth(textScale.coerceAtLeast(1f), artwork) / paneSpace).coerceIn(.4f, .5f)
    return if (this == DisplayPreviewPaneBalance.MEDIA) 1f - minimumShare else minimumShare
}

private fun minimumPaneWidth(scale: Float, artwork: Boolean): Float =
    if (artwork) 112 * scale + PREVIEW_ARTWORK_DP + PREVIEW_ARTWORK_GAP_DP else 152 * scale

internal fun previewPaneHeight(sideBySide: Boolean, textScale: Float, showsProgress: Boolean, bothPanes: Boolean = true): Float {
    // The tallest media pane has a title, up to three lines, or two lines with progress and a time label.
    val media = if (showsProgress) 118 else 110
    val stacked = bothPanes && !sideBySide
    val textHeight = if (stacked) media + 110 else media
    val gap = if (stacked) PREVIEW_ROW_GAP_DP else 0
    return textHeight * textScale.coerceAtLeast(1f) + gap + 2 * PREVIEW_PADDING_DP
}
