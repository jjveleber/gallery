package com.jjveleber.gallery.ui.grid

import com.jjveleber.gallery.model.GridSize
import org.junit.Assert.assertEquals
import org.junit.Test

class GridSizingTest {
    @Test fun spreadingFingersGrowsOneStep() = assertEquals(GridSize.L, GridSize.M.step(1.6f))
    @Test fun pinchingShrinksOneStep() = assertEquals(GridSize.S, GridSize.M.step(0.6f))
    @Test fun smallGestureKeepsSize() = assertEquals(GridSize.M, GridSize.M.step(1.1f))
    @Test fun clampsAtLargest() = assertEquals(GridSize.XL, GridSize.XL.step(3f))
    @Test fun clampsAtSmallest() = assertEquals(GridSize.S, GridSize.S.step(0.2f))
}
