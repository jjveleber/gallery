package com.jjveleber.gallery.scan

private val MIME_TYPES = setOf(
    "image/jpeg", "image/png", "image/apng", "image/webp", "image/gif",
    "image/bmp", "image/x-ms-bmp", "image/heic", "image/heif",
)
private val EXTENSIONS = setOf("jpg", "jpeg", "png", "apng", "webp", "gif", "bmp", "heic", "heif")

/** True for the formats the app displays. Uses the extension when a provider reports a generic or odd MIME type. */
fun isSupportedImage(name: String, mime: String?): Boolean {
    if (mime in MIME_TYPES) return true
    val genericMime = mime.isNullOrEmpty() || mime == "application/octet-stream" || mime.startsWith("image/")
    return genericMime && name.substringAfterLast('.', "").lowercase() in EXTENSIONS
}
