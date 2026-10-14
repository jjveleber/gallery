package com.jjveleber.gallery.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedTypesTest {
    @Test fun acceptsListedMimeTypes() {
        for (mime in listOf("image/jpeg", "image/png", "image/apng", "image/webp", "image/gif", "image/bmp", "image/x-ms-bmp", "image/heic", "image/heif")) {
            assertTrue(mime, isSupportedImage("x", mime))
        }
    }

    @Test fun rejectsOtherTypes() {
        assertFalse(isSupportedImage("a.avif", "image/avif"))
        assertFalse(isSupportedImage("a.svg", "image/svg+xml"))
        assertFalse(isSupportedImage("a.mp4", "video/mp4"))
        assertFalse(isSupportedImage("notes.txt", "text/plain"))
    }

    @Test fun fallsBackToExtensionForGenericMime() {
        assertTrue(isSupportedImage("IMG_1.JPG", "application/octet-stream"))
        assertTrue(isSupportedImage("a.webp", null))
        assertTrue(isSupportedImage("a.png", ""))
        assertTrue(isSupportedImage("a.jpeg", "image/jpg"))
        assertFalse(isSupportedImage("a.txt", "application/octet-stream"))
        assertFalse(isSupportedImage("noextension", null))
    }
}
