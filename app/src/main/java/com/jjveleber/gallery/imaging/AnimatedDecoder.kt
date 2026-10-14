package com.jjveleber.gallery.imaging

import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.ParcelFileDescriptor
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.request.Options
import coil3.size.pxOrElse
import com.github.penfeizhou.animation.FrameAnimationDrawable
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import com.github.penfeizhou.animation.loader.FileLoader
import com.github.penfeizhou.animation.loader.Loader
import com.github.penfeizhou.animation.loader.StreamLoader
import com.github.penfeizhou.animation.webp.WebPDrawable
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import okio.buffer
import okio.source

/**
 * A grid cell shows a still for a GIF whose canvas has more pixels than this. The platform decoder keeps full-canvas
 * native frames whatever the target size (about 10 MB for a 1080p GIF), so a grid of large GIFs exhausts memory.
 */
const val GRID_GIF_MAX_PIXELS = 1_000_000L

/**
 * Decodes animated GIF with the platform ImageDecoder, which decodes frames at the request size, and animated WebP
 * and APNG with APNG4Android, which handles WebP blend/dispose frames and APNG that the platform cannot animate.
 * APNG4Android's GIF decoder always allocates full-canvas Java-heap buffers, which runs a grid of large GIFs out of
 * memory. Neither path holds a copy of the file: both read it from disk, except a GIF or WebP with frame delays too
 * short to play as written (see [FrameDelays]), which is decoded from a fixed copy in memory. Still images are left
 * to Coil's decoders.
 */
