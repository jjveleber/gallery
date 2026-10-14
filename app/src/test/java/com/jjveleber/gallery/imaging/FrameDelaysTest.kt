package com.jjveleber.gallery.imaging

import okio.Buffer
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDelaysTest {
    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/images/$name")!!.readBytes()

    private fun gifDelays(bytes: ByteArray) = FrameDelays.shortGifDelays(Buffer().write(bytes))
    private fun webpDurations(bytes: ByteArray) = FrameDelays.shortWebpDurations(Buffer().write(bytes))

    /** A 1x1 GIF with a 2-color global table and one frame per entry of [delaysCs], each with a comment extension before it. */
    private fun gif(vararg delaysCs: Int): ByteArray {
        val b = Buffer().writeUtf8("GIF89a").writeShortLe(1).writeShortLe(1)
            .writeByte(0x80).writeByte(0).writeByte(0) // global color table of 2 entries
            .write(ByteArray(6))
        b.writeByte(0x21).writeByte(0xFE).writeByte(3).writeUtf8("hi!").writeByte(0) // comment
        for (d in delaysCs) {
            b.writeByte(0x21).writeByte(0xF9).writeByte(4).writeByte(0).writeShortLe(d).writeByte(0).writeByte(0)
            b.writeByte(0x2C).writeShortLe(0).writeShortLe(0).writeShortLe(1).writeShortLe(1).writeByte(0)
            b.writeByte(2).writeByte(2).writeByte(0x4C).writeByte(0x01).writeByte(0) // LZW data
        }
        return b.writeByte(0x3B).readByteArray()
    }

    /** An animated WebP skeleton: a VP8X chunk, then one ANMF chunk per entry of [durationsMs] with a 3-byte frame body. */
    private fun webp(vararg durationsMs: Int): ByteArray {
        val chunks = Buffer()
        chunks.writeUtf8("VP8X").writeIntLe(10).write(byteArrayOf(0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        for (d in durationsMs) {
            chunks.writeUtf8("ANMF").writeIntLe(19).write(ByteArray(12))
                .writeByte(d and 0xFF).writeByte((d shr 8) and 0xFF).writeByte(d shr 16)
                .writeByte(0).write(ByteArray(3)).writeByte(0) // flags, 3-byte body, pad to even
        }
        val body = chunks.readByteArray()
        return Buffer().writeUtf8("RIFF").writeIntLe(4 + body.size).writeUtf8("WEBP").write(body).readByteArray()
    }

    @Test fun gifDelaysOfTenMsOrLessAreFoundAndFixed() {
        val bytes = gif(0, 1, 2, 10)
        val offsets = gifDelays(bytes)
        assertEquals(2, offsets.size)
        FrameDelays.fixGif(ByteBuffer.wrap(bytes), offsets)
        assertTrue(gifDelays(bytes).isEmpty())
        assertArrayEquals(gif(10, 10, 2, 10), bytes)
    }

    @Test fun gifWithNormalDelaysNeedsNoFix() {
        assertTrue(gifDelays(gif(5, 10, 200)).isEmpty())
        assertTrue(gifDelays(resource("anim.gif")).isEmpty())
    }

    @Test fun walksARealGifToEveryFrame() {
        // anim.gif has four frames of 200 ms (20 cs, 0x14): zero each delay and every frame must be found.
        val bytes = resource("anim.gif")
        var zeroed = 0
        for (i in 0 until bytes.size - 5) {
            if (bytes[i] == 0x21.toByte() && bytes[i + 1] == 0xF9.toByte() && bytes[i + 2] == 4.toByte()) {
                bytes[i + 4] = 0
                bytes[i + 5] = 0
                zeroed++
            }
        }
        assertEquals(4, zeroed)
        assertEquals(4, gifDelays(bytes).size)
    }

    @Test fun truncatedGifGivesWhatWasFoundBeforeTheEnd() {
        val bytes = gif(0, 0)
        assertEquals(1, gifDelays(bytes.copyOf(bytes.size - 20)).size)
    }

    @Test fun webpDurationsOfTenMsOrLessAreFoundAndFixed() {
        val bytes = webp(0, 10, 11, 100)
        val offsets = webpDurations(bytes)
        assertEquals(2, offsets.size)
        FrameDelays.fixWebp(ByteBuffer.wrap(bytes), offsets)
        assertArrayEquals(webp(100, 100, 11, 100), bytes)
    }

    @Test fun webpWithNormalDurationsNeedsNoFix() {
        assertTrue(webpDurations(webp(20, 70_000)).isEmpty())
        assertTrue(webpDurations(resource("anim.webp")).isEmpty())
    }

    @Test fun otherFormatsHaveNoDelays() {
        val png = resource("still.png")
        assertTrue(gifDelays(png).isEmpty())
        assertTrue(webpDurations(png).isEmpty())
    }

    @Test fun zeroSizeExtensionDoesNotDesyncTheWalk() {
        // An application extension whose first size byte is the terminator, before a frame with a 0 delay.
        val good = gif(0)
        val bytes = Buffer().write(good, 0, 19).writeByte(0x21).writeByte(0xFF).writeByte(0)
            .write(good, 19, good.size - 19).readByteArray()
        assertEquals(1, gifDelays(bytes).size)
    }

    @Test fun fixSkipsOffsetsPastTheEnd() {
        val bytes = gif(0)
        val offsets = gifDelays(bytes)
        val shrunk = ByteBuffer.wrap(bytes, 0, offsets[0].toInt() + 1).slice()
        FrameDelays.fixGif(shrunk, offsets)
        assertArrayEquals(gif(0), bytes)
    }
}
