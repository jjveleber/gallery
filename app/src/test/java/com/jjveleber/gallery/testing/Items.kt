package com.jjveleber.gallery.testing

import com.jjveleber.gallery.model.ImageItem

fun item(relPath: String, modifiedMs: Long = 0, sizeBytes: Long = 0) = ImageItem(
    uri = "content://test/$relPath",
    name = relPath.substringAfterLast('/'),
    relPath = relPath,
    sizeBytes = sizeBytes,
    modifiedMs = modifiedMs,
    mime = "image/jpeg",
)
