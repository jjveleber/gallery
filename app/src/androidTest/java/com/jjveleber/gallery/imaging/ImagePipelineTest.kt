package com.jjveleber.gallery.imaging

import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
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

    /** A [frames]-frame GIF of one-color frames, LZW compressed so the file stays small at any canvas size. */
    private fun solidGif(width: Int, height: Int, frames: Int, loopBlock: Boolean = true): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun short(v: Int) { out.write(v and 0xff); out.write(v shr 8) }
        out.write("GIF89a".toByteArray())
        short(width); short(height)
        out.write(0x80 or 0x01); out.write(0); out.write(0) // 4-entry global color table
        for (c in listOf(0xff0000, 0x00ff00, 0x0000ff, 0xffffff)) { out.write(c shr 16); out.write(c shr 8); out.write(c) }
        if (loopBlock) out.write(byteArrayOf(0x21, 0xff.toByte(), 0x0b) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0))
        repeat(frames) { frame ->
            out.write(byteArrayOf(0x21, 0xf9.toByte(), 4, 0, 10, 0, 0, 0)) // 100 ms per frame
            out.write(0x2c); short(0); short(0); short(width); short(height); out.write(0)
            out.write(2) // minimum LZW code size
            val data = lzw(ByteArray(width * height) { frame.toByte() })
            for (i in data.indices step 255) {
                val n = minOf(255, data.size - i)
                out.write(n); out.write(data, i, n)
            }
            out.write(0)
        }
        out.write(0x3b)
        return out.toByteArray()
    }

    private fun lzw(pixels: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var bits = 0; var nbits = 0
        var size = 3
        fun emit(code: Int) {
            bits = bits or (code shl nbits); nbits += size
            while (nbits >= 8) { out.write(bits and 0xff); bits = bits shr 8; nbits -= 8 }
        }
        val clear = 4; val eoi = 5
        val table = HashMap<Int, Int>()
        var next = 6
        emit(clear)
        var prefix = pixels[0].toInt()
        for (i in 1 until pixels.size) {
            val key = (prefix shl 8) or pixels[i].toInt()
            val code = table[key]
            if (code != null) { prefix = code; continue }
            emit(prefix)
            table[key] = next++
            if (next > (1 shl size) && size < 12) size++
            if (next == 4096) { emit(clear); table.clear(); next = 6; size = 3 }
            prefix = pixels[i].toInt()
        }
        emit(prefix); emit(eoi)
        if (nbits > 0) out.write(bits and 0xff)
        return out.toByteArray()
    }

    // APNG4Android played a GIF without a NETSCAPE block once and one with loop count 0 forever; the platform drawable
    // must do the same (AnimatedImageDrawable.REPEAT_INFINITE is -1, a single play is 0).
    @Test fun gifLoopCountMatchesWhatApng4AndroidDid() {
        for ((loopBlock, expected) in listOf(true to AnimatedImageDrawable.REPEAT_INFINITE, false to 0)) {
            val file = File(context.cacheDir, "pipeline-loop$loopBlock.gif").apply { writeBytes(solidGif(16, 16, 2, loopBlock)) }
            val drawable = success(load(Uri.fromFile(file).toString(), animate = true)).image.asDrawable(context.resources)
            assertEquals("loopBlock=$loopBlock", expected, (drawable as AnimatedImageDrawable).repeatCount)
        }
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

    // APNG4Android 3.0.5's GifDecoder always uses sample size 1, so GIF goes through the platform ImageDecoder instead.
    @Test fun animatedGifDecodesAtRequestSize() {
        val request = imageRequest(context, copyAsset("anim.gif"), animate = true)
            .newBuilder().size(Size(16, 12)).build()
        val drawable = success(runBlocking { loader.execute(request) }).image.asDrawable(context.resources)
        assertTrue("expected AnimatedImageDrawable but got $drawable", drawable is AnimatedImageDrawable)
        assertEquals(16, drawable.intrinsicWidth)
        assertEquals(12, drawable.intrinsicHeight)
    }

    // APNG4Android allocated about 25 MB of Java heap per GIF for a 1920x1080 canvas whatever the request size,
    // which ran the grid out of memory.
    @Test fun largeAnimatedGifsStayOffTheJavaHeap() {
        val file = File(context.cacheDir, "pipeline-large.gif").apply { writeBytes(solidGif(1920, 1080, frames = 3)) }
        val uri = Uri.fromFile(file).toString()
        val runtime = Runtime.getRuntime()
        fun usedHeap(): Long {
            System.gc()
            return runtime.totalMemory() - runtime.freeMemory()
        }
        val before = usedHeap()
        val held = mutableListOf<Any>()
        repeat(12) {
            val request = imageRequest(context, uri, animate = true).newBuilder().size(192).build()
            val drawable = success(runBlocking { loader.execute(request) }).image.asDrawable(context.resources)
            held += drawable
            InstrumentationRegistry.getInstrumentation().runOnMainSync { (drawable as Animatable).start() }
        }
        Thread.sleep(2000) // let every decoder reach its first frame
        val growthMb = (usedHeap() - before) / (1024 * 1024)
        assertTrue("Java heap grew ${growthMb}MB for ${held.size} large GIFs", growthMb < 40)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { held.forEach { (it as Animatable).stop() } }
    }

    // APNG4Android tolerates a header-only APNG (acTL but no frames), so this cannot reach decodeStill;
    // it only pins that such a file ends in a clean result instead of a crash or hang.
    @Test fun headerOnlyAnimatedFileEndsCleanly() {
        val result = load(copyAsset("truncated.png"), animate = true)
        assertTrue("unexpected $result", result is SuccessResult || result is ErrorResult)
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
