package com.jjveleber.gallery.scan

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import com.jjveleber.gallery.model.FolderRef
import com.jjveleber.gallery.model.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** The device's image index, so the ViewModel can be tested with a fake. Needs photo access. */
interface MediaLibrary {
    suspend fun folders(): List<MediaFolder>
    suspend fun images(folder: FolderRef): List<ImageItem>

    /** Every supported image on every volume, for the duplicate search. */
    suspend fun allImages(): List<ImageItem>
}

class AndroidMediaLibrary(resolver: ContentResolver) : MediaLibrary {
    private val source = AndroidMediaRowSource(resolver)

    override suspend fun folders(): List<MediaFolder> = withContext(Dispatchers.IO) { listFolders(source) }

    override suspend fun images(folder: FolderRef): List<ImageItem> = withContext(Dispatchers.IO) { imagesIn(source, folder) }

    override suspend fun allImages(): List<ImageItem> = withContext(Dispatchers.IO) { allImages(source) }
}

/** One MediaStore query per call, instead of one document-provider query per subfolder. */
class AndroidMediaRowSource(private val resolver: ContentResolver) : MediaRowSource {
    override fun rows(volume: String?, pathPrefix: String): List<MediaRow> {
        val collection = MediaStore.Images.Media.getContentUri(volume ?: MediaStore.VOLUME_EXTERNAL)
        val selection = if (pathPrefix.isEmpty()) null else "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
        val args = if (pathPrefix.isEmpty()) null else arrayOf(likePrefix(pathPrefix))
        val cursor = resolver.query(collection, COLUMNS, selection, args, null)
            ?: throw FileNotFoundException("Cannot query $collection")
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(3) ?: continue
                    add(
                        MediaRow(
                            id = c.getLong(0),
                            volume = c.getString(1).orEmpty(),
                            relativePath = c.getString(2).orEmpty(),
                            name = name,
                            mime = c.getString(4),
                            sizeBytes = if (c.isNull(5)) 0 else c.getLong(5),
                            modifiedMs = if (c.isNull(6)) 0 else c.getLong(6) * 1000,
                        ),
                    )
                }
            }
        }
    }

    override fun uriOf(row: MediaRow): String =
        ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(row.volume), row.id).toString()

    private companion object {
        val COLUMNS = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
    }
}
