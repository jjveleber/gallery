package com.jjveleber.gallery.scan

import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.model.PRIMARY_VOLUME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFoldersTest {
    /** Returns every row whatever the query, like a provider that ignores the selection: callers must filter. */
    private class FakeRows(private val rows: List<MediaRow>) : MediaRowSource {
        val calls = mutableListOf<Pair<String?, String>>()
        override fun rows(volume: String?, pathPrefix: String): List<MediaRow> {
            calls += volume to pathPrefix
            return rows
        }
        override fun uriOf(row: MediaRow) = "content://media/${row.volume}/images/media/${row.id}"
    }

    private var nextId = 1L

    /** [path] is "relative/dir/name.ext". */
    private fun row(path: String, volume: String = PRIMARY_VOLUME, mime: String? = "image/jpeg", modifiedMs: Long = 0): MediaRow {
        val dir = path.substringBeforeLast('/', "")
        return MediaRow(nextId++, volume, if (dir.isEmpty()) "" else "$dir/", path.substringAfterLast('/'), mime, 10, modifiedMs)
    }

    @Test fun likePrefixEscapesWildcards() {
        assertEquals("Pictures/My\\_Photos/50\\%/%", likePrefix("Pictures/My_Photos/50%/"))
        assertEquals("a\\\\b/%", likePrefix("a\\b/"))
        assertEquals("%", likePrefix(""))
    }

    @Test fun hiddenPathsAreDetected() {
        assertTrue(isHiddenPath("Pictures/.thumbnails/", "a.jpg"))
        assertTrue(isHiddenPath(".trashed/x/", "a.jpg"))
        assertTrue(isHiddenPath("Pictures/", ".a.jpg"))
        assertFalse(isHiddenPath("Pictures/a.b/", "c.jpg"))
    }

    @Test fun listFoldersGroupsWithCountAndNewestCover() {
        val newest = row("Pictures/Trip/b.jpg", modifiedMs = 50)
        val source = FakeRows(listOf(row("Pictures/Trip/a.jpg", modifiedMs = 10), newest, row("Pictures/Trip/day2/c.png", mime = "image/png")))
        val folders = listFolders(source)
        assertEquals(
            listOf(
                FolderRef(PRIMARY_VOLUME, "Pictures/") to 3,
                FolderRef(PRIMARY_VOLUME, "Pictures/Trip/") to 3,
                FolderRef(PRIMARY_VOLUME, "Pictures/Trip/day2/") to 1,
            ),
            folders.map { it.ref to it.count },
        )
        assertEquals(source.uriOf(newest), folders[0].coverUri)
        assertEquals(source.uriOf(newest), folders[1].coverUri)
        assertEquals("image/jpeg", folders[1].coverMime)
        assertEquals(listOf<Pair<String?, String>>(null to ""), source.calls)
    }

    @Test fun listFoldersSkipsHiddenAndUnsupported() {
        val source = FakeRows(
            listOf(
                row("Pictures/.thumbnails/t.jpg"),
                row(".trashed/old/x.jpg"),
                row("Pictures/Shown/.hidden.jpg"),
                row("Pictures/Docs/scan.tiff", mime = "image/tiff"),
                row("Pictures/Shown/ok.jpg"),
            ),
        )
        assertEquals(listOf("Pictures/", "Pictures/Shown/"), listFolders(source).map { it.ref.relativePath })
    }

    @Test fun ancestorWithoutDirectImagesGetsRecursiveRowAndRootGetsNone() {
        val deep = row("DCIM/Camera/2024/a.jpg", modifiedMs = 5)
        val source = FakeRows(listOf(deep, row("DCIM/Camera/2024/.hidden/b.jpg", modifiedMs = 99), row("top.jpg")))
        val folders = listFolders(source)
        assertEquals(
            listOf("DCIM/" to 1, "DCIM/Camera/" to 1, "DCIM/Camera/2024/" to 1),
            folders.map { it.ref.relativePath to it.count },
        )
        assertTrue(folders.all { it.coverUri == source.uriOf(deep) })
    }

    @Test fun listFoldersSortsByPathNaturally() {
        val source = FakeRows(listOf(row("Pictures/img10/a.jpg"), row("Pictures/img2/a.jpg"), row("DCIM/Camera/a.jpg")))
        assertEquals(
            listOf("DCIM/", "DCIM/Camera/", "Pictures/", "Pictures/img2/", "Pictures/img10/"),
            listFolders(source).map { it.ref.relativePath },
        )
    }

    @Test fun sameFolderOnTwoVolumesStaysSeparate() {
        val source = FakeRows(listOf(row("DCIM/Camera/a.jpg"), row("DCIM/Camera/b.jpg", volume = "1a2b-3c4d")))
        assertEquals(
            listOf(
                FolderRef("1a2b-3c4d", "DCIM/") to 1,
                FolderRef(PRIMARY_VOLUME, "DCIM/") to 1,
                FolderRef("1a2b-3c4d", "DCIM/Camera/") to 1,
                FolderRef(PRIMARY_VOLUME, "DCIM/Camera/") to 1,
            ),
            listFolders(source).map { it.ref to it.count },
        )
    }

    @Test fun imagesInIsRecursiveWithRelativePaths() {
        val b = row("Pictures/Trip/day2/b.png", mime = "image/png", modifiedMs = 7)
        val source = FakeRows(listOf(row("Pictures/Trip/a.jpg"), b, row("Pictures/Other/c.jpg")))
        val items = imagesIn(source, FolderRef(PRIMARY_VOLUME, "Pictures/Trip/"))
        assertEquals(listOf("a.jpg", "day2/b.png"), items.map { it.relPath })
        val item = items[1]
        assertEquals("b.png", item.name)
        assertEquals(source.uriOf(b), item.uri)
        assertEquals("image/png", item.mime)
        assertEquals(10L, item.sizeBytes)
        assertEquals(7L, item.modifiedMs)
        assertEquals(listOf<Pair<String?, String>>(PRIMARY_VOLUME to "Pictures/Trip/"), source.calls)
    }

    @Test fun imagesInIgnoresLookAlikeFolders() {
        val source = FakeRows(listOf(row("Pictures/My_Photos/a.jpg"), row("Pictures/MyXPhotos/b.jpg"), row("Pictures/My_Photos2/c.jpg")))
        assertEquals(listOf("a.jpg"), imagesIn(source, FolderRef(PRIMARY_VOLUME, "Pictures/My_Photos/")).map { it.relPath })
    }

    @Test fun imagesInDoesNotMatchLongerSibling() {
        val source = FakeRows(listOf(row("Pictures/Trip/a.jpg"), row("Pictures/Trip2/b.jpg")))
        assertEquals(listOf("a.jpg"), imagesIn(source, FolderRef(PRIMARY_VOLUME, "Pictures/Trip/")).map { it.relPath })
    }

    @Test fun imagesInSkipsOtherVolumesHiddenAndUnsupported() {
        val source = FakeRows(
            listOf(
                row("DCIM/Camera/a.jpg"),
                row("DCIM/Camera/b.jpg", volume = "1a2b-3c4d"),
                row("DCIM/Camera/.thumbnails/t.jpg"),
                row("DCIM/Camera/clip.mp4", mime = "video/mp4"),
            ),
        )
        assertEquals(listOf("a.jpg"), imagesIn(source, FolderRef(PRIMARY_VOLUME, "DCIM/Camera/")).map { it.relPath })
    }

    @Test fun imagesInMatchesPathCaseInsensitivelyLikeTheStorage() {
        val source = FakeRows(listOf(row("pictures/trip/a.jpg")))
        assertEquals(listOf("a.jpg"), imagesIn(source, FolderRef(PRIMARY_VOLUME, "Pictures/Trip/")).map { it.relPath })
    }

    @Test fun allImagesHasFullPathsMarksSdCardAndSkipsHidden() {
        val source = FakeRows(
            listOf(
                row("DCIM/Camera/a.jpg"),
                row("Pictures/b.jpg", volume = "1a2b-3c4d"),
                row("root.jpg"),
                row(".hidden/c.jpg"),
                row("DCIM/clip.mp4", mime = "video/mp4"),
            ),
        )
        assertEquals(listOf("DCIM/Camera/a.jpg", "SD card: Pictures/b.jpg", "root.jpg"), allImages(source).map { it.relPath })
        assertEquals(listOf(null to ""), source.calls)
    }
}
