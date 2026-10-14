package com.jjveleber.gallery.scan

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderWalkTest {
    /** A fake tree: folder id to its children. Folder ids are their relative paths ("" is the root). */
    private class FakeSource(private val tree: Map<String, List<DocEntry>>) : DocumentSource {
        val listed = mutableListOf<String>()
        override fun children(folderId: String): List<DocEntry> {
            listed += folderId
            return tree[folderId].orEmpty()
        }
        override fun uriOf(docId: String) = "content://tree/$docId"
    }

    private fun file(id: String, mime: String? = "image/jpeg") =
        DocEntry(id = id, name = id.substringAfterLast('/'), mime = mime, sizeBytes = 1, modifiedMs = 2, isDir = false)

    private fun dir(id: String) =
        DocEntry(id = id, name = id.substringAfterLast('/'), mime = "vnd.android.document/directory", sizeBytes = 0, modifiedMs = 0, isDir = true)

    private val tree = mapOf(
        "" to listOf(file("a.jpg"), dir("trip"), file("notes.txt", "text/plain")),
        "trip" to listOf(file("trip/b.png", "image/png"), dir("trip/day2")),
        "trip/day2" to listOf(file("trip/day2/c.gif", "image/gif")),
    )

    @Test fun recursesIntoSubfoldersWithRelativePaths() = runBlocking {
        val items = walkImages(FakeSource(tree), rootId = "", recursive = true)
        assertEquals(listOf("a.jpg", "trip/b.png", "trip/day2/c.gif"), items.map { it.relPath })
        val c = items.last()
        assertEquals("c.gif", c.name)
        assertEquals("content://tree/trip/day2/c.gif", c.uri)
        assertEquals("image/gif", c.mime)
        assertEquals(1L, c.sizeBytes)
        assertEquals(2L, c.modifiedMs)
    }

    @Test fun nonRecursiveIgnoresSubfolders() = runBlocking {
        val source = FakeSource(tree)
        val items = walkImages(source, rootId = "", recursive = false)
        assertEquals(listOf("a.jpg"), items.map { it.relPath })
        assertEquals(listOf(""), source.listed)
    }

    @Test fun skipsHiddenFilesAndFolders() = runBlocking {
        val hidden = mapOf(
            "" to listOf(file("a.jpg"), dir(".thumbnails"), dir(".trashed-123"), file(".hidden.jpg"), file(".nomedia", null)),
            ".thumbnails" to listOf(file(".thumbnails/t1.jpg")),
            ".trashed-123" to listOf(file(".trashed-123/old.jpg")),
        )
        val source = FakeSource(hidden)
        val items = walkImages(source, rootId = "", recursive = true)
        assertEquals(listOf("a.jpg"), items.map { it.relPath })
        assertEquals(listOf(""), source.listed)
    }

    @Test fun acceptsGenericMimeWithImageExtension() = runBlocking {
        val items = walkImages(FakeSource(mapOf("" to listOf(file("IMG.JPG", "application/octet-stream")))), "", recursive = true)
        assertEquals(1, items.size)
    }

    @Test fun reportsProgressAfterEachFolder() = runBlocking {
        val counts = mutableListOf<Int>()
        walkImages(FakeSource(tree), rootId = "", recursive = true) { counts += it }
        assertEquals(listOf(1, 2, 3), counts)
    }

    @Test fun emptyFolderGivesNoItems() = runBlocking {
        assertTrue(walkImages(FakeSource(emptyMap()), rootId = "", recursive = true).isEmpty())
    }
}
