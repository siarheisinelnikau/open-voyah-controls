package org.openvoyah.controls.presentation.apps

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.apps.AppGridMoves
import org.openvoyah.controls.domain.apps.AppGridPages
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.presentation.theme.CockpitMotion
import org.openvoyah.controls.presentation.theme.LocalCockpitMotion

/** Whole rows of one page and their height, for the height the pages get. */
internal data class GridPageLayout(val rows: Int, val rowHeight: Dp)

/**
 * What a cell needs from its page: the row height, whether the page is on screen, and [anchor], which an app tile adds
 * to its root modifier so the move mode knows where the tile is.
 */
internal class GridCellContext(val rowHeight: Dp, val visible: Boolean, val anchor: Modifier = Modifier)

/** One cell of the paged app grid. [app] is set for an app tile, which the move mode can pick up and place. */
internal class GridCell(
    val key: String,
    val span: Int,
    val app: LocalApp? = null,
    val content: @Composable LazyGridItemScope.(GridCellContext) -> Unit,
)

/**
 * The move mode of the app grid. [selected] is the app picked by a tap or from its settings, [preview] the app order
 * while a drag runs, and [pending] the order after a drop until the saved order arrives, so the grid does not jump
 * back in between.
 */
@Stable
internal class AppMoveState(selected: String?) {
    var selected by mutableStateOf(selected)
    var dragging by mutableStateOf<String?>(null)
    var preview by mutableStateOf<List<String>?>(null)
    var pending by mutableStateOf<List<String>?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var grab by mutableStateOf(Offset.Zero)
    var dragSize by mutableStateOf(Size.Zero)

    /** The app order the grid shows now, given the [arranged] order from the settings. */
    fun order(arranged: List<String>): List<String> = preview ?: pending ?: arranged
}

/**
 * The app grid as pages the user swipes sideways, with snapping and a page indicator. [layoutFor] gives the rows that
 * fit a height; the indicator takes its row only when there is more than one page. With [move], app tiles can be
 * dragged: the order previews under the finger, holding the tile at an edge turns the page, and a drop calls [place]
 * with the app and the app whose place it took in [arranged]. [sameGroup] keeps pinned and other apps apart.
 * [footer] replaces the indicator row and gets the page dots to place; it has the row's height, so the pages keep
 * their layout while it is shown.
 */
