package org.openvoyah.youtube

import android.content.Context
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Lays its children out at 1/[scale] of its own size and draws them [scale] times larger.
 *
 * The WebView takes its pixel ratio from the display and ignores the density a window gets from a per-app DPI
 * profile, so the page would keep the display's scale whatever the profile says. Inside this frame a 1775 px window
 * with a 240 dpi profile on a 160 dpi display becomes a 1183 CSS px page drawn at 1.5x: the layout YouTube would pick
 * on a 240 dpi screen. Touches reach the children through the inverse transform, as for any scaled view.
 */
internal class ScaledFrame(context: Context) : FrameLayout(context) {
    var scale = 1f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val childWidth = MeasureSpec.makeMeasureSpec((width / scale).roundToInt(), MeasureSpec.EXACTLY)
        val childHeight = MeasureSpec.makeMeasureSpec((height / scale).roundToInt(), MeasureSpec.EXACTLY)
        for (index in 0 until childCount) getChildAt(index).measure(childWidth, childHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (index in 0 until childCount) getChildAt(index).apply {
            layout(0, 0, measuredWidth, measuredHeight)
            pivotX = 0f
            pivotY = 0f
            scaleX = scale
            scaleY = scale
        }
    }
}
