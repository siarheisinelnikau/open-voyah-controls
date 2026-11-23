package org.openvoyah.controls.domain.display

/**
 * The density at which Controls draws its own windows by default.
 *
 * Every screen is laid out for a canvas of at least [CANVAS_LONG_DP] × [CANVAS_SHORT_DP] dp: the 1920 × 720 central
 * display at 240 dpi, where the screens are previewed and tested. The supported head unit runs that display at
 * 160 dpi, which makes the same layouts two thirds of their intended size. Controls therefore draws at the density
 * that maps the display onto that canvas, and never below the density Android gives it.
 *
 * Only Controls' own windows change: the display and every other app keep their density. A display that already
 * fits the canvas, such as the 240 dpi emulator, keeps its own density.
 */
internal object ControlsDensity {
    const val CANVAS_LONG_DP = 1280
    const val CANVAS_SHORT_DP = 480
    private const val BASELINE_DPI = 160

    /**
     * @param displayDpi the density Android gives the window.
     * @param widthPx width of the whole display, not of the window, so every Controls window on it gets one density.
     * @param heightPx height of the whole display.
     */
    fun dpi(displayDpi: Int, widthPx: Int, heightPx: Int): Int {
        if (displayDpi <= 0 || widthPx <= 0 || heightPx <= 0) return displayDpi
        val canvasDpi = minOf(
            maxOf(widthPx, heightPx).toLong() * BASELINE_DPI / CANVAS_LONG_DP,
            minOf(widthPx, heightPx).toLong() * BASELINE_DPI / CANVAS_SHORT_DP,
        ).toInt()
        return maxOf(displayDpi, canvasDpi)
    }

    /** A length in dp at [fromDpi] expressed in dp at [toDpi], rounded down as Android rounds screen sizes. */
    fun rescaleDp(dp: Int, fromDpi: Int, toDpi: Int): Int =
        if (dp <= 0 || fromDpi <= 0 || toDpi <= 0) dp else (dp.toLong() * fromDpi / toDpi).toInt()
}