@Composable
internal fun PagedAppGrid(
    cells: List<GridCell>,
    columns: Int,
    spacing: Dp,
    layoutFor: (Dp) -> GridPageLayout,
    resetKey: Any,
    move: AppMoveState?,
    arranged: List<String>,
    sameGroup: (String, String) -> Boolean,
    place: (source: String, target: String) -> Unit,
    floating: @Composable (LocalApp, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable (dots: @Composable () -> Unit) -> Unit)? = null,
) {
    BoxWithConstraints(modifier) {
        val indicatorHeight = 28.dp
        val spans = cells.map { it.span.coerceIn(1, columns) }
        val paged = layoutFor(maxHeight - indicatorHeight)
        val pagedRanges = AppGridPages.paginate(spans, columns, paged.rows)
        val showIndicator = pagedRanges.size > 1 || footer != null
        val pageLayout = if (showIndicator) paged else layoutFor(maxHeight)
        val pages = if (showIndicator) pagedRanges else AppGridPages.paginate(spans, columns, pageLayout.rows)
        val motion = LocalCockpitMotion.current
        val scope = rememberCoroutineScope()
        val state = key(resetKey) { rememberPagerState { pages.size } }
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val bounds = remember { mutableStateMapOf<String, Rect>() }
        var container by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val currentCells by rememberUpdatedState(cells)
        val currentPages by rememberUpdatedState(pages)
        val currentArranged by rememberUpdatedState(arranged)
        val currentSameGroup by rememberUpdatedState(sameGroup)
        val currentPlace by rememberUpdatedState(place)

        fun go(page: Int) {
            scope.launch { if (motion.enabled) state.animateScrollToPage(page) else state.scrollToPage(page) }
        }

        // An app picked from its settings page is on the page the grid shows first.
        val selected = move?.selected
        LaunchedEffect(move, selected != null) {
            val app = selected ?: return@LaunchedEffect
            val index = currentCells.indexOfFirst { it.app?.id == app }
            if (index >= 0) state.scrollToPage(AppGridPages.pageOf(currentPages, index))
        }

        fun onScreen(rect: Rect) = rect.right > 0f && rect.left < widthPx
        fun appAt(point: Offset): String? = bounds.entries.firstOrNull { onScreen(it.value) && it.value.contains(point) }?.key
        fun nearestApp(point: Offset): String? = bounds.entries.filter { onScreen(it.value) }
            .minByOrNull { (it.value.center - point).getDistanceSquared() }?.key

        /** Moves the dragged app to the place of [hovered], or of the nearest app of its own group. */
        fun hover(hovered: String) {
            val session = move ?: return
            val dragged = session.dragging ?: return
            val order = session.preview ?: return
            // Pins stay ahead of the other apps: a drag stops at the edge of the dragged app's own group.
            val target = AppGridMoves.targetWithin(order, dragged, hovered, currentSameGroup)
            if (target != dragged) session.preview = AppGridMoves.place(order, dragged, target)
        }

        fun finish(drop: Boolean) {
            val session = move ?: return
            val dragged = session.dragging ?: return
            val final = session.preview
            session.dragging = null
            session.preview = null
            if (!drop || final == null) return
            val index = final.indexOf(dragged)
            val target = currentArranged.getOrNull(index)
            if (target != null && target != dragged) {
                session.pending = final
                currentPlace(dragged, target)
            }
        }

        // Holding the dragged app at an edge turns the page and puts the app on the near end of the new page.
        val edge = with(density) { 56.dp.toPx() }
        val side by remember(move, widthPx) {
            derivedStateOf {
                val session = move
                when {
                    session == null || session.dragging == null -> 0
                    session.pointer.x < edge -> -1
                    session.pointer.x > widthPx - edge -> 1
                    else -> 0
                }
            }
        }
        LaunchedEffect(side) {
            if (side == 0) return@LaunchedEffect
            delay(EDGE_HOLD_MILLIS)
            while (true) {
                val next = state.currentPage + side
                if (next !in 0 until state.pageCount) break
                if (motion.enabled) state.animateScrollToPage(next) else state.scrollToPage(next)
                val range = currentPages.getOrNull(next) ?: break
                val dragged = move?.dragging ?: break
                val apps = range.mapNotNull { currentCells.getOrNull(it)?.app?.id }.filter { it != dragged }
                (if (side > 0) apps.firstOrNull() else apps.lastOrNull())?.let(::hover)
                delay(EDGE_REPEAT_MILLIS)
            }
        }

        val drag = if (move == null) Modifier else Modifier.pointerInput(move) {
            detectDragGestures(
                onDragStart = { start ->
                    val app = appAt(start) ?: return@detectDragGestures
                    val rect = bounds[app] ?: return@detectDragGestures
                    move.selected = null
                    move.pending = null
                    move.preview = currentCells.mapNotNull { it.app?.id }
                    move.dragging = app
                    move.grab = start - rect.topLeft
                    move.dragSize = rect.size
                    move.pointer = start
                },
                onDrag = { change, amount ->
                    if (move.dragging != null) {
                        change.consume()
                        move.pointer += amount
                        nearestApp(move.pointer)?.let(::hover)
                    }
                },
                onDragEnd = { finish(drop = true) },
                onDragCancel = { finish(drop = false) },
            )
        }

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { container = it }.then(drag)) {
                val fling = PagerDefaults.flingBehavior(state,
                    snapAnimationSpec = if (motion.enabled) tween<Float>(CockpitMotion.ENTER, easing = CockpitMotion.Settle) else snap())
                HorizontalPager(state, Modifier.fillMaxSize(), flingBehavior = fling,
                    userScrollEnabled = move?.dragging == null, verticalAlignment = Alignment.Top) { page ->
                    val range = pages.getOrNull(page) ?: IntRange.EMPTY
                    val onScreen by remember(state, page) {
                        derivedStateOf { state.layoutInfo.visiblePagesInfo.any { it.index == page } }
                    }
                    val plain = GridCellContext(pageLayout.rowHeight, onScreen)
                    LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize(), userScrollEnabled = false,
                        horizontalArrangement = Arrangement.spacedBy(spacing), verticalArrangement = Arrangement.spacedBy(spacing)) {
                        items(range.toList(), key = { cells[it].key }, span = { GridItemSpan(spans[it]) }) { index ->
                            val cell = cells[index]
                            val app = cell.app
                            if (app != null && move != null) {
                                DisposableEffect(app.id) { onDispose { bounds.remove(app.id) } }
                                cell.content(this, GridCellContext(pageLayout.rowHeight, onScreen, Modifier.onGloballyPositioned { coordinates ->
                                    container?.takeIf { it.isAttached }?.let { bounds[app.id] = it.localBoundingBoxOf(coordinates) }
                                }))
                            } else cell.content(this, plain)
                        }
                    }
                }
                val dragged = move?.dragging?.let { id -> cells.firstOrNull { it.app?.id == id }?.app }
                if (move != null && dragged != null) {
                    val size = with(density) { Modifier.size(move.dragSize.width.toDp(), move.dragSize.height.toDp()) }
                    floating(dragged, Modifier
                        .offset { (move.pointer - move.grab).let { IntOffset(it.x.toInt(), it.y.toInt()) } }
                        .then(size)
                        .graphicsLayer { scaleX = 1.06f; scaleY = 1.06f; shadowElevation = 12f })
                }
            }
            val dots: @Composable () -> Unit = { if (pages.size > 1) PageIndicator(state, pages.size, Modifier.height(indicatorHeight), ::go) }
            if (footer != null) {
                Box(Modifier.fillMaxWidth().height(indicatorHeight), contentAlignment = Alignment.Center) { footer(dots) }
            } else if (showIndicator) {
                Box(Modifier.fillMaxWidth().height(indicatorHeight), contentAlignment = Alignment.Center) { dots() }
            }
        }
    }
}

/** One dot per page; the current one is a longer bar. A dot opens its page. */
@Composable
private fun PageIndicator(state: PagerState, count: Int, modifier: Modifier, go: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalCockpitMotion.current
    val current = state.currentPage
    val description = UiText.get(R.string.apps_grid_page).format(current + 1, count)
    Row(modifier.semantics { stateDescription = description }, horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { page ->
            val active = page == current
            val color by animateColorAsState(if (active) colors.primary else colors.outlineVariant,
                if (motion.enabled) CockpitMotion.change() else snap(), label = "page dot")
            val width by animateDpAsState(if (active) 22.dp else 8.dp,
                if (motion.enabled) CockpitMotion.change() else snap(), label = "page dot width")
            val label = UiText.get(R.string.apps_grid_go_to_page).format(page + 1)
            Box(Modifier.width(32.dp).height(28.dp).clickable(role = Role.Tab) { go(page) }
                .semantics { contentDescription = label; selected = active }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(width).height(8.dp).clip(CircleShape).background(color))
            }
        }
    }
}

/** How long the dragged app waits at an edge before the page turns, and between further turns. */
private const val EDGE_HOLD_MILLIS = 600L
private const val EDGE_REPEAT_MILLIS = 900L
