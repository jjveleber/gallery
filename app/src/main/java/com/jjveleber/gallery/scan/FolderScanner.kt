package com.jjveleber.gallery.scan

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.jjveleber.gallery.model.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** What the ViewModel needs from storage, so it can be tested with a fake. */
interface FolderScanner {
    suspend fun folderName(treeUri: String): String
    suspend fun scan(treeUri: String, recursive: Boolean, onProgress: (Int) -> Unit): List<ImageItem>
}

class AndroidFolderScanner(private val resolver: ContentResolver) : FolderScanner {
    override suspend fun folderName(treeUri: String): String = withContext(Dispatchers.IO) {
        AndroidDocumentSource(resolver, Uri.parse(treeUri)).rootName()
    }

    override suspend fun scan(treeUri: String, recursive: Boolean, onProgress: (Int) -> Unit): List<ImageItem> =
        withContext(Dispatchers.IO) {
            val source = AndroidDocumentSource(resolver, Uri.parse(treeUri))
            walkImages(source, source.rootId(), recursive, onProgress)
        }
}

/** Lists a picked document tree through DocumentsContract queries (much faster than DocumentFile). */
class AndroidDocumentSource(private val resolver: ContentResolver, private val tree: Uri) : DocumentSource {
    fun rootId(): String = DocumentsContract.getTreeDocumentId(tree)

    fun rootName(): String {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId())
        val name = resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        return name ?: rootId().substringAfterLast(':').substringAfterLast('/')
    }

    override fun children(folderId: String): List<DocEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val cursor = resolver.query(uri, COLUMNS, null, null, null) ?: throw FileNotFoundException("Cannot list $uri")
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val mime = c.getString(2)
                    add(
                        DocEntry(
                            id = c.getString(0),
                            name = c.getString(1).orEmpty(),
                            mime = mime,
                            sizeBytes = if (c.isNull(3)) 0 else c.getLong(3),
                            modifiedMs = if (c.isNull(4)) 0 else c.getLong(4),
                            isDir = mime == Document.MIME_TYPE_DIR,
                        ),
                    )
                }
            }
        }
    }

    override fun uriOf(docId: String): String = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString()

    private companion object {
        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
