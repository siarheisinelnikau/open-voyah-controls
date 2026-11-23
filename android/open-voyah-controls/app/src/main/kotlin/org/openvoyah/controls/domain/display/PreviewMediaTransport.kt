package org.openvoyah.controls.domain.display

import org.openvoyah.controls.core.display.PlaybackProgress

internal enum class PreviewMediaAction(val seekDirection: Int = 0) {
    PREVIOUS,
    PLAY,
    PAUSE,
    NEXT,
    SEEK_BACK(-1),
    SEEK_FORWARD(1),
}

internal fun PlaybackProgress.relativePosition(action: PreviewMediaAction, elapsedRealtime: Long, step: PreviewSeekStep): Long {
    val current = positionAt(elapsedRealtime)
    val offset = step.seconds * 1_000L * action.seekDirection
    return current + offset.coerceIn(-current, durationMillis() - current)
}

internal fun <T> preferredMediaSource(
    candidates: List<T>,
    preferredPackage: String,
    packageName: (T) -> String,
    fallback: PreviewMediaFallback = PreviewMediaFallback.AUTOMATIC,
    automatic: (List<T>) -> T?,
): T? {
    if (preferredPackage.isNotEmpty()) {
        val preferred = candidates.filter { packageName(it) == preferredPackage }
        if (preferred.isNotEmpty()) return automatic(preferred)
        if (fallback == PreviewMediaFallback.PREFERRED_ONLY) return null
    }
    return automatic(candidates)
}
