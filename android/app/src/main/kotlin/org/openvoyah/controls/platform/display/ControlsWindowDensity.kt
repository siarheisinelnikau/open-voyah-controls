package org.openvoyah.controls.platform.display

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager
import org.openvoyah.controls.domain.display.ControlsDensity

/**
 * Call from [Activity.attachBaseContext] right after `super`: the activity then draws at [ControlsDensity.dpi]. Its
 * dialogs and popups inherit the density from the activity's resources, and so does the media preview on the
 * Displays page when its own density is left at the device default.
 */
internal fun Activity.useControlsDensity(base: Context) {
    base.controlsDensityOverride()?.let(::applyOverrideConfiguration)
}

/**
 * The override that raises the density, or null when Android's density already fits the canvas. Screen sizes in dp
 * are rescaled with it, so `screenWidthDp` and its siblings describe the same window at the new density. Android
 * recreates the activity on a size or density change, which computes the override again.
 */
internal fun Context.controlsDensityOverride(): Configuration? {
    val current = resources.configuration
    val metrics = realMetrics() ?: return null
    val dpi = ControlsDensity.dpi(current.densityDpi, metrics.widthPixels, metrics.heightPixels)
    if (dpi == current.densityDpi) return null
    val from = current.densityDpi
    return Configuration().apply {
        densityDpi = dpi
        screenWidthDp = ControlsDensity.rescaleDp(current.screenWidthDp, from, dpi)
        screenHeightDp = ControlsDensity.rescaleDp(current.screenHeightDp, from, dpi)
        smallestScreenWidthDp = ControlsDensity.rescaleDp(current.smallestScreenWidthDp, from, dpi)
    }
}

/**
 * The density Controls' windows draw at on this context's display, in pixels per dp. Bitmaps prepared outside an
 * activity, such as app icons loaded with the application context, use it to match what the screens draw.
 */
internal fun Context.controlsDensity(): Float {
    val metrics = realMetrics() ?: return resources.displayMetrics.density
    return ControlsDensity.dpi(resources.configuration.densityDpi, metrics.widthPixels, metrics.heightPixels) /
        DisplayMetrics.DENSITY_DEFAULT.toFloat()
}

@Suppress("DEPRECATION") // Context.getDisplay() needs API 30; the activity's window manager names its display on 28.
private fun Context.realMetrics(): DisplayMetrics? {
    val display = (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay ?: return null
    return DisplayMetrics().also(display::getRealMetrics)
}
