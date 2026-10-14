package com.jjveleber.gallery.testing

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry

/** Puts test assets into MediaStore as images owned by the app under test, so it can read them without permission. */
object MediaFixtures {
    private val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    /** Copies `images/[asset]` from the test assets to [relativePath][displayName] and returns its MediaStore URI. */
    fun insert(asset: String, relativePath: String, displayName: String, mime: String): Uri {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection, values)) { "insert failed for $relativePath$displayName" }
        requireNotNull(resolver.openOutputStream(uri)).use { out -> assets.open("images/$asset").use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    fun delete(uris: List<Uri>) {
        uris.forEach { runCatching { resolver.delete(it, null, null) } }
    }
}
