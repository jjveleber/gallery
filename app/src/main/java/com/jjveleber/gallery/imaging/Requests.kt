package com.jjveleber.gallery.imaging

import android.content.Context
import android.net.Uri
import coil3.Extras
import coil3.request.ImageRequest

/** Whether this request may produce an animated image. */
val AnimateKey = Extras.Key(default = false)

/** The request's URI string, so the decoder can record sniff results in [AnimatedRegistry]. */
val SourceKey = Extras.Key(default = "")

fun imageRequest(
    context: Context,
    uri: String,
    animate: Boolean,
    listener: ImageRequest.Listener? = null,
): ImageRequest =
    ImageRequest.Builder(context)
        .data(Uri.parse(uri))
        .apply {
            extras[AnimateKey] = animate
            extras[SourceKey] = uri
        }
        // Animated and still results for the same file must not share a memory cache entry.
        .memoryCacheKeyExtra("animate", animate.toString())
        .listener(listener)
        .build()
