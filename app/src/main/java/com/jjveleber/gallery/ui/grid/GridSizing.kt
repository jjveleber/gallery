package com.jjveleber.gallery.ui.grid

import com.jjveleber.gallery.model.GridSize

/** One preset step per pinch gesture: spread fingers for bigger cells, pinch for smaller. */
fun GridSize.step(zoom: Float): GridSize = when {
    zoom > 1.25f -> GridSize.entries.getOrElse(ordinal + 1) { this }
    zoom < 0.8f -> GridSize.entries.getOrElse(ordinal - 1) { this }
    else -> this
}
