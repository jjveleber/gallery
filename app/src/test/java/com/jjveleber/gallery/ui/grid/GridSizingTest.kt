package com.jjveleber.gallery.ui.grid

import com.jjveleber.gallery.model.GridSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridSizingTest {
    @Test fun spreadingFingersGrowsOneStep() = assertEquals(GridSize.L, GridSize.M.step(1.6f))
    @Test fun pinchingShrinksOneStep() = assertEquals(GridSize.S, GridSize.M.step(0.6f))
    @Test fun smallGestureKeepsSize() = assertEquals(GridSize.M, GridSize.M.step(1.1f))
    @Test fun clampsAtLargest() = assertEquals(GridSize.XL, GridSize.XL.step(3f))
    @Test fun clampsAtSmallest() = assertEquals(GridSize.S, GridSize.S.step(0.2f))

    @Test fun columnsMatchAdaptiveGrid() {
        assertEquals(4, gridColumns(availablePx = 1000, minCellPx = 240))
        assertEquals(2, gridColumns(availablePx = 480, minCellPx = 240))
        assertEquals(1, gridColumns(availablePx = 100, minCellPx = 240))
    }

    @Test fun prefetchForwardIsTwoScreens() = assertEquals(20..39, prefetchRange(10, 19, itemCount = 1000, forward = true))

    @Test fun prefetchForwardClampsAtTheEnd() {
        assertEquals(990..999, prefetchRange(980, 989, itemCount = 1000, forward = true))
        assertTrue(prefetchRange(990, 999, itemCount = 1000, forward = true).isEmpty())
    }

    @Test fun prefetchBackwardIsTwoScreensClampedAtTheTop() {
        assertEquals(30..49, prefetchRange(50, 59, itemCount = 1000, forward = false))
        assertEquals(0..9, prefetchRange(10, 19, itemCount = 1000, forward = false))
        assertTrue(prefetchRange(0, 9, itemCount = 1000, forward = false).isEmpty())
    }

    @Test fun nothingVisibleMeansNoPrefetch() {
        assertTrue(prefetchRange(0, -1, itemCount = 0, forward = true).isEmpty())
        assertTrue(prefetchRange(0, -1, itemCount = 50, forward = true).isEmpty())
    }
}
