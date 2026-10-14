package com.jjveleber.gallery.imaging

import android.graphics.drawable.Animatable
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.asDrawable
import coil3.decode.DataSource
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.jjveleber.gallery.model.ImageItem
import com.jjveleber.gallery.testing.MediaFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaThumbnailTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val registry = AnimatedRegistry()
    private val loader = buildImageLoader(context, registry)
    private val folder = "Pictures/gthumb${System.nanoTime()}/"
    private val inserted = mutableListOf<Uri>()

    @After fun cleanUp() = MediaFixtures.delete(inserted)

    private fun insert(asset: String, mime: String): Uri = MediaFixtures.insert(asset, folder, asset, mime).also { inserted += it }

    private fun load(uri: Uri, mime: String, animate: Boolean): ImageResult = runBlocking {
        val item = ImageItem(uri.toString(), "x", "x", 0, 0, mime)
        loader.execute(gridRequest(context, item, animate, sizePx = 256))
    }

    private fun success(result: ImageResult): SuccessResult {
        assertTrue("expected success but got $result", result is SuccessResult)
        return result as SuccessResult
    }

    // rotated.jpg is stored 40x20 with an EXIF tag that rotates it to portrait.
    @Test fun systemThumbnailIsUpright() {
        val image = success(load(insert("rotated.jpg", "image/jpeg"), "image/jpeg", animate = false)).image
        assertTrue("expected portrait, got ${image.width}x${image.height}", image.height > image.width)
    }

    @Test fun animatedFileAnimatesWhenAllowed() {
        val uri = insert("anim.gif", "image/gif")
        val drawable = success(load(uri, "image/gif", animate = true)).image.asDrawable(context.resources)
        assertTrue("expected an animation, got $drawable", drawable is Animatable)
    }

    @Test fun animatedFileIsStillAndRecordedWhenNotAllowed() {
        val uri = insert("anim.webp", "image/webp")
        val image = success(load(uri, "image/webp", animate = false)).image
        assertTrue("expected a bitmap, got $image", image is BitmapImage)
        assertTrue(registry.isAnimated(uri.toString()))
    }

    @Test fun staticPngIsRecordedAsStatic() {
        val uri = insert("still.png", "image/png")
        success(load(uri, "image/png", animate = true))
        assertTrue(registry.isStatic(uri.toString()))
    }

    // loadThumbnail can return a bitmap larger than requested; INEXACT precision keeps it a memory cache hit.
    @Test fun secondIdenticalGridRequestIsServedFromMemoryCache() {
        val uri = insert("rotated.jpg", "image/jpeg")
        success(load(uri, "image/jpeg", animate = false))
        assertEquals(DataSource.MEMORY_CACHE, success(load(uri, "image/jpeg", animate = false)).dataSource)
    }
}
