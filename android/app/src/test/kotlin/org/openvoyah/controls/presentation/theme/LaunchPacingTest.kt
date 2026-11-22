package org.openvoyah.controls.presentation.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPacingTest {
    private val frame = 16_666_667L
    private val millis = 1_000_000L

    private fun LaunchSettle.feed(gaps: List<Long>, from: Long = 0L): Pair<Boolean, Long> {
        var time = from
        var steady = onFrame(time)
        for (gap in gaps) {
            time += gap
            steady = onFrame(time)
        }
        return steady to time
    }

    @Test fun settleWaitsForARunOfOnTimeFramesAndToleratesOneMissedVsync() {
        val settle = LaunchSettle(frame)
        val (early, time) = settle.feed(List(LaunchSettle.STEADY_FRAMES - 1) { if (it % 5 == 0) 2 * frame else frame })
        assertFalse("one gap short of the run", early)
        assertTrue(settle.onFrame(time + frame))
    }

    @Test fun aStallStartsTheRunAgain() {
        val settle = LaunchSettle(frame)
        var time = settle.feed(List(LaunchSettle.STEADY_FRAMES - 1) { frame }).second
        time += 3 * frame
        assertFalse("two missed vsyncs are a visible stall", settle.onFrame(time))
        repeat(LaunchSettle.STEADY_FRAMES - 1) {
            time += frame
            assertFalse(settle.onFrame(time))
        }
        assertTrue(settle.onFrame(time + frame))
    }

    @Test fun aLateFrameMovesTheIntroOnByTwoFramesAtMost() {
        val clock = LaunchClock(frame, 4_740 * millis)
        assertEquals(0L, clock.onFrame(1_000 * millis))
        assertEquals(frame, clock.onFrame(1_000 * millis + frame))
        val afterStall = clock.onFrame(1_000 * millis + frame + 200 * millis)
        assertEquals(3 * frame, afterStall)
        assertEquals(3, clock.frames)
        assertEquals(1, clock.slowFrames)
        assertEquals(200 * millis, clock.longestGapNanos)
        assertEquals(200 * millis + frame, clock.wallNanos)
    }

    @Test fun aDeviceThatStaysSlowStillFinishesWithinOneAndAHalfTimesThePlan() {
        val planned = 4_740 * millis
        val clock = LaunchClock(frame, planned)
        var time = 0L
        clock.onFrame(time)
        while (clock.introNanos < planned) {
            time += 100 * millis
            clock.onFrame(time)
        }
        assertTrue("finished at ${time / millis} ms", time <= planned * 3 / 2 + 100 * millis)
        assertEquals(clock.frames - 1, clock.slowFrames)
    }

    @Test fun backdropFadesInBeforeTheMarkAndOutAfterIt() {
        assertEquals(0f, launchBackdrop(0f), 0f)
        assertEquals(1f, launchBackdrop(LAUNCH_BACKDROP_MILLIS.toFloat()), 0f)
        assertTrue("the backdrop is in before the wings start",
            launchBackdrop(LaunchTimeline.WINGS_START * LaunchTimeline.DURATION_MILLIS) > .9f)
        assertEquals(1f, launchBackdrop(LaunchTimeline.DURATION_MILLIS - 1f), 0f)
        assertEquals(0f, launchBackdrop((LaunchTimeline.DURATION_MILLIS + LAUNCH_BACKDROP_MILLIS).toFloat()), 0f)
    }
}
