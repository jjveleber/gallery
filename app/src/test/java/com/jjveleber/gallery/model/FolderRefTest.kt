package com.jjveleber.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderRefTest {
    @Test fun nameIsLastSegment() = assertEquals("Trip", FolderRef(PRIMARY_VOLUME, "Pictures/Trip/").name)

    @Test fun volumeRootIsCalledStorage() {
        assertEquals("Storage", FolderRef(PRIMARY_VOLUME, "").name)
        assertEquals("Storage", FolderRef(PRIMARY_VOLUME, "/").name)
    }

    @Test fun displayPathMarksSdCard() {
        assertEquals("Pictures/Trip", FolderRef(PRIMARY_VOLUME, "Pictures/Trip/").displayPath)
        assertEquals("SD card: DCIM", FolderRef("1a2b-3c4d", "DCIM/").displayPath)
        assertEquals("/", FolderRef(PRIMARY_VOLUME, "/").displayPath)
    }
}
