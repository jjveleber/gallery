package com.jjveleber.gallery.storage

import android.content.ContentResolver
import android.content.IntentSender
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.jjveleber.gallery.imaging.isMediaStoreUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How an image can be deleted, by where its URI comes from. */
enum class DeleteKind {
    /** A MediaStore image: moved to the system trash after the system's consent dialog. */
    Trash,

    /** A file in a folder picked in the system picker: deleted for good, which the app's own dialog confirms. */
    Permanent,

    /** Anything else, such as a file another app shared through "Open with": the app has no right to delete it. */
    Unsupported,
}

fun deleteKind(uri: String): DeleteKind = when {
    isMediaStoreUri(uri) -> DeleteKind.Trash
    uri.startsWith("content://") && "/tree/" in uri -> DeleteKind.Permanent
    else -> DeleteKind.Unsupported
}

/** Deletes document files for good. Implemented with DocumentsContract on the device and faked in tests. */
interface DocumentDeleter {
    /** Returns the URIs that were deleted. Never throws for a file that cannot be deleted. */
    suspend fun delete(uris: List<String>): List<String>
}

class AndroidDocumentDeleter(private val resolver: ContentResolver) : DocumentDeleter {
    override suspend fun delete(uris: List<String>): List<String> = withContext(Dispatchers.IO) {
        uris.filter { runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(it)) }.getOrDefault(false) }
    }
}

/**
 * The system dialog asking to move MediaStore [uris] to the trash. Apps without broad storage access cannot change
 * other apps' media without it. Trashed files leave every gallery and are erased after about 30 days.
 */
fun trashRequest(resolver: ContentResolver, uris: List<String>): IntentSender =
    MediaStore.createTrashRequest(resolver, uris.map(Uri::parse), true).intentSender
