package com.jjveleber.gallery.dupes

import com.jjveleber.gallery.model.ImageItem
import com.jjveleber.gallery.testing.FakeHasher
import com.jjveleber.gallery.testing.item
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatesTest {
    private val hasher = FakeHasher()

    private fun file(path: String, size: Long, content: String): ImageItem =
        item(path, sizeBytes = size).also { hasher.contents[it.uri] = content }

    @Test fun marksEveryTargetOfAGroupWithTheOthers() = runTest {
        val a = file("a.jpg", 10, "x")
        val b = file("b.jpg", 10, "x")
        val c = file("c.jpg", 10, "x")
        val result = findDuplicates(listOf(a, b, c), listOf(a, b, c), hasher)
        assertEquals(listOf(b, c), result.getValue(a.uri).others)
        assertEquals(listOf(a, c), result.getValue(b.uri).others)
        assertEquals(result.getValue(a.uri).hash, result.getValue(c.uri).hash)
    }

    @Test fun sameSizeDifferentBytesIsNotADuplicate() = runTest {
        val a = file("a.jpg", 10, "x")
        val b = file("b.jpg", 10, "y")
        assertTrue(findDuplicates(listOf(a, b), listOf(a, b), hasher).isEmpty())
    }

    @Test fun findsCopiesOutsideTheTargets() = runTest {
        val a = file("Trip/a.jpg", 10, "x")
        val elsewhere = file("Old/a-copy.jpg", 10, "x")
        val result = findDuplicates(listOf(a), listOf(a, elsewhere), hasher)
        assertEquals(setOf(a.uri), result.keys)
        assertEquals(listOf(elsewhere), result.getValue(a.uri).others)
    }

    @Test fun onlyHashesFilesThatShareASizeWithATarget() = runTest {
        val a = file("a.jpg", 10, "x")
        val b = file("b.jpg", 11, "x")
        val other1 = file("o1.jpg", 12, "z")
        val other2 = file("o2.jpg", 12, "z")
        val result = findDuplicates(listOf(a, b), listOf(a, b, other1, other2), hasher)
        assertTrue(result.isEmpty())
        assertTrue(hasher.hashed.isEmpty())
    }

    @Test fun skipsUnknownSizesAndUnreadableFiles() = runTest {
        val a = file("a.jpg", 0, "x")
        val b = file("b.jpg", 0, "x")
        val c = file("c.jpg", 5, "x")
        val unreadable = item("d.jpg", sizeBytes = 5)
        assertTrue(findDuplicates(listOf(a, b, c, unreadable), listOf(a, b, c, unreadable), hasher).isEmpty())
        assertEquals(listOf(c.uri, unreadable.uri), hasher.hashed)
    }

    @Test fun reportsProgress() = runTest {
        val a = file("a.jpg", 10, "x")
        val b = file("b.jpg", 10, "x")
        val progress = mutableListOf<Pair<Int, Int>>()
        findDuplicates(listOf(a, b), listOf(a, b), hasher) { done, total -> progress += done to total }
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
    }

    @Test fun extraCopiesKeepsTheFirstOfEachGroupInTheList() = runTest {
        val a1 = file("a1.jpg", 10, "x")
        val b1 = file("b1.jpg", 10, "y")
        val a2 = file("a2.jpg", 10, "x")
        val a3 = file("a3.jpg", 10, "x")
        val b2 = file("b2.jpg", 10, "y")
        val unique = file("u.jpg", 11, "u")
        val list = listOf(b1, a2, unique, a1, a3, b2)
        val copies = findDuplicates(list, list, hasher)
        assertEquals(setOf(a1.uri, a3.uri, b2.uri), extraCopies(list, copies))
    }

    @Test fun extraCopiesIgnoresCopiesOutsideTheList() = runTest {
        val a = file("Trip/a.jpg", 10, "x")
        val elsewhere = file("Old/a.jpg", 10, "x")
        val copies = findDuplicates(listOf(a), listOf(a, elsewhere), hasher)
        assertTrue(extraCopies(listOf(a), copies).isEmpty())
    }
}
