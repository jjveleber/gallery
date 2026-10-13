package com.jjveleber.gallery.slideshow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerMathTest {
    @Test fun startPageMapsToIndex() {
        for (n in listOf(2, 3, 7, 10_000)) for (i in listOf(0, 1, n - 1)) {
            assertEquals(i, LoopPaging.indexOf(LoopPaging.startPage(i, n), n))
        }
    }

    @Test fun swipingBackFromFirstWrapsToLast() {
        val n = 5
        assertEquals(n - 1, LoopPaging.indexOf(LoopPaging.startPage(0, n) - 1, n))
    }

    @Test fun swipingForwardFromLastWrapsToFirst() {
        val n = 5
        assertEquals(0, LoopPaging.indexOf(LoopPaging.startPage(n - 1, n) + 1, n))
    }

    @Test fun singleItemDoesNotLoop() {
        assertEquals(1, LoopPaging.pageCount(1))
        assertEquals(0, LoopPaging.startPage(0, 1))
        assertEquals(0, LoopPaging.indexOf(0, 1))
    }

    @Test fun manyItemsLeaveRoomToLoopBothWays() {
        val n = 10_000
        val start = LoopPaging.startPage(0, n)
        assertTrue(start >= 100 * n)
        assertTrue(LoopPaging.pageCount(n) - start >= 100 * n)
    }

    @Test fun tapZones() {
        assertEquals(TapZone.Prev, tapZone(10f, 1000f))
        assertEquals(TapZone.Prev, tapZone(149f, 1000f))
        assertEquals(TapZone.Middle, tapZone(150f, 1000f))
        assertEquals(TapZone.Middle, tapZone(850f, 1000f))
        assertEquals(TapZone.Next, tapZone(851f, 1000f))
    }

    @Test fun nextIntervalCyclesThroughTheList() {
        assertEquals(10, nextInterval(5))
        assertEquals(3, nextInterval(300))
        assertEquals(3, nextInterval(7)) // not in the list: start over
    }

    @Test fun intervalLabels() {
        assertEquals("3 s", intervalLabel(3))
        assertEquals("1 min", intervalLabel(60))
        assertEquals("5 min", intervalLabel(300))
    }
}
