package org.openvoyah.controls.domain.display

import org.openvoyah.controls.domain.display.WindowStripState.Phase

/**
 * How far an OVC screen keeps its content right of the OEM launcher's left panel on the central display.
 *
 * The panel is a launcher system window drawn over apps; Android does not tell apps about it. So every screen keeps
 * a gap as wide as the panel unless the strip is positively known to be clear already:
 * - Android itself reports it: the content already starts right of the panel on screen (a window moved beside it),
 *   or the window's left system inset covers it. The gap is then not added on top, so it never doubles.
 * - The window agent confirms the reserved strip (`insets` receipt `verified`, [Phase.ACTIVE]).
 * No agent, an agent that is not ready, any other receipt, or no answer yet keeps the gap.
 */
internal object LauncherPanelClearance {
    /** Display 0, the central screen, is the only one with the launcher panel. */
    const val CENTRAL_DISPLAY_ID = 0

    /**
     * Width of the panel window (`MainScreenNavigationBar`, gravity LEFT, `145xfill`) on the central display of
     * the supported head unit, read from `dumpsys window` at its native 160 dpi. Used when the window agent has
     * not measured the panel itself.
     */
    const val FALLBACK_PANEL_PX = 145
    const val FALLBACK_PANEL_DPI = 160

    /** The panel width in physical pixels for a window on [displayId]; 0 where there is no panel. */
    fun panelWidthPx(displayId: Int, displayDensityDpi: Int, strip: WindowStripState?): Int {
        if (displayId != CENTRAL_DISPLAY_ID) return 0
        val measured = strip?.takeIf { it.phase in MEASURED }?.widthPx ?: 0
        if (measured > 0) return measured
        val dpi = if (displayDensityDpi > 0) displayDensityDpi else FALLBACK_PANEL_DPI
        return (FALLBACK_PANEL_PX * dpi + FALLBACK_PANEL_DPI / 2) / FALLBACK_PANEL_DPI
    }

    /** Only a verified receipt that names a width confirms that Android keeps the strip clear. */
    fun stripConfirmed(strip: WindowStripState?): Boolean = strip != null && strip.phase == Phase.ACTIVE && strip.widthPx > 0

    /**
     * The left padding, in pixels, that the screen root applies. It also stands for the window's own left system
     * inset, which the root consumes, so screens that pad for system insets below it do not add it again.
     *
     * @param contentLeftPx where the screen's root starts on the physical screen.
     * @param systemLeftInsetPx the window's left safe-drawing inset reported by Android.
     */
    fun leftPaddingPx(panelPx: Int, contentLeftPx: Int, systemLeftInsetPx: Int, confirmed: Boolean): Int {
        val overlap = if (confirmed) 0 else (panelPx - contentLeftPx).coerceIn(0, panelPx.coerceAtLeast(0))
        return maxOf(overlap, systemLeftInsetPx.coerceAtLeast(0))
    }

    /** Phases whose receipt carries the panel width the agent measured on this head unit. */
    private val MEASURED = setOf(Phase.STARTING, Phase.CHECKING, Phase.ACTIVE)
}
