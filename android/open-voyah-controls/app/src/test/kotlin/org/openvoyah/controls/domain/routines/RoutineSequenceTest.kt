package org.openvoyah.controls.domain.routines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RoutineSequenceTest {
    @Test fun pauseAndPlaybackRunInOrder() = runBlocking {
        val calls = mutableListOf<String>()
        val result = runRoutineSteps(listOf(RoutineStep.MEDIA_PAUSE, RoutineStep.WAIT_THREE, RoutineStep.MEDIA_PLAY),
            ensureCurrent = { calls += "check" }, onStep = { calls += "step ${it + 1}" }, request = {
                calls += it.key
                RoutineStepResult.REQUESTED
            }, wait = { calls += "wait $it" })
        assertEquals(RoutineStepResult.REQUESTED, result)
        assertEquals(listOf("check", "step 1", "media_pause", "check", "step 2", "wait 3000",
            "check", "step 3", "media_play", "check"), calls)
    }

    @Test fun uncertainDeliveryNeverContinuesOrRetries() = runBlocking {
        val called = mutableListOf<RoutineStep>()
        val same = RoutineStep.MEDIA_NEXT
        val result = runRoutineSteps(listOf(same, same, RoutineStep.MEDIA_PLAY), {}, {}, {
            called += it
            RoutineStepResult.UNCONFIRMED
        })
        assertEquals(RoutineStepResult.UNCONFIRMED, result)
        assertEquals(listOf(same), called)
    }

    @Test fun ownerRevokedDuringPauseCannotDispatchTheNextAction() {
        var owned = true
        assertThrows(CancellationException::class.java) {
            runBlocking {
                runRoutineSteps(listOf(RoutineStep.WAIT_ONE, RoutineStep.MEDIA_PLAY), {
                    if (!owned) throw CancellationException("Owner lost")
                }, {}, { error("Playback should not run") }, wait = { owned = false })
            }
        }
    }
}
