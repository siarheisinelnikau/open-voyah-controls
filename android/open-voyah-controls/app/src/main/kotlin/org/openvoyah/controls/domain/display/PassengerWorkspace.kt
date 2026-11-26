package org.openvoyah.controls.domain.display

internal data class PassengerWorkspace(
    val slots: List<String?> = List(SLOT_COUNT) { null },
    val dock: List<String?> = List(DOCK_COUNT) { null },
    val compact: Boolean = false,
    val clock: Boolean = true,
    val date: Boolean = false,
    val openOnStartup: Boolean = false,
) {
    init {
        require(validApps(slots, SLOT_COUNT) && validApps(dock, DOCK_COUNT))
    }

    fun setApp(inDock: Boolean, slot: Int, component: String?): PassengerWorkspace {
        val changed = (if (inDock) dock else slots).toMutableList()
        changed[slot] = component
        return if (inDock) copy(dock = changed.toList()) else copy(slots = changed.toList())
    }

    companion object {
        const val SLOT_COUNT = 6
        const val DOCK_COUNT = 2

        fun eligible(component: String): Boolean = AppWindowProfile.key(component) == component

        private fun validApps(apps: List<String?>, count: Int): Boolean = apps.size == count &&
            apps.filterNotNull().let { nonempty -> nonempty.all(::eligible) && nonempty.distinct().size == nonempty.size }
    }
}
