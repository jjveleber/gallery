package com.jjveleber.gallery.scan

import com.jjveleber.gallery.model.ImageItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class DocEntry(
    val id: String,
    val name: String,
    val mime: String?,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val isDir: Boolean,
)

/** Lists a folder's direct children. Implemented over DocumentsContract on the device and faked in tests. */
interface DocumentSource {
    fun children(folderId: String): List<DocEntry>
    fun uriOf(docId: String): String
}

/**
 * Collects supported images under [rootId], breadth first. Skips hidden entries (names starting with '.'),
 * such as the `.thumbnails` folders Android creates. Calls [onProgress] with the running count after each folder.
 */
suspend fun walkImages(
    source: DocumentSource,
    rootId: String,
    recursive: Boolean,
    onProgress: (Int) -> Unit = {},
): List<ImageItem> {
    val found = ArrayList<ImageItem>()
    val folders = ArrayDeque<Pair<String, String>>() // folder id, relative dir ("" for the root)
    folders.add(rootId to "")
    while (folders.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (folderId, dir) = folders.removeFirst()
        for (entry in source.children(folderId)) {
            if (entry.name.startsWith(".")) continue
            val relPath = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
            if (entry.isDir) {
                if (recursive) folders.add(entry.id to relPath)
            } else if (isSupportedImage(entry.name, entry.mime)) {
                found.add(ImageItem(source.uriOf(entry.id), entry.name, relPath, entry.sizeBytes, entry.modifiedMs, entry.mime.orEmpty()))
            }
        }
        onProgress(found.size)
    }
    return found
}