class AnimatedDecoder(
    private val source: ImageSource,
    private val kind: ImageKind,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        if (kind == ImageKind.AnimatedGif) {
            try {
                return DecodeResult(decodeGif().asImage(shareable = false), isSampled = true)
            } catch (_: IOException) {
                return DecodeResult(decodeStill(), isSampled = true)
            }
        }
        val drawable = when (kind) {
            ImageKind.AnimatedWebp -> WebPDrawable(loader())
            ImageKind.Apng -> APNGDrawable(loader())
            else -> error("$kind is not routed to APNG4Android")
        }
        // intrinsicWidth parses the header on APNG4Android's worker thread; 0 means it could not read the file.
        var width = drawable.intrinsicWidth
        var height = drawable.intrinsicHeight
        if (width <= 0 || height <= 0) {
            // getBounds() parks once for the header parse, so a spurious wakeup can return an empty rect while the
            // parse is still queued. After a real failure fullRect is already set and this returns at once; after a
            // spurious wakeup the serial worker handler makes this call wait for the pending parse.
            val bounds = drawable.frameSeqDecoder.bounds
            width = bounds.width()
            height = bounds.height()
        }
        return if (width > 0 && height > 0) {
            applyRequestSize(drawable, width, height)
            DecodeResult(drawable.asImage(shareable = false), isSampled = false)
        } else {
            DecodeResult(decodeStill(), isSampled = true)
        }
    }

    /** A platform decoder source that reads the file itself: its path when it has one, else its URI. */
    private fun imageDecoderSource(): ImageDecoder.Source =
        source.fileOrNull()?.let { ImageDecoder.createSource(it.toFile()) }
            ?: ImageDecoder.createSource(options.context.contentResolver, Uri.parse(options.getExtra(SourceKey)))

    /** An [AnimatedImageDrawable] whose frames are decoded at the request size. */
    private fun decodeGif(): Drawable {
        val src = delayFixedBytes()?.let { ImageDecoder.createSource(ByteBuffer.wrap(it)) } ?: imageDecoderSource()
        return ImageDecoder.decodeDrawable(src) { decoder, info, _ -> decoder.fitToRequest(info.size.width, info.size.height) }
    }

    /** A new stream over the file: its path when it has one, else its URI. */
    private fun openFile(): InputStream =
        source.fileOrNull()?.let { FileInputStream(it.toFile()) }
            ?: options.getExtra(SourceKey).let { options.context.contentResolver.openInputStream(Uri.parse(it)) ?: throw FileNotFoundException(it) }

    /**
     * The file's bytes with its too-short frame delays raised (see [FrameDelays]), or null when it has none (or cannot
     * be read for the check), so the decoder reads the file itself. Finding them reads the file once more.
     */
    private fun delayFixedBytes(): ByteArray? = try {
        val gif = kind == ImageKind.AnimatedGif
        val offsets = openFile().source().buffer().use { if (gif) FrameDelays.shortGifDelays(it) else FrameDelays.shortWebpDurations(it) }
        if (offsets.isEmpty()) null
        else openFile().use { it.readBytes() }.also { if (gif) FrameDelays.fixGif(it, offsets) else FrameDelays.fixWebp(it, offsets) }
    } catch (e: IOException) {
        null
    }

    /**
     * Reads from the file instead of a heap copy, unless its frame delays need fixing. APNG4Android restarts by
     * calling [Loader.obtain] again.
     */
    private fun loader(): Loader {
        if (kind == ImageKind.AnimatedWebp) {
            delayFixedBytes()?.let { bytes ->
                return object : ByteBufferLoader() {
                    override fun getByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
                }
            }
        }
        val file = source.fileOrNull()
        if (file != null) return FileLoader(file.toString())
        val resolver = options.context.contentResolver
        val uri = Uri.parse(options.getExtra(SourceKey))
        return object : StreamLoader() {
            override fun getInputStream(): InputStream =
                SeekableFileStream(resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString()))
        }
    }

    /** The scale that fits [width] x [height] inside the request without upscaling. */
    private fun fitScale(width: Int, height: Int): Float {
        val maxWidth = options.size.width.pxOrElse { width }
        val maxHeight = options.size.height.pxOrElse { height }
        return minOf(1f, maxWidth.toFloat() / width, maxHeight.toFloat() / height)
    }

    private fun ImageDecoder.fitToRequest(width: Int, height: Int) {
        val scale = fitScale(width, height)
        setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
    }

    /**
     * Makes APNG4Android decode frames at the request size instead of full resolution. The drawable's bounds drive
     * the decoder's sample size, so size them to fit the request while keeping the aspect ratio.
     */
    private fun applyRequestSize(drawable: FrameAnimationDrawable<*>, width: Int, height: Int) {
        val scale = fitScale(width, height)
        drawable.setBounds(0, 0, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
    }

    /** Fallback when the animated decode fails: the platform's first frame at the requested size. */
    private fun decodeStill(): Image =
        ImageDecoder.decodeBitmap(imageDecoderSource()) { decoder, info, _ -> decoder.fitToRequest(info.size.width, info.size.height) }.asImage()

    /**
     * APNG4Android rewinds its stream with reset() before every frame, which ContentResolver streams do not support.
     * This one reads the file descriptor and rewinds by seeking to the start.
     */
    private class SeekableFileStream(private val pfd: ParcelFileDescriptor) : FileInputStream(pfd.fileDescriptor) {
        override fun markSupported(): Boolean = true
        override fun mark(readlimit: Int) = Unit
        override fun reset() {
            channel.position(0)
        }
        override fun close() = pfd.close()
    }

    class Factory(private val registry: AnimatedRegistry) : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val kind = AnimationSniffer.sniff(result.source.source().peek())
            val key = options.getExtra(SourceKey)
            if (key.isNotEmpty()) registry.record(key, kind != ImageKind.Static)
            if (kind == ImageKind.Static || !options.getExtra(AnimateKey)) return null
            if (kind == ImageKind.AnimatedGif && options.getExtra(GridKey) &&
                AnimationSniffer.gifCanvasPixels(result.source.source().peek()) > GRID_GIF_MAX_PIXELS
            ) return null
            return AnimatedDecoder(result.source, kind, options)
        }
    }
}
