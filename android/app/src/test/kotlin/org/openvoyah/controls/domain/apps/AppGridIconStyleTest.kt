package org.openvoyah.controls.domain.apps

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGridIconStyleTest {
    /** Applies a 4x5 matrix as Android does, clamped to 0..255. */
    private fun apply(matrix: FloatArray, r: Int, g: Int, b: Int, a: Int = 255): IntArray = IntArray(4) { row ->
        val value = matrix[row * 5] * r + matrix[row * 5 + 1] * g + matrix[row * 5 + 2] * b + matrix[row * 5 + 3] * a +
            matrix[row * 5 + 4]
        value.toInt().coerceIn(0, 255)
    }

    private val monochrome = requireNotNull(AppGridIconStyle.MONOCHROME.colorMatrix)

    @Test fun nativeIconsStayAsTheyAre() = assertNull(AppGridIconStyle.NATIVE.colorMatrix)

    @Test fun lightIconsTurnBlueWhiteAndShadowsSlateBlue() {
        val white = apply(monochrome, 255, 255, 255)
        assertTrue(white.contentToString(), white[0] >= 240 && white[1] >= 245 && white[2] == 255)
        assertArrayEquals(intArrayOf(40, 58, 108, 255), apply(monochrome, 0, 0, 0))
    }

    @Test fun middleTonesArePaleBlueAndHueIsGone() {
        // The red of a radio app and a grey of the same lightness end up alike, bluer than they are red.
        val red = apply(monochrome, 237, 85, 101)
        val grey = apply(monochrome, 118, 118, 118)
        for (channel in 0..2) assertTrue("${red.contentToString()} ${grey.contentToString()}", kotlin.math.abs(red[channel] - grey[channel]) <= 2)
        assertTrue(red.contentToString(), red[2] > red[1] && red[1] > red[0])
    }

    @Test fun transparencyIsKept() {
        assertEquals(0, apply(monochrome, 255, 255, 255, a = 0)[3])
        assertEquals(128, apply(monochrome, 10, 200, 30, a = 128)[3])
    }

    @Test fun eachCallGivesItsOwnCopy() {
        val first = requireNotNull(AppGridIconStyle.MONOCHROME.colorMatrix)
        first[0] = 99f
        assertTrue(AppGridIconStyle.MONOCHROME.colorMatrix!![0] < 1f)
    }
}
