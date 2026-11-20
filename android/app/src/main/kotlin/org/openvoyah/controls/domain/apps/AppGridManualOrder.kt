package org.openvoyah.controls.domain.apps

internal data class AppGridOrderApp(val component: String, val system: Boolean)

internal class AppGridManualOrderFull : IllegalStateException("Manual app order is full")

internal object AppGridManualOrder {
    const val MAX_APPS = AppGridPins.MAX_PINNED_APPS

    fun move(saved: List<String>, visible: List<String>, source: String, neighbor: String, earlier: Boolean): List<String> {
        val from = visible.indexOf(source)
        val to = from + if (earlier) -1 else 1
        if (from < 0 || visible.getOrNull(to) != neighbor) return saved
        // Keep saved positions of unavailable, hidden and pinned apps. Rank only the prefix needed for this move.
        val ranked = saved.toHashSet()
        val newRanks = visible.subList(0, maxOf(from, to) + 1).filterNot(ranked::contains)
        if (newRanks.size > MAX_APPS - saved.size) throw AppGridManualOrderFull()
        return AppGridPins.swap(saved + newRanks, source, neighbor)
    }
}
