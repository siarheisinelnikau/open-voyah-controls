package org.openvoyah.controls.presentation.display

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openvoyah.controls.core.display.PlaybackProgress

class PlaybackProgressPositionsTest {
    @Test fun visiblePlaybackTicksAtMostOncePerSecondAndStopsAtDuration() = runBlocking {
        val playback = PlaybackProgress(3_000, 500, 100, 1.5f)
        var now = 100L
        val waits = mutableListOf<Long>()
        val positions = playback.previewPositions(visible = true, elapsedRealtime = { now }) { wait ->
            waits += wait
            now += wait
        }.toList()

        assertEquals(listOf(500L, 2_000L, 3_000L), positions)
        assertEquals(listOf(1_000L, 1_000L), waits)
    }

    @Test fun hiddenPreviewDoesNoClockReadsOrTicks() = runBlocking {
        val playback = PlaybackProgress(3_000, 500, 100, 1f)
        val positions = playback.previewPositions(visible = false, elapsedRealtime = { error("Clock read while hidden") }) {
            error("Tick while hidden")
        }.toList()
        assertEquals(emptyList<Long>(), positions)
    }

    @Test fun stationaryAndCompletedPlaybackEmitOnceWithoutTicks() = runBlocking {
        val paused = PlaybackProgress(3_000, 500, 100, 0f)
        val completed = PlaybackProgress(3_000, 500, 100, 1f)
        assertEquals(listOf(500L), paused.previewPositions(true, { 5_000 }) { error("Tick while paused") }.toList())
        assertEquals(listOf(3_000L), completed.previewPositions(true, { 5_000 }) { error("Tick after end") }.toList())
    }

    @Test fun stoppingTheCollectorCancelsFurtherTicks() = runBlocking {
        val playback = PlaybackProgress(3_000, 500, 100, 1f)
        val positions = playback.previewPositions(true, { 100 }) { error("Tick after collection stopped") }
            .take(1).toList()
        assertEquals(listOf(500L), positions)
    }
}
