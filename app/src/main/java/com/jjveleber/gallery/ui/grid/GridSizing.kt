package com.jjveleber.gallery.ui.grid

import com.jjveleber.gallery.model.GridSize

/** One preset step per pinch gesture: spread fingers for bigger cells, pinch for smaller. */
fun GridSize.step(zoom: Float): GridSize = when {
    zoom > 1.25f -> GridSize.entries.getOrElse(ordinal + 1) { this }
    zoom < 0.8f -> GridSize.entries.getOrElse(ordinal - 1) { this }
    else -> this
}

/** Columns of at least [minCellPx] that fit in [availablePx]: the count GridCells.Adaptive picks with no spacing. */
fun gridColumns(availablePx: Int, minCellPx: Int): Int = maxOf(1, availablePx / maxOf(1, minCellPx))

/**
 * Cells to prefetch: up to [pages] screens past the visible cells ([firstVisible]..[lastVisible]) in the scroll
 * direction, clamped to the list. Empty when nothing is visible.
 */
fun prefetchRange(firstVisible: Int, lastVisible: Int, itemCount: Int, forward: Boolean, pages: Int = 2): IntRange {
    if (itemCount <= 0 || lastVisible < firstVisible) return IntRange.EMPTY
    val ahead = (lastVisible - firstVisible + 1) * pages
    return if (forward) (lastVisible + 1)..minOf(itemCount - 1, lastVisible + ahead)
    else maxOf(0, firstVisible - ahead)..(firstVisible - 1)
}
