package org.openvoyah.controls.domain.routines

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal enum class RoutineStep(val key: String, val delayMillis: Long = 0) {
    WAIT_ONE("wait_1", 1_000),
    WAIT_THREE("wait_3", 3_000),
    WAIT_FIVE("wait_5", 5_000),
    WAIT_TEN("wait_10", 10_000),
    MEDIA_PLAY("media_play"),
    MEDIA_PAUSE("media_pause"),
    MEDIA_NEXT("media_next"),
    MEDIA_PREVIOUS("media_previous");

    companion object { const val LIMIT = 6 }
}

internal enum class RoutineStepResult { REQUESTED, UNAVAILABLE, UNCONFIRMED }

/** Runs each step once. A paused, rejected or uncertain run never advances to the next action. */
internal suspend fun runRoutineSteps(
    steps: List<RoutineStep>,
    ensureCurrent: () -> Unit,
    onStep: (index: Int) -> Unit,
    request: (RoutineStep) -> RoutineStepResult,
    wait: suspend (Long) -> Unit = { delay(it) },
): RoutineStepResult {
    for ((index, step) in steps.withIndex()) {
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        onStep(index)
        if (step.delayMillis > 0) {
            wait(step.delayMillis)
        } else {
            val result = request(step)
            if (result != RoutineStepResult.REQUESTED) return result
        }
    }
    currentCoroutineContext().ensureActive()
    ensureCurrent()
    return RoutineStepResult.REQUESTED
}
