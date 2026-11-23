package org.openvoyah.controls.domain.apps

/**
 * Which apps a "choose an app" list offers. It follows the grid's **Hide** choice: an app All apps hides by default
 * (firmware, maker, keyboard, service apps and extra launcher entries) is left out unless the user un-hid it, and an
 * app the user hid is left out too. [keep] holds apps already chosen in that list, which stay so they can be removed.
 */
internal object AppPickerChoices {
    fun <T> offered(items: List<T>, component: (T) -> String, hiddenByDefault: (T) -> Boolean,
                    grid: AppGridConfiguration?, showAll: Boolean, keep: Set<String> = emptySet()): List<T> {
        if (showAll) return items
        val rules = grid ?: AppGridConfiguration()
        return items.filter { component(it) in keep || !rules.hides(component(it), hiddenByDefault(it)) }
    }

    /** Whether "show all" would add anything to the list, so the switch appears only when it matters. */
    fun <T> hidesAny(items: List<T>, component: (T) -> String, hiddenByDefault: (T) -> Boolean,
                     grid: AppGridConfiguration?, keep: Set<String> = emptySet()): Boolean =
        offered(items, component, hiddenByDefault, grid, showAll = false, keep).size < items.size
}
