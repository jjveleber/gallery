package com.jjveleber.gallery.scan

/**
 * Where an opened image lives, in MediaStore terms. [volume] is a MediaStore volume name ("external_primary"
 * or a lowercase SD card ID). [relativePath] ends with '/' like MediaStore.RELATIVE_PATH, or is empty at the volume root.
 */
data class FolderLocation(val volume: String, val relativePath: String, val fileName: String)

private const val PRIMARY = "external_primary"
internal const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
private val VOLUME_ID = Regex("primary|[0-9A-F]{4}-[0-9A-F]{4}")

fun locationFromPath(path: String): FolderLocation? = when {
    path.startsWith("/storage/emulated/0/") -> split(PRIMARY, path.removePrefix("/storage/emulated/0/"))
    path.startsWith("/sdcard/") -> split(PRIMARY, path.removePrefix("/sdcard/"))
    path.startsWith("/storage/") -> {
        val afterStorage = path.removePrefix("/storage/")
        val volume = afterStorage.substringBefore('/')
        if (volume == afterStorage) null else split(volume.lowercase(), afterStorage.substringAfter('/'))
    }
    else -> null
}

/**
 * Any document provider whose ID has the externalstorage shape `<volume>:<relative path>` (volume is "primary" or an
 * SD card ID like "1A2B-3C4D") maps to a location; the Fire's Files app uses such IDs. Downloads `raw:` IDs carry a path.
 */
fun locationFromDocumentId(authority: String, docId: String): FolderLocation? {
    if (authority == DOWNLOADS_AUTHORITY && docId.startsWith("raw:")) return locationFromPath(docId.removePrefix("raw:"))
    val volume = docId.substringBefore(':', missingDelimiterValue = "")
    if (!VOLUME_ID.matches(volume)) return null
    return split(if (volume == "primary") PRIMARY else volume.lowercase(), docId.substringAfter(':'))
}

/** The externalstorage document ID of this location's folder, for the folder picker's EXTRA_INITIAL_URI. */
fun FolderLocation.externalStorageDocId(): String =
    (if (volume == PRIMARY) "primary" else volume.uppercase()) + ":" + relativePath.trimEnd('/')

private fun split(volume: String, rest: String): FolderLocation? {
    val clean = rest.trim('/')
    if (clean.isEmpty()) return null
    val dir = clean.substringBeforeLast('/', missingDelimiterValue = "")
    return FolderLocation(volume, if (dir.isEmpty()) "" else "$dir/", clean.substringAfterLast('/'))
}
