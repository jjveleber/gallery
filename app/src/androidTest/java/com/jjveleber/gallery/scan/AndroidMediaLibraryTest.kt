package com.jjveleber.gallery.scan

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.model.PRIMARY_VOLUME
import com.jjveleber.gallery.testing.MediaFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMediaLibraryTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val run = System.nanoTime()
    private val folder = "Pictures/gt_$run/"
    private val lookAlike = "Pictures/gtX$run/"
    private val inserted = mutableListOf<Uri>()

    @Before fun seed() {
        inserted += MediaFixtures.insert("still.jpg", folder, "a.jpg", "image/jpeg")
        inserted += MediaFixtures.insert("still.png", "${folder}sub/", "b.png", "image/png")
        inserted += MediaFixtures.insert("still.jpg", lookAlike, "c.jpg", "image/jpeg")
    }

    @After fun cleanUp() = MediaFixtures.delete(inserted)

    @Test fun underscoreInPathIsLiteral() {
        val rows = AndroidMediaRowSource(resolver).rows(PRIMARY_VOLUME, folder)
        assertEquals(setOf("a.jpg", "b.png"), rows.map { it.name }.toSet())
    }

    @Test fun imagesAreRecursiveWithRelativePaths() = runBlocking {
        val items = AndroidMediaLibrary(resolver).images(FolderRef(PRIMARY_VOLUME, folder))
        assertEquals(listOf("a.jpg", "sub/b.png"), items.map { it.relPath }.sorted())
        assertTrue(items.all { it.uri.startsWith("content://media/") })
    }

    // Counts are recursive: the parent folder counts the image in sub/ too. Ancestors such as Pictures/ also get rows,
    // but their paths do not contain "$run/", so the filter leaves them out.
    @Test fun foldersListEachFolderWithItsRecursiveCount() = runBlocking {
        val mine = AndroidMediaLibrary(resolver).folders().filter { it.ref.relativePath.contains("$run/") }
        assertEquals(
            setOf(folder to 2, "${folder}sub/" to 1, lookAlike to 1),
            mine.map { it.ref.relativePath to it.count }.toSet(),
        )
        assertTrue(mine.all { it.ref.volume == PRIMARY_VOLUME && it.coverUri.startsWith("content://media/") })
    }
}
