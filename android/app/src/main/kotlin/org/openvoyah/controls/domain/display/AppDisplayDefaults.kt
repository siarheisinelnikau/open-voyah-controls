package org.openvoyah.controls.domain.display

/**
 * Defaults for app tasks. The private media preview has its own density setting. [moveFingers] is the number of
 * fingers (2 or 3) of the swipe that moves an app's window to the instrument cluster or the passenger display, or 0
 * while that swipe is off. It is on by default with three fingers, which a map's own two-finger pan and pinch never
 * use. [moveShowsApps] makes the central display show the Controls app grid instead of the factory home once a
 * window has left it.
 */
data class AppDisplayDefaults @JvmOverloads constructor(
    val centralDpi: Int = 0,
    val secondaryDpi: Int? = null,
    val moveFingers: Int = DEFAULT_MOVE_FINGERS,
    val moveShowsApps: Boolean = false,
) {
    init {
        require(centralDpi in AppWindowProfile.densities)
        require(secondaryDpi == null || secondaryDpi in AppWindowProfile.densities)
        require(moveFingers in moveFingerChoices)
    }

    companion object {
        const val DEFAULT_MOVE_FINGERS = 3
        val moveFingerChoices = listOf(0, 2, 3)
    }
}
