package com.jjveleber.gallery.slideshow

import com.jjveleber.gallery.model.INTERVALS_SEC

/** Maps pager pages to item indexes over a large virtual page range, so swiping wraps around both ways. */
object LoopPaging {
    private const val VIRTUAL_PAGES = 10_000_000

    fun pageCount(n: Int): Int = if (n <= 1) n else VIRTUAL_PAGES

    fun startPage(index: Int, n: Int): Int {
        if (n <= 1) return 0
        val middle = VIRTUAL_PAGES / 2
        return middle - middle % n + index
    }

    fun indexOf(page: Int, n: Int): Int = if (n <= 1) 0 else page % n
}

enum class TapZone { Prev, Middle, Next }

/** The outer 15% on each side navigates; the middle toggles the overlay. */
fun tapZone(x: Float, width: Float): TapZone = when {
    x < width * 0.15f -> TapZone.Prev
    x > width * 0.85f -> TapZone.Next
    else -> TapZone.Middle
}

fun nextInterval(current: Int): Int {
    val i = INTERVALS_SEC.indexOf(current)
    return if (i < 0) INTERVALS_SEC.first() else INTERVALS_SEC[(i + 1) % INTERVALS_SEC.size]
}

fun intervalLabel(sec: Int): String = if (sec < 60) "$sec s" else "${sec / 60} min"
