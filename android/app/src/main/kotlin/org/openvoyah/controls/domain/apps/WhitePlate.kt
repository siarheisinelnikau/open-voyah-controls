package org.openvoyah.controls.domain.apps

/**
 * Removes the white plate an app icon draws behind its mark: the near-white area that reaches the edges of
 * the picture, together with the transparent corners around it. Colours inside the mark stay, even white
 * ones, as long as the mark encloses them. The fringe where the mark meets the plate is taken back from its
 * blend with white, so no light halo remains on a dark panel.
 *
 * Pixels are unpremultiplied ARGB, as Android's Bitmap.getPixels returns them.
 */
object WhitePlate {
    /** At least this share of the picture must be opaque plate, or there is no plate to remove. */
    private const val MIN_PLATE_SHARE = 0.08
    private const val TRANSPARENT = 16
    private const val PLATE_LIGHTNESS = 232
    private const val PLATE_TINT = 24
    /** Edge pixels at least this light are a blend of the mark with the plate. */
    private const val FRINGE_LIGHTNESS = 150

    /** Returns the picture without its plate, or null when it has none. */
    fun strip(pixels: IntArray, width: Int, height: Int): IntArray? {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val plate = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var head = 0
        var tail = 0
        fun visit(index: Int) {
            if (!plate[index] && background(pixels[index])) {
                plate[index] = true
                queue[tail++] = index
            }
        }
        for (x in 0 until width) { visit(x); visit((height - 1) * width + x) }
        for (y in 0 until height) { visit(y * width); visit(y * width + width - 1) }
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            if (x > 0) visit(index - 1)
            if (x < width - 1) visit(index + 1)
            if (y > 0) visit(index - width)
            if (y < height - 1) visit(index + width)
        }
        var opaquePlate = 0
        for (index in pixels.indices) if (plate[index] && alpha(pixels[index]) >= TRANSPARENT) opaquePlate++
        if (opaquePlate < pixels.size * MIN_PLATE_SHARE) return null
        val result = pixels.copyOf()
        for (index in pixels.indices) {
            if (plate[index]) { result[index] = 0; continue }
            val x = index % width
            val y = index / width
            val touchesPlate = (x > 0 && plate[index - 1]) || (x < width - 1 && plate[index + 1]) ||
                (y > 0 && plate[index - width]) || (y < height - 1 && plate[index + width])
            if (touchesPlate) result[index] = unblend(pixels[index])
        }
        // Nothing of the mark left means the whole picture was plate; keep the original then.
        return if (result.any { alpha(it) >= TRANSPARENT }) result else null
    }

    /** The smallest rectangle holding every visible pixel, as [left, top, right, bottom) or null when empty. */
    fun bounds(pixels: IntArray, width: Int, height: Int): IntArray? {
        var left = width; var top = height; var right = -1; var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (alpha(pixels[y * width + x]) < TRANSPARENT) continue
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        return if (right < 0) null else intArrayOf(left, top, right + 1, bottom + 1)
    }

    private fun background(pixel: Int): Boolean {
        if (alpha(pixel) < TRANSPARENT) return true
        val r = pixel shr 16 and 0xff
        val g = pixel shr 8 and 0xff
        val b = pixel and 0xff
        val low = minOf(r, g, b)
        return low >= PLATE_LIGHTNESS && maxOf(r, g, b) - low <= PLATE_TINT
    }

    private fun unblend(pixel: Int): Int {
        val r = pixel shr 16 and 0xff
        val g = pixel shr 8 and 0xff
        val b = pixel and 0xff
        val low = minOf(r, g, b)
        if (low < FRINGE_LIGHTNESS) return pixel
        // A light edge pixel is the mark's colour partly covered by white: its darkest channel tells how much.
        val share = (255 - low) / 255.0
        if (share <= 0.0) return 0
        fun channel(value: Int) = (255 - (255 - value) / share).toInt().coerceIn(0, 255)
        val a = (alpha(pixel) * share).toInt().coerceIn(0, 255)
        return (a shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    private fun alpha(pixel: Int) = pixel ushr 24
}
