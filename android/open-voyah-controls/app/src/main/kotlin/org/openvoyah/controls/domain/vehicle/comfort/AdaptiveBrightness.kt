package org.openvoyah.controls.domain.vehicle.comfort

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

internal data class AdaptiveBrightnessSettings(
    val enabled: Boolean = false,
    val lowPercent: Int = 25,
    val highPercent: Int = 85,
) {
    init {
        require(lowPercent in LOW_LEVELS && highPercent in HIGH_LEVELS)
    }

    companion object {
        val LOW_LEVELS = listOf(15, 25, 35, 45)
        val HIGH_LEVELS = listOf(55, 70, 85, 100)
    }
}

/** Log-scale interpolation holds each target until the desired level moves outside a small hysteresis band. */
internal class AdaptiveBrightnessCurve(private val settings: AdaptiveBrightnessSettings) {
    private var current: Int? = null

    fun level(lux: Float): Int? {
        if (!lux.isFinite() || lux < 0) {
            current = null
            return null
        }
        val previous = current
        val light = lux.toDouble().coerceIn(DARK_LUX, BRIGHT_LUX)
        val fraction = ln(light / DARK_LUX) / ln(BRIGHT_LUX / DARK_LUX)
        val desired = (settings.lowPercent + (settings.highPercent - settings.lowPercent) * fraction).roundToInt()
        if (previous == null || abs(desired - previous) >= HYSTERESIS ||
            desired == settings.lowPercent || desired == settings.highPercent) current = desired
        return current
    }

    companion object {
        private const val DARK_LUX = 10.0
        private const val BRIGHT_LUX = 2_000.0
        private const val HYSTERESIS = 5
    }
}
