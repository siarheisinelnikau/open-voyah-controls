package org.openvoyah.controls.domain.apps

/**
 * The app grid as pages of whole rows that the user swipes through sideways. Cells fill a row from the start, and a
 * cell wider than the rest of its row starts the next row, which is how a grid lays out its lines; a page holds
 * [rows] such lines.
 */
internal object AppGridPages {
    /** Whole rows of [rowHeight] that fit in [availableHeight] with [spacing] between them; at least one. */
    fun rows(availableHeight: Int, rowHeight: Int, spacing: Int): Int {
        require(rowHeight > 0 && spacing >= 0)
        return ((availableHeight.coerceAtLeast(0).toLong() + spacing) / (rowHeight.toLong() + spacing)).toInt().coerceAtLeast(1)
    }

    /**
     * The cell indices of each page for cells of [spans] in a grid of [columns] and [rows]. A span wider than the
     * grid takes a whole row. There is always at least one page, so an empty grid still has a place to show its state.
     */
    fun paginate(spans: List<Int>, columns: Int, rows: Int): List<IntRange> {
        require(columns > 0 && rows > 0)
        if (spans.isEmpty()) return listOf(IntRange.EMPTY)
        val pages = mutableListOf<IntRange>()
        var pageStart = 0
        var line = 0
        var used = 0
        for ((index, raw) in spans.withIndex()) {
            val span = raw.coerceIn(1, columns)
            if (used > 0 && used + span > columns) {
                line++
                used = 0
            }
            if (line == rows) {
                pages += pageStart until index
                pageStart = index
                line = 0
            }
            used += span
        }
        pages += pageStart until spans.size
        return pages
    }

    /** The page that shows cell [index], or the last page when the index is past the end. */
    fun pageOf(pages: List<IntRange>, index: Int): Int =
        pages.indexOfFirst { index in it }.takeIf { it >= 0 } ?: pages.lastIndex.coerceAtLeast(0)
}

/** Moves one app to the place of another within the order the user sees. */
internal object AppGridMoves {
    /**
     * [order] with [source] taken out and put where [target] was: before it when moving towards the start, after it
     * when moving towards the end. Unchanged when either is missing or they are the same.
     */
    fun place(order: List<String>, source: String, target: String): List<String> {
        val from = order.indexOf(source)
        val to = order.indexOf(target)
        if (from < 0 || to < 0 || from == to) return order
        return order.toMutableList().apply {
            removeAt(from)
            add(to, source)
        }
    }

    /**
     * [hovered] when it is in [source]'s group, otherwise the end of that group nearest to [hovered] in [order]: a
     * pinned app stops at the last pin, and another app at the first app after the pins.
     */
    fun targetWithin(order: List<String>, source: String, hovered: String, sameGroup: (String, String) -> Boolean): String {
        if (sameGroup(source, hovered)) return hovered
        val group = order.filter { sameGroup(source, it) }.ifEmpty { return source }
        return if (order.indexOf(hovered) < order.indexOf(source)) group.first() else group.last()
    }

    /**
     * The saved manual order after the move of [source] to [target] in [visible], the unpinned apps as All apps
     * shows them. [saved] keeps its ranks for apps that are not visible now; visible apps without a rank are ranked in
     * their current place first, so the move changes nothing but the two positions involved. [rankAll] ranks every
     * visible app, which keeps the current order when it came from another sort order.
     */
    fun manualOrder(saved: List<String>, visible: List<String>, source: String, target: String, rankAll: Boolean): List<String> {
        val from = visible.indexOf(source)
        val to = visible.indexOf(target)
        if (from < 0 || to < 0 || from == to) return saved
        val base = if (rankAll) emptyList() else saved
        val ranked = base.toHashSet()
        val prefix = if (rankAll) visible else visible.subList(0, maxOf(from, to) + 1)
        val newRanks = prefix.filterNot(ranked::contains)
        if (newRanks.size > AppGridManualOrder.MAX_APPS - base.size) throw AppGridManualOrderFull()
        // The visible apps keep their relative order inside the ranked list, so a place there is a place on screen.
        val extended = base + newRanks
        val fromRank = extended.indexOf(source)
        val toRank = extended.indexOf(target)
        return extended.toMutableList().apply {
            removeAt(fromRank)
            add(toRank, source)
        }
    }
}

/** The settings a move changes; null fields stay as they are. */
internal data class AppGridMove(
    val pinnedApps: List<String>? = null,
    val desktops: AppGridDesktops? = null,
    val manualAppOrder: List<String>? = null,
    val sortOrder: AppGridSortOrder? = null,
)

/**
 * Moves [source] to the place of [target] on [desktop], where [visible] is the app order the desktop shows. Pins stay
 * ahead of the other apps on All apps, so a pinned app moves among pins and any other app among the unpinned ones;
 * moving an unpinned app makes the order manual and keeps everything else where it was. Null when nothing changes.
 */
internal fun AppGridConfiguration.move(desktop: AppGridDesktop, visible: List<String>, source: String, target: String): AppGridMove? {
    if (source == target || source !in visible || target !in visible) return null
    val effective = desktops.selected(desktop)
    val pins = pins(effective)
    if (effective != AppGridDesktop.MAIN) {
        val next = AppGridMoves.place(pins, source, target)
        return if (next == pins) null else AppGridMove(desktops = desktops.withPins(effective, next))
    }
    val sourcePinned = source in pins
    if (sourcePinned != (target in pins)) return null
    if (sourcePinned) {
        val next = AppGridMoves.place(pins, source, target)
        return if (next == pins) null else AppGridMove(pinnedApps = next)
    }
    val unpinned = visible.filterNot(pins::contains)
    val manual = sortOrder == AppGridSortOrder.MANUAL
    val order = AppGridMoves.manualOrder(manualAppOrder, unpinned, source, target, rankAll = !manual)
    return AppGridMove(manualAppOrder = order, sortOrder = AppGridSortOrder.MANUAL.takeUnless { manual })
}
