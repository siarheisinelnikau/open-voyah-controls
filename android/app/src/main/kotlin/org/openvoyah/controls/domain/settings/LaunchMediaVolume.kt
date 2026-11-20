package org.openvoyah.controls.domain.settings

/** Returns a lower index only when the current level exceeds the configured ceiling. */
internal fun launcherMediaVolumeTarget(minimum: Int, maximum: Int, current: Int, percent: Int): Int? {
    if (minimum < 0 || minimum >= maximum || current !in minimum..maximum || percent !in LAUNCH_MEDIA_VOLUME_PERCENTS) {
        return null
    }
    // Round down to the highest available step that stays within the ceiling.
    val ceiling = minimum + ((maximum - minimum).toLong() * percent / 100).toInt()
    return ceiling.takeIf { current > it }
}
