package com.jjveleber.gallery.model

/** Grid presets by target cell size; the column count adapts to the screen width. */
enum class GridSize(val cellDp: Int) { S(120), M(180), L(300), XL(480) }

enum class Transition { Crossfade, None }

val INTERVALS_SEC = listOf(3, 5, 10, 15, 30, 60, 120, 300)

data class Settings(
    val sort: SortSpec = SortSpec(),
    val gridSize: GridSize = GridSize.M,
    val animateGrid: Boolean = false,
    val animateFullScreen: Boolean = true,
    val autoAdvance: Boolean = false,
    val intervalSec: Int = 5,
    val transition: Transition = Transition.Crossfade,
)
