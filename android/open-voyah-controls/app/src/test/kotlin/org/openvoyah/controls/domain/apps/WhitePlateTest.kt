package org.openvoyah.controls.domain.apps

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitePlateTest {
    private val white = 0xffffffff.toInt()
    private val yellow = 0xffffcc00.toInt()
    private val clear = 0

    /** A 10x10 picture: transparent corners, a white plate and a 4x4 yellow mark in the middle. */
    private fun plated(center: (Int, Int) -> Int = { _, _ -> yellow }) = IntArray(100) { index ->
        val x = index % 10
        val y = index / 10
        when {
            (x == 0 || x == 9) && (y == 0 || y == 9) -> clear
            x in 3..6 && y in 3..6 -> center(x, y)
            else -> white
        }
    }

    @Test fun removesThePlateAndKeepsTheMark() {
        val result = WhitePlate.strip(plated(), 10, 10)!!
        for (index in result.indices) {
            val x = index % 10
            val y = index / 10
            assertEquals("$x,$y", if (x in 3..6 && y in 3..6) yellow else clear, result[index])
        }
        assertArrayEquals(intArrayOf(3, 3, 7, 7), WhitePlate.bounds(result, 10, 10))
    }

    @Test fun whiteInsideTheMarkStays() {
        val result = WhitePlate.strip(plated { x, y -> if (x in 4..5 && y in 4..5) white else yellow }, 10, 10)!!
        assertEquals(white, result[4 * 10 + 4])
        assertEquals(clear, result[1 * 10 + 1])
    }

    @Test fun aLightFringeLosesItsWhiteBlend() {
        val pale = 0xfffff0b0.toInt() // yellow, about one third, over white
        val result = WhitePlate.strip(plated { x, y -> if (x == 3 && y == 4) pale else yellow }, 10, 10)!!
        val fringe = result[4 * 10 + 3]
        assertEquals(0xff, fringe shr 16 and 0xff)
        assertTrue("alpha ${fringe ushr 24}", (fringe ushr 24) in 60..100)
        assertTrue("blue ${fringe and 0xff}", (fringe and 0xff) < 0x20)
    }

    @Test fun aPictureWithoutAPlateIsLeftAlone() {
        assertNull(WhitePlate.strip(IntArray(100) { yellow }, 10, 10))
        assertNull(WhitePlate.strip(IntArray(100) { if (it % 10 in 4..5) yellow else clear }, 10, 10))
        // All plate and no mark: nothing would remain.
        assertNull(WhitePlate.strip(IntArray(100) { white }, 10, 10))
        assertNull(WhitePlate.bounds(IntArray(4), 2, 2))
    }
}
