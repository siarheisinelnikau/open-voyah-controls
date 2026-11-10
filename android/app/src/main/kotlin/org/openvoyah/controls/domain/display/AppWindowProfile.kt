package org.openvoyah.controls.domain.display

data class AppWindowProfile(
    val component: String,
    val widthPercent: Int = 100,
    val densityDpi: Int = 0,
    val panelWidthPx: Int = 0,
    val inheritDensity: Boolean = densityDpi == 0,
) {
    init {
        require(eligible(component))
        require(widthPercent in 50..100 && widthPercent % 5 == 0)
        require(densityDpi in densities)
        require(!inheritDensity || densityDpi == 0)
        require(panelWidthPx in 0..600)
    }

    val packageName: String get() = component.substringBefore('/')

    companion object {
        val densities = listOf(0) + (100..320 step 10)
        const val MAX_PROFILES = 16
        private val flattened = Regex("""([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/(\.?[A-Za-z_$][A-Za-z0-9_.$]*)""")
        private val protected = setOf(
            "org.openvoyah.controls", "com.android.settings", "com.android.systemui", "com.qinggan.app.launcher",
            "com.qinggan.app.setting", "com.qinggan.app.vehicle", "com.qinggan.cluster",
        )

        fun key(component: String): String? {
            if (component.length > 200) return null
            val match = flattened.matchEntire(component) ?: return null
            val (packageName, className) = match.destructured
            if (packageName in protected) return null
            return "$packageName/${if (className.startsWith('.')) packageName + className else className}"
        }

        fun eligible(component: String): Boolean = key(component) != null
    }
}
