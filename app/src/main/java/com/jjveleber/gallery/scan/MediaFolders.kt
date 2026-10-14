package com.jjveleber.gallery.scan

import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.model.ImageItem
import com.jjveleber.gallery.model.NaturalOrder

/** One MediaStore image row. [relativePath] is RELATIVE_PATH (ends with '/'). [modifiedMs] is in milliseconds. */
data class MediaRow(
    val id: Long,
    val volume: String,
    val relativePath: String,
    val name: String,
    val mime: String?,
    val sizeBytes: Long,
    val modifiedMs: Long,
)

/** MediaStore image rows. Implemented over ContentResolver on the device and faked in tests. */
interface MediaRowSource {
    /**
     * Image rows on [volume] (every volume when null) whose RELATIVE_PATH starts with [pathPrefix] ("" for all).
     * May return extra rows: callers filter again.
     */
    fun rows(volume: String?, pathPrefix: String): List<MediaRow>

    fun uriOf(row: MediaRow): String
}

/** A folder-list entry: [count] supported images in the folder and all its subfolders, [coverUri] the newest of them. */
data class MediaFolder(val ref: FolderRef, val count: Int, val coverUri: String, val coverMime: String)

/** A SQL LIKE pattern for strings starting with [prefix], to use with ESCAPE '\'. */
fun likePrefix(prefix: String): String =
    prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

/** True when the file, or any folder on its path, is hidden (name starts with '.'), as the folder walk skips them. */
fun isHiddenPath(relativePath: String, name: String): Boolean =
    name.startsWith(".") || relativePath.split('/').any { it.startsWith(".") }

private fun MediaRow.isShown() = !isHiddenPath(relativePath, name) && isSupportedImage(name, mime)

/** "a/b/" gives "a/", "a/b/"; the volume root ("") gives nothing. */
private fun MediaRow.ancestorPaths(): List<String> {
    val segments = relativePath.split('/').filter { it.isNotEmpty() }
    return segments.indices.map { n -> segments.take(n + 1).joinToString("/", postfix = "/") }
}

private val newestFirst = compareByDescending<MediaRow> { it.modifiedMs }.thenBy(NaturalOrder) { it.name }

/**
 * Every folder holding supported, non-hidden images directly or in any subfolder (so ancestors like DCIM/ get a row
 * too; the volume root gets none), sorted by path, then volume. Count and cover are recursive, matching what opens.
 */
fun listFolders(source: MediaRowSource): List<MediaFolder> =
    source.rows(volume = null, pathPrefix = "")
        .filter { it.isShown() }
        .flatMap { row -> row.ancestorPaths().map { FolderRef(row.volume, it) to row } }
        .groupBy({ it.first }, { it.second })
        .map { (ref, rows) ->
            val cover = rows.minWith(newestFirst)
            MediaFolder(ref, rows.size, source.uriOf(cover), cover.mime.orEmpty())
        }
        .sortedWith(compareBy<MediaFolder, String>(NaturalOrder) { it.ref.relativePath }.thenBy { it.ref.volume })

/**
 * The supported images in [folder] and its subfolders, with relPath relative to [folder]. The path match ignores
 * case, like the shared storage and SQL LIKE.
 */
fun imagesIn(source: MediaRowSource, folder: FolderRef): List<ImageItem> =
    source.rows(folder.volume, folder.relativePath)
        .filter { it.volume == folder.volume && it.relativePath.startsWith(folder.relativePath, ignoreCase = true) && it.isShown() }
        .map {
            val relPath = it.relativePath.substring(folder.relativePath.length) + it.name
            ImageItem(source.uriOf(it), it.name, relPath, it.sizeBytes, it.modifiedMs, it.mime.orEmpty())
        }
