package org.openvoyah.controls.presentation.display

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.openvoyah.controls.core.display.PlaybackProgress

internal fun PlaybackProgress.previewPositions(
    visible: Boolean,
    elapsedRealtime: () -> Long,
    waitForTick: suspend (Long) -> Unit = { delay(it) },
): Flow<Long> = flow {
    if (!visible) return@flow
    var position = positionAt(elapsedRealtime())
    emit(position)
    while (advancing() && position < durationMillis()) {
        waitForTick(1_000L)
        val next = positionAt(elapsedRealtime())
        if (next > position) {
            position = next
            emit(position)
        }
    }
}
