package com.jjveleber.gallery.model

import com.jjveleber.gallery.testing.item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SortTest {
    private fun names(items: List<ImageItem>) = items.map { it.relPath }

    @Test fun naturalOrderSortsDigitRunsByValue() {
        assertEquals(listOf("img1", "img2", "img10"), listOf("img10", "img2", "img1").sortedWith(NaturalOrder))
    }

    @Test fun naturalOrderIgnoresCase() {
        assertEquals(listOf("apple", "Banana", "cherry"), listOf("cherry", "Banana", "apple").sortedWith(NaturalOrder))
    }

    @Test fun naturalOrderHandlesHugeDigitRuns() {
        val small = "IMG_99999999999999999999999"
        val big = "IMG_100000000000000000000000"
        assertTrue(NaturalOrder.compare(small, big) < 0)
        assertTrue(NaturalOrder.compare(big, small) > 0)
    }

    @Test fun naturalOrderIsTotalForLeadingZeros() {
        val c = NaturalOrder.compare("img01", "img1")
        assertNotEquals(0, c)
        assertEquals(-c, NaturalOrder.compare("img1", "img01"))
    }

    @Test fun naturalOrderShorterPrefixFirst() {
        assertTrue(NaturalOrder.compare("a", "ab") < 0)
    }

    @Test fun nameAscendingAndDescending() {
        val items = listOf(item("b10.jpg"), item("b2.jpg"), item("A.jpg"))
        assertEquals(listOf("A.jpg", "b2.jpg", "b10.jpg"), names(sortItems(items, SortSpec(SortOrder.Name))))
        assertEquals(listOf("b10.jpg", "b2.jpg", "A.jpg"), names(sortItems(items, SortSpec(SortOrder.Name, ascending = false))))
    }

    @Test fun nameTiesBrokenByRelPath() {
        val items = listOf(item("z/x.jpg"), item("a/x.jpg"))
        assertEquals(listOf("a/x.jpg", "z/x.jpg"), names(sortItems(items, SortSpec(SortOrder.Name))))
    }

    @Test fun pathGroupsBySubfolderThenName() {
        val items = listOf(item("trip/b.jpg"), item("z.jpg"), item("trip/a.jpg"), item("a.jpg"), item("trip/day2/c.jpg"))
        assertEquals(
            listOf("a.jpg", "z.jpg", "trip/a.jpg", "trip/b.jpg", "trip/day2/c.jpg"),
            names(sortItems(items, SortSpec(SortOrder.Path))),
        )
    }

    @Test fun pathOrdersSegmentsNaturallyNotByRawCharacters() {
        val items = listOf(item("a-b/1.jpg"), item("a/sub/1.jpg"), item("a/1.jpg"))
        assertEquals(listOf("a/1.jpg", "a/sub/1.jpg", "a-b/1.jpg"), names(sortItems(items, SortSpec(SortOrder.Path))))
    }

    @Test fun modifiedSortsByTimeThenName() {
        val items = listOf(item("c.jpg", modifiedMs = 5), item("b.jpg", modifiedMs = 1), item("a.jpg", modifiedMs = 5))
        assertEquals(listOf("b.jpg", "a.jpg", "c.jpg"), names(sortItems(items, SortSpec(SortOrder.Modified))))
        assertEquals(listOf("c.jpg", "a.jpg", "b.jpg"), names(sortItems(items, SortSpec(SortOrder.Modified, ascending = false))))
    }

    @Test fun sizeSortsBySizeThenName() {
        val items = listOf(item("b.jpg", sizeBytes = 10), item("a.jpg", sizeBytes = 10), item("c.jpg", sizeBytes = 1))
        assertEquals(listOf("c.jpg", "a.jpg", "b.jpg"), names(sortItems(items, SortSpec(SortOrder.Size))))
    }

    @Test fun randomIsStableForASeedRegardlessOfInputOrder() {
        val items = (1..20).map { item("img$it.jpg") }
        val spec = SortSpec(SortOrder.Random, randomSeed = 42)
        assertEquals(sortItems(items, spec), sortItems(items.reversed(), spec))
    }

    @Test fun randomDiffersForAnotherSeed() {
        val items = (1..20).map { item("img$it.jpg") }
        assertNotEquals(
            sortItems(items, SortSpec(SortOrder.Random, randomSeed = 1)),
            sortItems(items, SortSpec(SortOrder.Random, randomSeed = 2)),
        )
    }

    @Test fun randomIgnoresDirection() {
        val items = (1..20).map { item("img$it.jpg") }
        assertEquals(
            sortItems(items, SortSpec(SortOrder.Random, ascending = true, randomSeed = 7)),
            sortItems(items, SortSpec(SortOrder.Random, ascending = false, randomSeed = 7)),
        )
    }
}
