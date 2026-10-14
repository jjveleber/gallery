package com.jjveleber.gallery.imaging

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class AnimationSnifferTest {
    private fun bytes(name: String): ByteArray =
        javaClass.getResourceAsStream("/images/$name")!!.use { it.readBytes() }

    private fun sniff(data: ByteArray) = AnimationSniffer.sniff(Buffer().write(data))

    @Test fun animatedGif() = assertEquals(ImageKind.AnimatedGif, sniff(bytes("anim.gif")))
    @Test fun staticGif() = assertEquals(ImageKind.Static, sniff(bytes("still.gif")))
    @Test fun animatedWebp() = assertEquals(ImageKind.AnimatedWebp, sniff(bytes("anim.webp")))
    @Test fun staticWebp() = assertEquals(ImageKind.Static, sniff(bytes("still.webp")))
    @Test fun apng() = assertEquals(ImageKind.Apng, sniff(bytes("anim.png")))
    @Test fun plainPng() = assertEquals(ImageKind.Static, sniff(bytes("still.png")))
    @Test fun jpeg() = assertEquals(ImageKind.Static, sniff(bytes("still.jpg")))
    @Test fun bmp() = assertEquals(ImageKind.Static, sniff(bytes("still.bmp")))
    @Test fun empty() = assertEquals(ImageKind.Static, sniff(ByteArray(0)))
    @Test fun garbage() = assertEquals(ImageKind.Static, sniff(ByteArray(64) { it.toByte() }))

    @Test fun truncatedGifIsStatic() =
        assertEquals(ImageKind.Static, sniff(bytes("anim.gif").copyOf(20)))

    @Test fun truncatedPngIsStatic() =
        assertEquals(ImageKind.Static, sniff(bytes("anim.png").copyOf(20)))

    @Test fun truncatedWebpIsStatic() =
        assertEquals(ImageKind.Static, sniff(bytes("anim.webp").copyOf(16)))

    @Test fun peekLeavesSourceUnconsumed() {
        val data = bytes("anim.gif")
        val source = Buffer().write(data)
        AnimationSniffer.sniff(source.peek())
        assertEquals(data.size.toLong(), source.size)
    }

    @Test fun gifCanvasPixelsReadsTheLogicalScreen() =
        assertEquals(64L * 48, AnimationSniffer.gifCanvasPixels(Buffer().write(bytes("anim.gif"))))

    @Test fun gifCanvasPixelsIsZeroForOtherFormatsAndTruncatedHeaders() {
        assertEquals(0L, AnimationSniffer.gifCanvasPixels(Buffer().write(bytes("anim.png"))))
        assertEquals(0L, AnimationSniffer.gifCanvasPixels(Buffer().write(bytes("anim.gif").copyOf(8))))
    }
}
