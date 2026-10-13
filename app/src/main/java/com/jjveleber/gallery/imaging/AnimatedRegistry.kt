package com.jjveleber.gallery.imaging

import java.util.concurrent.ConcurrentHashMap

/** Remembers which images were sniffed as animated, so the grid can badge them without re-reading files. */
class AnimatedRegistry {
    private val animated = ConcurrentHashMap<String, Boolean>()

    fun record(key: String, animated: Boolean) {
        this.animated[key] = animated
    }

    fun isAnimated(key: String): Boolean = animated[key] == true
}
