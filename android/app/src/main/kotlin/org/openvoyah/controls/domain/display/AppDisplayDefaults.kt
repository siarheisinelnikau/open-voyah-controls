package org.openvoyah.controls.domain.display

/**
 * Defaults for app tasks. The private media preview has its own density setting. [moveFingers] is the number of
 * fingers (2 or 3) of the swipe that moves an app's window to the instrument cluster or the passenger display, or 0
 * while that swipe is off.
 */
data class AppDisplayDefaults(val centralDpi: Int = 0, val secondaryDpi: Int? = null, val moveFingers: Int = 0) {
    init {
        require(centralDpi in AppWindowProfile.densities)
        require(secondaryDpi == null || secondaryDpi in AppWindowProfile.densities)
        require(moveFingers in moveFingerChoices)
    }

    companion object {
        val moveFingerChoices = listOf(0, 2, 3)
    }
}
