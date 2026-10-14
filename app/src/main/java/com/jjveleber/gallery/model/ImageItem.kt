package com.jjveleber.gallery.model

/** One image file. [uri] is a content URI string. [relPath] is relative to the browsed folder, separated by '/'. */
data class ImageItem(
    val uri: String,
    val name: String,
    val relPath: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val mime: String,
)
