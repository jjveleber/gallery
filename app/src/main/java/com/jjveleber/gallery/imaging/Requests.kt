package com.jjveleber.gallery.imaging

import android.content.Context
import android.net.Uri
import coil3.Extras
import coil3.request.ImageRequest
import coil3.size.Precision
import com.jjveleber.gallery.model.ImageItem

/** Whether this request may produce an animated image. */
val AnimateKey = Extras.Key(default = false)

/** The request's URI string, so the decoder can record sniff results in [AnimatedRegistry]. */
val SourceKey = Extras.Key(default = "")

fun imageRequest(
    context: Context,
    uri: String,
    animate: Boolean,
    listener: ImageRequest.Listener? = null,
    data: Any = Uri.parse(uri),
): ImageRequest =
    ImageRequest.Builder(context)
        .data(data)
        .apply {
            extras[AnimateKey] = animate
            extras[SourceKey] = uri
        }
        // Animated and still results for the same file must not share a memory cache entry.
        .memoryCacheKeyExtra("animate", animate.toString())
        .listener(listener)
        .build()

/**
 * A grid cell's request, [sizePx] square. MediaStore images come from the system thumbnail cache; images from a
 * folder picked in the system picker decode the file. The cell and the prefetch build the same request, so they
 * share one memory cache entry. INEXACT precision: loadThumbnail may return a bitmap up to about twice [sizePx],
 * and EXACT precision would reject such a cached entry on every lookup.
 */
fun gridRequest(
    context: Context,
    item: ImageItem,
    animate: Boolean,
    sizePx: Int,
    listener: ImageRequest.Listener? = null,
): ImageRequest {
    val data: Any = if (isMediaStoreUri(item.uri)) MediaThumbnail(item.uri, item.mime, sizePx) else Uri.parse(item.uri)
    return imageRequest(context, item.uri, animate, listener, data).newBuilder().size(sizePx).precision(Precision.INEXACT).build()
}
