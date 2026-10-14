package com.jjveleber.gallery.imaging

import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException

enum class ImageKind { AnimatedGif, AnimatedWebp, Apng, Static }

/** Classifies an image by its header bytes, never by file extension. Consumes [sniff]'s source: pass a peek(). */
object AnimationSniffer {
    private val GIF87 = "GIF87a".encodeUtf8()
    private val GIF89 = "GIF89a".encodeUtf8()
    private val RIFF = "RIFF".encodeUtf8()
    private val WEBP_VP8X = "WEBPVP8X".encodeUtf8()
    private val PNG = "89504e470d0a1a0a".decodeHex()
    private const val WEBP_ANIMATION_FLAG = 0x02

    fun sniff(source: BufferedSource): ImageKind = try {
        when {
            source.rangeEquals(0, GIF87) || source.rangeEquals(0, GIF89) ->
                if (gifHasTwoFrames(source)) ImageKind.AnimatedGif else ImageKind.Static
            source.rangeEquals(0, RIFF) && source.rangeEquals(8, WEBP_VP8X) -> {
                source.skip(20)
                if (source.readByte().toInt() and WEBP_ANIMATION_FLAG != 0) ImageKind.AnimatedWebp else ImageKind.Static
            }
            source.rangeEquals(0, PNG) -> if (pngHasActl(source)) ImageKind.Apng else ImageKind.Static
            else -> ImageKind.Static
        }
    } catch (e: IOException) {
        ImageKind.Static // Truncated or malformed: let the static decoder try.
    }

    /** Width x height of a GIF's logical screen, read from its header; 0 when [source] is not a readable GIF. Pass a peek(). */
    fun gifCanvasPixels(source: BufferedSource): Long = try {
        if (source.rangeEquals(0, GIF87) || source.rangeEquals(0, GIF89)) {
            source.skip(6)
            source.readShortLe().toLong().and(0xFFFF) * source.readShortLe().toLong().and(0xFFFF)
        } else 0L
    } catch (e: IOException) {
        0L
    }

    /** Walks GIF blocks until a second image descriptor (animated) or the trailer (static). */
    private fun gifHasTwoFrames(s: BufferedSource): Boolean {
        s.skip(6 + 4) // signature, logical screen width and height
        val packed = s.readByte().toInt() and 0xFF
        s.skip(2) // background color index, pixel aspect ratio
        if (packed and 0x80 != 0) s.skip(3L * (1 shl ((packed and 0x07) + 1)))
        var frames = 0
        while (true) {
            when (s.readByte().toInt() and 0xFF) {
                0x21 -> { // extension: label byte, then sub-blocks
                    s.skip(1)
                    skipSubBlocks(s)
                }
                0x2C -> { // image descriptor
                    if (++frames == 2) return true
                    s.skip(8)
                    val p = s.readByte().toInt() and 0xFF
                    if (p and 0x80 != 0) s.skip(3L * (1 shl ((p and 0x07) + 1)))
                    s.skip(1) // LZW minimum code size
                    skipSubBlocks(s)
                }
                else -> return false // 0x3B trailer, or garbage
            }
        }
    }

    private fun skipSubBlocks(s: BufferedSource) {
        while (true) {
            val n = s.readByte().toInt() and 0xFF
            if (n == 0) return
            s.skip(n.toLong())
        }
    }

    /** APNG has an acTL chunk before the first IDAT. */
    private fun pngHasActl(s: BufferedSource): Boolean {
        s.skip(8)
        while (true) {
            val length = s.readInt().toLong() and 0xFFFFFFFFL
            when (s.readUtf8(4)) {
                "acTL" -> return true
                "IDAT", "IEND" -> return false
            }
            s.skip(length + 4) // data and CRC
        }
    }
}
