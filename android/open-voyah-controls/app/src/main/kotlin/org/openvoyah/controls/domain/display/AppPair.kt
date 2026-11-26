package org.openvoyah.controls.domain.display

internal data class AppPair(
    val id: String,
    val name: String,
    val left: String,
    val right: String,
    val leftPercent: Int,
    val inDock: Boolean,
) {
    init {
        require(id.matches(Regex("[a-f0-9]{32}")))
        require(name.isNotBlank() && name.length <= MAX_NAME && name.none(Char::isISOControl))
        require(eligible(left) && eligible(right))
        require(left.substringBefore('/') != right.substringBefore('/'))
        require(leftPercent in proportions)
    }

    companion object {
        const val MAX_PAIRS = 8
        const val MAX_NAME = 64
        val proportions = listOf(33, 50, 67)

        fun eligible(component: String): Boolean = AppWindowProfile.key(component) == component
    }
}
