package com.jjveleber.gallery.imaging

import coil3.ImageLoader
import coil3.PlatformContext

fun buildImageLoader(context: PlatformContext, registry: AnimatedRegistry): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(MediaThumbnailFetcher.Factory(context.contentResolver, registry))
            add(MediaThumbnailFetcher.CacheKeyer)
            add(AnimatedDecoder.Factory(registry))
        }
        .build()
