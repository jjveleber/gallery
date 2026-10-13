package com.jjveleber.gallery.imaging

import android.graphics.drawable.Animatable
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.asDrawable
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Size
import com.github.penfeizhou.animation.FrameAnimationDrawable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImagePipelineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val registry = AnimatedRegistry()
    private val loader = buildImageLoader(context, registry)

    private fun copyAsset(name: String): String {
        val file = File(context.cacheDir, "pipeline-$name")
        testAssets.open("images/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file).toString()
    }

    private fun load(uri: String, animate: Boolean): ImageResult = runBlocking {
        loader.execute(imageRequest(context, uri, animate))
    }

    private fun success(result: ImageResult): SuccessResult {
        assertTrue("expected success but got $result", result is SuccessResult)
        return result as SuccessResult
    }

    @Test fun animatedFilesDecodeAsAnimatableWhenAllowed() {
        for (name in listOf("anim.gif", "anim.png", "anim.webp")) {
            val image = success(load(copyAsset(name), animate = true)).image
            val drawable = image.asDrawable(context.resources)
            assertTrue("$name should be animatable", drawable is Animatable)
            assertEquals("$name width", 64, drawable.intrinsicWidth)
            assertEquals("$name height", 48, drawable.intrinsicHeight)
        }
    }

    // APNG4Android 3.0.5's GifDecoder overrides getDesiredSample to always return 1, so GIF cannot be sampled.
    @Test fun animatedDecodeHonorsRequestSize() {
        for (name in listOf("anim.png", "anim.webp")) {
            val request = imageRequest(context, copyAsset(name), animate = true)
                .newBuilder().size(Size(16, 12)).build()
            val result = success(runBlocking { loader.execute(request) })
            val drawable = result.image.asDrawable(context.resources) as FrameAnimationDrawable<*>
            val decoder = drawable.frameSeqDecoder
            // The sample size is applied on the decoder's worker thread, so poll briefly.
            val deadline = System.currentTimeMillis() + 3000
            while (decoder.sampleSize <= 1 && System.currentTimeMillis() < deadline) Thread.sleep(25)
            assertTrue("$name should be sampled down for a 16x12 request", decoder.sampleSize > 1)
        }
    }

    @Test fun animatedFilesDecodeAsStillWhenNotAllowed() {
        for (name in listOf("anim.gif", "anim.png", "anim.webp")) {
            val image = success(load(copyAsset(name), animate = false)).image
            assertTrue("$name should be a bitmap", image is BitmapImage)
            assertEquals(64, image.width)
            assertEquals(48, image.height)
        }
    }

    @Test fun staticFormatsDecode() {
        for (name in listOf("still.gif", "still.png", "still.webp", "still.jpg", "still.bmp")) {
            val image = success(load(copyAsset(name), animate = true)).image
            assertTrue("$name should be a bitmap", image is BitmapImage)
            assertEquals("$name width", 64, image.width)
        }
    }

    @Test fun registryRecordsWhatWasSniffed() {
        val anim = copyAsset("anim.gif")
        val still = copyAsset("still.png")
        load(anim, animate = false)
        load(still, animate = false)
        assertTrue(registry.isAnimated(anim))
        assertFalse(registry.isAnimated(still))
    }

    @Test fun exifRotationIsApplied() {
        val image = success(load(copyAsset("rotated.jpg"), animate = false)).image
        assertEquals(20, image.width)
        assertEquals(40, image.height)
    }

    @Test fun corruptFileFails() {
        assertTrue(load(copyAsset("corrupt.jpg"), animate = true) is ErrorResult)
    }
}
