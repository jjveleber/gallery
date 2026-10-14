package com.jjveleber.gallery.dupes

import com.jjveleber.gallery.testing.item
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HashCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun sha256OfKnownInput() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".byteInputStream()),
        )
    }

    @Test fun savedEntriesSurviveANewInstance() = runTest {
        val file = tmp.root.resolve("hashes")
        val cache = HashCache(file)
        val key = cache.key(item("a.jpg", modifiedMs = 5, sizeBytes = 10))
        cache.put(key, "h1")
        cache.save()
        assertEquals("h1", HashCache(file).get(key))
    }

    @Test fun keyChangesWhenTheFileChanges() {
        val cache = HashCache(tmp.root.resolve("hashes"))
        val before = cache.key(item("a.jpg", modifiedMs = 5, sizeBytes = 10))
        assertNotEquals(before, cache.key(item("a.jpg", modifiedMs = 6, sizeBytes = 10)))
        assertNotEquals(before, cache.key(item("a.jpg", modifiedMs = 5, sizeBytes = 11)))
    }

    @Test fun missingFileIsAnEmptyCacheAndSaveWithoutChangesWritesNothing() = runTest {
        val file = tmp.root.resolve("hashes")
        val cache = HashCache(file)
        assertNull(cache.get("x"))
        cache.save()
        assertFalse(file.exists())
    }
}
