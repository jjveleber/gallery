package com.jjveleber.gallery.imaging

import android.graphics.BitmapFactory
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
import com.github.penfeizhou.animation.gif.GifDrawable
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import com.github.penfeizhou.animation.webp.WebPDrawable
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Decodes animated GIF, WebP and APNG with APNG4Android, which handles WebP blend/dispose frames and APNG
 * that the platform decoder gets wrong or cannot animate. Still images are left to Coil's default decoders.
 */
class AnimatedDecoder(
    private val source: ImageSource,
    private val kind: ImageKind,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val loader = object : ByteBufferLoader() {
            override fun getByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
        }
        val drawable = when (kind) {
            ImageKind.AnimatedGif -> GifDrawable(loader)
            ImageKind.AnimatedWebp -> WebPDrawable(loader)
            ImageKind.Apng -> APNGDrawable(loader)
            ImageKind.Static -> error("Static images are not routed to AnimatedDecoder")
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
            DecodeResult(decodeStill(bytes), isSampled = true)
        }
    }

    /**
     * Makes APNG4Android decode frames at the request size instead of full resolution. The drawable's bounds drive
     * the decoder's sample size, so size them to fit the request while keeping the aspect ratio.
     */
    private fun applyRequestSize(drawable: FrameAnimationDrawable<*>, width: Int, height: Int) {
        val maxWidth = options.size.width.pxOrElse { width }
        val maxHeight = options.size.height.pxOrElse { height }
        val scale = minOf(1f, maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        drawable.setBounds(0, 0, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
    }

    /** Fallback when APNG4Android fails: the platform's first frame, downsampled to the requested size. */
    private fun decodeStill(bytes: ByteArray): Image {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxWidth = options.size.width.pxOrElse { bounds.outWidth }
        val maxHeight = options.size.height.pxOrElse { bounds.outHeight }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth && bounds.outHeight / (sample * 2) >= maxHeight) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Cannot decode image")
        return bitmap.asImage()
    }

    class Factory(private val registry: AnimatedRegistry) : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val kind = AnimationSniffer.sniff(result.source.source().peek())
            val key = options.getExtra(SourceKey)
            if (key.isNotEmpty()) registry.record(key, kind != ImageKind.Static)
            if (kind == ImageKind.Static || !options.getExtra(AnimateKey)) return null
            return AnimatedDecoder(result.source, kind, options)
        }
    }
}
