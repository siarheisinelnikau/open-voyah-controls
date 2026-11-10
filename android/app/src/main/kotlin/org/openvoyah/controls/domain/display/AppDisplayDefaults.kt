package org.openvoyah.controls.domain.display

/** Defaults for app tasks. The private media preview has its own density setting. */
data class AppDisplayDefaults(val centralDpi: Int = 0, val secondaryDpi: Int? = null) {
    init {
        require(centralDpi in AppWindowProfile.densities)
        require(secondaryDpi == null || secondaryDpi in AppWindowProfile.densities)
    }
}
