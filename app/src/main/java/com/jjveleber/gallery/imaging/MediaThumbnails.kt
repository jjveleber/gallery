package com.jjveleber.gallery.imaging

import android.content.ContentResolver
import android.net.Uri
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.key.Keyer
import coil3.request.Options
import okio.buffer
import okio.source
import java.io.FileNotFoundException

/** A square thumbnail of a MediaStore image, [sizePx] on each side. [mime] says whether it may be animated. */
data class MediaThumbnail(val uri: String, val mime: String, val sizePx: Int)

private val ANIMATABLE_MIME = setOf("image/gif", "image/webp", "image/png", "image/apng")

/** Only GIF, WebP and PNG files can be animated, so only they are sniffed. */
fun mightBeAnimated(mime: String): Boolean = mime in ANIMATABLE_MIME

/** MediaStore item URIs can use the system thumbnail cache; document (system picker) URIs cannot. */
fun isMediaStoreUri(uri: String): Boolean = uri.startsWith("content://media/")

/**
 * Loads a [MediaThumbnail] from the system thumbnail cache (ContentResolver.loadThumbnail, which also applies EXIF
 * orientation). An animated file whose request allows animation is handed to the decoders instead, so it animates.
 */
class MediaThumbnailFetcher(
    private val data: MediaThumbnail,
    private val options: Options,
    private val resolver: ContentResolver,
    private val registry: AnimatedRegistry,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val uri = Uri.parse(data.uri)
        val animated = isAnimated(uri) // also records the kind for the grid's ANIM badge
        if (animated && options.getExtra(AnimateKey)) {
            val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException(data.uri)
            return SourceFetchResult(ImageSource(stream.source().buffer(), options.fileSystem), data.mime, DataSource.DISK)
        }
        val bitmap = resolver.loadThumbnail(uri, Size(data.sizePx, data.sizePx), null)
        // The size is part of the cache key (CacheKeyer), and the request is INEXACT, so a cache hit needs no size check.
        return ImageFetchResult(bitmap.asImage(), isSampled = false, dataSource = DataSource.DISK)
    }

    /** Sniffs the header once per file and records the result in [registry]. */
    private fun isAnimated(uri: Uri): Boolean {
        if (!mightBeAnimated(data.mime) || registry.isStatic(data.uri)) return false
        if (registry.isAnimated(data.uri)) return true
        val kind = resolver.openInputStream(uri)?.source()?.buffer()?.use { AnimationSniffer.sniff(it) } ?: ImageKind.Static
        registry.record(data.uri, kind != ImageKind.Static)
        return kind != ImageKind.Static
    }

    class Factory(private val resolver: ContentResolver, private val registry: AnimatedRegistry) : Fetcher.Factory<MediaThumbnail> {
        override fun create(data: MediaThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaThumbnailFetcher(data, options, resolver, registry)
    }

    /** Keeps thumbnails apart from full-size entries for the same URI, one entry per thumbnail size. */
    object CacheKeyer : Keyer<MediaThumbnail> {
        override fun key(data: MediaThumbnail, options: Options): String = "thumb:${data.uri}@${data.sizePx}"
    }
}
