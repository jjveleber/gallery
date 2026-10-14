package com.jjveleber.gallery.imaging

import okio.BufferedSource
import java.io.IOException

/**
 * Browsers and most image viewers play a frame whose delay is 10 ms or less for 100 ms instead, and many GIFs and
 * animated WebPs were made with 0 or 10 ms delays that only look right that way. Android's GIF decoder and
 * APNG4Android's WebP decoder play such delays as written, so the animation runs far too fast. These functions find
 * those delays in a file and raise them in a copy of its bytes. APNG4Android already does this for APNG.
 */
object FrameDelays {
    /** A delay at or below this many milliseconds is played for [FIXED_MS] instead, as browsers do. */
    const val MAX_SHORT_MS = 10
    const val FIXED_MS = 100

    /**
     * Byte offsets of the GIF frame delays (2 bytes, little-endian, in hundredths of a second) of [MAX_SHORT_MS] or
     * less. Consumes [source]. A truncated file gives the offsets found before the end; a non-GIF gives none.
     */
    fun shortGifDelays(source: BufferedSource): List<Long> {
        val s = Counting(source)
        val found = mutableListOf<Long>()
        try {
            if (s.readUtf8(3) != "GIF") return found
            s.skip(3 + 4) // version, logical screen width and height
            val packed = s.readByte()
            s.skip(2) // background color index, pixel aspect ratio
            if (packed and 0x80 != 0) s.skip(3L * (1 shl ((packed and 0x07) + 1)))
            while (true) {
                when (s.readByte()) {
                    0x21 -> { // extension: label byte, then sub-blocks
                        val label = s.readByte()
                        val size = s.readByte()
                        if (label == 0xF9 && size >= 4) { // graphic control extension: packed, delay, transparent index
                            s.skip(1)
                            val offset = s.position
                            val delayCs = s.readShortLe()
                            if (delayCs * 10 <= MAX_SHORT_MS) found += offset
                            s.skip(size - 3L)
                        } else {
                            s.skip(size.toLong())
                        }
                        s.skipSubBlocks()
                    }
                    0x2C -> { // image descriptor
                        s.skip(8)
                        val p = s.readByte()
                        if (p and 0x80 != 0) s.skip(3L * (1 shl ((p and 0x07) + 1)))
                        s.skip(1) // LZW minimum code size
                        s.skipSubBlocks()
                    }
                    else -> return found // 0x3B trailer, or garbage
                }
            }
        } catch (e: IOException) {
            return found
        }
    }

    /**
     * Byte offsets of the animated WebP frame durations (3 bytes, little-endian, in milliseconds, inside ANMF chunks)
     * of [MAX_SHORT_MS] or less. Consumes [source]. A truncated file gives the offsets found before the end.
     */
    fun shortWebpDurations(source: BufferedSource): List<Long> {
        val s = Counting(source)
        val found = mutableListOf<Long>()
        try {
            if (s.readUtf8(4) != "RIFF") return found
            s.skip(4)
            if (s.readUtf8(4) != "WEBP") return found
            while (true) {
                val fourcc = s.readUtf8(4)
                val size = s.readIntLe().toLong() and 0xFFFFFFFFL
                val padded = size + (size and 1)
                if (fourcc == "ANMF" && size >= 16) {
                    s.skip(12) // frame x, y, width - 1, height - 1
                    val offset = s.position
                    if (s.readUInt24Le() <= MAX_SHORT_MS) found += offset
                    s.skip(padded - 15)
                } else {
                    s.skip(padded)
                }
            }
        } catch (e: IOException) {
            return found
        }
    }

    /** Sets the GIF delays at [offsets] (from [shortGifDelays]) to [FIXED_MS]. */
    fun fixGif(bytes: ByteArray, offsets: List<Long>) {
        val cs = FIXED_MS / 10
        for (o in offsets) {
            bytes[o.toInt()] = cs.toByte()
            bytes[o.toInt() + 1] = (cs shr 8).toByte()
        }
    }

    /** Sets the WebP frame durations at [offsets] (from [shortWebpDurations]) to [FIXED_MS]. */
    fun fixWebp(bytes: ByteArray, offsets: List<Long>) {
        for (o in offsets) {
            bytes[o.toInt()] = FIXED_MS.toByte()
            bytes[o.toInt() + 1] = (FIXED_MS shr 8).toByte()
            bytes[o.toInt() + 2] = (FIXED_MS shr 16).toByte()
        }
    }

    /** Reads [source] while counting bytes, so a field's offset in the file is known. Bytes come back unsigned. */
    private class Counting(private val source: BufferedSource) {
        var position = 0L
            private set

        fun readByte(): Int = (source.readByte().toInt() and 0xFF).also { position += 1 }

        fun readShortLe(): Int = (source.readShortLe().toInt() and 0xFFFF).also { position += 2 }

        fun readIntLe(): Int = source.readIntLe().also { position += 4 }

        fun readUInt24Le(): Int = readByte() or (readByte() shl 8) or (readByte() shl 16)

        fun readUtf8(n: Long): String = source.readUtf8(n).also { position += n }

        fun skip(n: Long) {
            source.skip(n)
            position += n
        }

        fun skipSubBlocks() {
            while (true) {
                val n = readByte()
                if (n == 0) return
                skip(n.toLong())
            }
        }
    }
}
