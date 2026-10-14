package com.jjveleber.gallery.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenWithLocationTest {
    private val ext = "com.android.externalstorage.documents"

    @Test fun primaryDocumentId() = assertEquals(
        FolderLocation("external_primary", "Pictures/Trip/", "a.jpg"),
        locationFromDocumentId(ext, "primary:Pictures/Trip/a.jpg"),
    )

    @Test fun sdCardDocumentIdUsesLowercaseVolume() = assertEquals(
        FolderLocation("1a2b-3c4d", "DCIM/", "b.png"),
        locationFromDocumentId(ext, "1A2B-3C4D:DCIM/b.png"),
    )

    @Test fun downloadsRawDocumentId() = assertEquals(
        FolderLocation("external_primary", "Download/", "c.gif"),
        locationFromDocumentId("com.android.providers.downloads.documents", "raw:/storage/emulated/0/Download/c.gif"),
    )

    @Test fun downloadsNumericIdIsUnknown() =
        assertNull(locationFromDocumentId("com.android.providers.downloads.documents", "msf:1234"))

    @Test fun unfamiliarAuthorityWithPrimaryIdIsResolved() = assertEquals(
        FolderLocation("external_primary", "Pictures/Trip/", "a.jpg"),
        locationFromDocumentId("com.example.files", "primary:Pictures/Trip/a.jpg"),
    )

    @Test fun unfamiliarAuthorityWithSdCardIdIsResolved() = assertEquals(
        FolderLocation("1a2b-3c4d", "DCIM/", "b.png"),
        locationFromDocumentId("com.example.files", "1A2B-3C4D:DCIM/b.png"),
    )

    @Test fun unfamiliarAuthorityWithOpaqueIdIsUnknown() =
        assertNull(locationFromDocumentId("com.example.files", "msf:42"))

    @Test fun unfamiliarAuthorityWithNumericIdIsUnknown() =
        assertNull(locationFromDocumentId("com.example.files", "42"))

    @Test fun documentIdWithoutVolumeIsUnknown() =
        assertNull(locationFromDocumentId(ext, "Pictures/a.jpg"))

    @Test fun primaryPath() = assertEquals(
        FolderLocation("external_primary", "Pictures/", "a.jpg"),
        locationFromPath("/storage/emulated/0/Pictures/a.jpg"),
    )

    @Test fun sdcardAliasPath() = assertEquals(
        FolderLocation("external_primary", "DCIM/Camera/", "a.jpg"),
        locationFromPath("/sdcard/DCIM/Camera/a.jpg"),
    )

    @Test fun selfPrimaryPath() = assertEquals(
        FolderLocation("external_primary", "Pictures/", "a.jpg"),
        locationFromPath("/storage/self/primary/Pictures/a.jpg"),
    )

    @Test fun esFileExplorerUriPath() = assertEquals(
        FolderLocation("external_primary", "folder/", "xyz.gif"),
        locationFromPath("/storage/emulated/0/folder/xyz.gif"),
    )

    @Test fun sdCardPath() = assertEquals(
        FolderLocation("1a2b-3c4d", "DCIM/", "a.jpg"),
        locationFromPath("/storage/1A2B-3C4D/DCIM/a.jpg"),
    )

    @Test fun fileAtVolumeRoot() = assertEquals(
        FolderLocation("external_primary", "", "a.jpg"),
        locationFromPath("/storage/emulated/0/a.jpg"),
    )

    @Test fun pathOutsideStorageIsUnknown() = assertNull(locationFromPath("/data/data/x/a.jpg"))

    @Test fun pathWithoutFileIsUnknown() = assertNull(locationFromPath("/storage/emulated/0/"))

    @Test fun externalStorageDocIdForPicker() {
        assertEquals("primary:Pictures/Trip", FolderLocation("external_primary", "Pictures/Trip/", "a.jpg").externalStorageDocId())
        assertEquals("1A2B-3C4D:DCIM", FolderLocation("1a2b-3c4d", "DCIM/", "a.jpg").externalStorageDocId())
    }
}
