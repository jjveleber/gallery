package com.jjveleber.gallery.imaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaThumbnailsTest {
    @Test fun onlyGifWebpAndPngMightBeAnimated() {
        for (mime in listOf("image/gif", "image/webp", "image/png", "image/apng")) assertTrue(mime, mightBeAnimated(mime))
        for (mime in listOf("image/jpeg", "image/heic", "image/bmp", "")) assertFalse(mime, mightBeAnimated(mime))
    }

    @Test fun recognizesMediaStoreUris() {
        assertTrue(isMediaStoreUri("content://media/external_primary/images/media/12"))
        assertFalse(isMediaStoreUri("content://com.android.externalstorage.documents/tree/primary%3APictures/document/primary%3APictures%2Fa.jpg"))
        assertFalse(isMediaStoreUri("file:///sdcard/a.jpg"))
    }

    @Test fun cannotAnimateWhenTypeCannotOrKnownStatic() {
        assertTrue(cannotAnimate("image/jpeg", knownStatic = false))
        assertTrue(cannotAnimate("image/png", knownStatic = true))
        assertFalse(cannotAnimate("image/png", knownStatic = false))
        assertFalse(cannotAnimate("image/gif", knownStatic = false))
    }
}
