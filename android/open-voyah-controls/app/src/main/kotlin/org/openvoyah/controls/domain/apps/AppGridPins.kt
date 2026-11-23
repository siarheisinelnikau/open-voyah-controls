package org.openvoyah.controls.domain.apps

internal object AppGridPins {
    const val MAX_PINNED_APPS = 256
    private const val CONTROLS_PACKAGE = "org.openvoyah.controls"
    private val flattened = Regex(
        """([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*)/[\p{L}_$][\p{L}\p{M}\p{N}_$]*(?:\.[\p{L}_$][\p{L}\p{M}\p{N}_$]*)*""",
    )

    fun supports(component: String): Boolean {
        if (component.length > 512) return false
        val match = flattened.matchEntire(component) ?: return false
        return match.groupValues[1] != CONTROLS_PACKAGE
    }

    fun validate(components: List<String>): List<String> {
        require(components.size <= MAX_PINNED_APPS)
        require(components.all(::supports) && components.toSet().size == components.size)
        return components
    }

    fun change(components: List<String>, component: String, pinned: Boolean): List<String> {
        require(supports(component))
        if ((component in components) == pinned) return components
        return if (pinned) validate(components + component) else components - component
    }

    fun swap(components: List<String>, source: String, target: String): List<String> {
        val from = components.indexOf(source)
        val to = components.indexOf(target)
        if (from < 0 || to < 0 || from == to) return components
        return components.toMutableList().apply {
            this[from] = target
            this[to] = source
        }
    }

    fun <T> order(items: List<T>, components: List<String>, unpinnedOrder: Comparator<T>? = null,
                  component: (T) -> String): List<T> {
        if (components.isEmpty()) return unpinnedOrder?.let(items::sortedWith) ?: items
        val byPin = byPosition(components, component)
        return items.sortedWith(if (unpinnedOrder == null) byPin else byPin.then(unpinnedOrder))
    }

    fun <T> byPosition(components: List<String>, component: (T) -> String): Comparator<T> {
        val ranks = components.withIndex().associate { it.value to it.index }
        return compareBy { ranks[component(it)] ?: Int.MAX_VALUE }
    }
}
