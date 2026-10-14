package com.jjveleber.gallery.scan

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.jjveleber.gallery.model.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface OpenWithResult {
    /** The incoming image's folder (not recursive). [startUri] is the incoming image's entry in [items]. */
    data class Resolved(val items: List<ImageItem>, val startUri: String, val folderName: String) : OpenWithResult

    /** Only the incoming image. [folderHint] points the folder picker at its folder when known. */
    data class Single(val item: ImageItem, val folderHint: FolderLocation?) : OpenWithResult
}

interface OpenWithResolver {
    /** [mayReadMedia] is true when the storage permission is granted. Never throws. */
    suspend fun resolve(uri: String, mayReadMedia: Boolean): OpenWithResult
}

class AndroidOpenWithResolver(private val context: Context) : OpenWithResolver {
    private val resolver = context.contentResolver

    override suspend fun resolve(uri: String, mayReadMedia: Boolean): OpenWithResult = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        val single = singleItem(parsed)
        val location = locate(parsed)
        if (!mayReadMedia || location == null) return@withContext OpenWithResult.Single(single, location)
        val siblings = runCatching { querySiblings(location) }.getOrDefault(emptyList())
        val start = siblings.firstOrNull { it.name == location.fileName }
            ?: return@withContext OpenWithResult.Single(single, location)
        val folderName = location.relativePath.trimEnd('/').substringAfterLast('/').ifEmpty { "Storage" }
        OpenWithResult.Resolved(siblings, start.uri, folderName)
    }

    private fun singleItem(uri: Uri): ImageItem {
        var name: String? = null
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "image"
        return ImageItem(uri.toString(), fileName, fileName, size, 0, runCatching { resolver.getType(uri) }.getOrNull().orEmpty())
    }

    private fun locate(uri: Uri): FolderLocation? = runCatching {
        when {
            uri.scheme == "file" -> uri.path?.let(::locationFromPath)
            uri.authority == MediaStore.AUTHORITY -> mediaStoreLocation(uri)
            DocumentsContract.isDocumentUri(context, uri) ->
                locationFromDocumentId(uri.authority.orEmpty(), DocumentsContract.getDocumentId(uri))
            else -> null
        }
    }.getOrNull()
        ?: runCatching { dataColumnLocation(uri) }.getOrNull()
        ?: pathLocation(uri)

    /** Last resort: some file managers (ES File Explorer) put the absolute path in the content URI path. */
    private fun pathLocation(uri: Uri): FolderLocation? =
        if (uri.scheme == "content") uri.path?.let(::locationFromPath) else null

    private fun mediaStoreLocation(uri: Uri): FolderLocation? {
        val columns = arrayOf(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
        return resolver.query(uri, columns, null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.isNull(1) || c.isNull(2)) null
            else FolderLocation(c.getString(0), c.getString(1), c.getString(2))
        }
    }

    /** Some file managers expose the file path in the deprecated _data column. */
    @Suppress("DEPRECATION")
    private fun dataColumnLocation(uri: Uri): FolderLocation? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) locationFromPath(c.getString(0)) else null
        }

    private fun querySiblings(location: FolderLocation): List<ImageItem> {
        val collection = MediaStore.Images.Media.getContentUri(location.volume)
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        return resolver.query(collection, columns, selection, arrayOf(location.relativePath), null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (name.startsWith(".") || !isSupportedImage(name, mime)) continue
                    val itemUri = ContentUris.withAppendedId(collection, c.getLong(0)).toString()
                    add(ImageItem(itemUri, name, name, c.getLong(3), c.getLong(4) * 1000, mime.orEmpty()))
                }
            }
        } ?: emptyList()
    }
}
