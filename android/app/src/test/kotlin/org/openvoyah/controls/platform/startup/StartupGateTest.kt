package org.openvoyah.controls.platform.startup

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupGateTest {
    private val queue = ArrayList<Runnable>()
    private val delayed = ArrayList<Pair<Runnable, Long>>()
    private val gate = StartupGate(
        post = { queue += it },
        postDelayed = { task, delay -> delayed += task to delay },
        cancel = { task -> delayed.removeAll { it.first === task } },
    )
    private val ran = ArrayList<String>()

    private fun drain() {
        while (queue.isNotEmpty()) queue.removeAt(0).run()
    }

    @Test fun withoutAnIntroWorkRunsAfterTheQueuedMessages() {
        gate.afterIntro { ran += "work" }
        assertEquals(emptyList<String>(), ran)
        drain()
        assertEquals(listOf("work"), ran)
    }

    @Test fun anIntroLaunchedBeforeThePostHoldsTheWorkUntilItEnds() {
        gate.afterIntro { ran += "work" }
        // The Activity launch was queued before the Application posted its work.
        val release = gate.hold(20_000L)
        drain()
        assertEquals(emptyList<String>(), ran)
        release()
        assertEquals(listOf("work"), ran)
        release()
        assertEquals("a second release does nothing", listOf("work"), ran)
        assertEquals(emptyList<Pair<Runnable, Long>>(), delayed)
    }

    @Test fun aHoldThatIsNeverReleasedEndsAtItsLimit() {
        gate.hold(20_000L)
        gate.afterIntro { ran += "work" }
        drain()
        val (limit, delay) = delayed.single()
        assertEquals(20_000L, delay)
        limit.run()
        assertEquals(listOf("work"), ran)
    }

    @Test fun workWaitsForEveryHold() {
        val first = gate.hold(20_000L)
        val second = gate.hold(20_000L)
        gate.afterIntro { ran += "work" }
        drain()
        first()
        assertEquals(emptyList<String>(), ran)
        second()
        assertEquals(listOf("work"), ran)
    }
}
