package org.openvoyah.controls.presentation.theme

/**
 * Decides when frames arrive steadily enough for the startup intro to begin. Right after boot the head unit is
 * still starting apps and agents, and an intro started then stuttered; while this waits, the intro's window shows
 * nothing and home stays on screen.
 */
internal class LaunchSettle(frameNanos: Long, private val steadyFrames: Int = STEADY_FRAMES) {
    // One missed vsync is not visible in this intro; two in a row are.
    private val allowedGap = 2 * frameNanos + frameNanos / 4
    private var last = -1L
    private var run = 0

    /** Feeds one frame time and answers whether the last [steadyFrames] gaps all stayed within two frames. */
    fun onFrame(timeNanos: Long): Boolean {
        if (last >= 0) run = if (timeNanos - last <= allowedGap) run + 1 else 0
        last = timeNanos
        return run >= steadyFrames
    }

    companion object {
        /** About a third of a second at 60 Hz. */
        const val STEADY_FRAMES = 20
    }
}

/**
 * Turns frame times into intro time. A late frame moves the intro on by at most two frames, so a stall shows as a
 * short pause instead of a jump over a stage. Intro time never falls more than half the plan behind the wall clock,
 * so a device that stays slow still finishes within one and a half times the plan.
 */
internal class LaunchClock(private val frameNanos: Long, private val plannedNanos: Long) {
    private var start = -1L
    private var last = -1L

    /** Intro time in nanoseconds; it starts at the first frame. */
    var introNanos = 0L
        private set
    var frames = 0
        private set
    /** Frames that came two or more vsyncs late. */
    var slowFrames = 0
        private set
    var longestGapNanos = 0L
        private set
    val wallNanos get() = if (start < 0) 0L else last - start

    fun onFrame(timeNanos: Long): Long {
        frames++
        if (start < 0) {
            start = timeNanos
            last = timeNanos
            return introNanos
        }
        val gap = (timeNanos - last).coerceAtLeast(0L)
        last = timeNanos
        if (gap > 2 * frameNanos + frameNanos / 2) slowFrames++
        longestGapNanos = maxOf(longestGapNanos, gap)
        introNanos = maxOf(introNanos + minOf(gap, 2 * frameNanos), timeNanos - start - plannedNanos / 2)
        return introNanos
    }
}

/** What one run of the intro looked like, for the diagnostic journal. */
internal data class LaunchReport(
    val completed: Boolean,
    val settled: Boolean,
    val settleMillis: Long,
    val frames: Int,
    val slowFrames: Int,
    val longestGapMillis: Long,
    val elapsedMillis: Long,
)
