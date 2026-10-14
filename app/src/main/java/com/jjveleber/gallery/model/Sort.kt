package com.jjveleber.gallery.model

import kotlin.random.Random

enum class SortOrder { Name, Modified, Path, Size, Random }

data class SortSpec(
    val order: SortOrder = SortOrder.Name,
    val ascending: Boolean = true,
    val randomSeed: Long = 0L,
)

/**
 * Natural, case-insensitive order: digit runs compare by numeric value, so "img2" sorts before "img10".
 * Strings equal under these rules fall back to plain comparison, so the order is total.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                val startA = i
                while (i < a.length && a[i].isAsciiDigit()) i++
                val startB = j
                while (j < b.length && b[j].isAsciiDigit()) j++
                // Compare digit runs as text after stripping leading zeros, so any length works without overflow.
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val c = numA.compareTo(numB)
                if (c != 0) return c
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}

private val byName: Comparator<ImageItem> =
    compareBy<ImageItem, String>(NaturalOrder) { it.name }.thenBy(NaturalOrder) { it.relPath }

// Folder segments compare one at a time, so a sibling like "a-b" cannot land between "a" and "a/sub".
private val bySegments = Comparator<List<String>> { x, y ->
    for (k in 0 until minOf(x.size, y.size)) {
        val c = NaturalOrder.compare(x[k], y[k])
        if (c != 0) return@Comparator c
    }
    x.size - y.size
}

private val byPath: Comparator<ImageItem> =
    compareBy<ImageItem, List<String>>(bySegments) {
        it.relPath.substringBeforeLast('/', "").let { dir -> if (dir.isEmpty()) emptyList() else dir.split('/') }
    }.thenBy(NaturalOrder) { it.name }

fun sortItems(items: List<ImageItem>, spec: SortSpec): List<ImageItem> {
    val comparator = when (spec.order) {
        SortOrder.Name -> byName
        SortOrder.Path -> byPath
        SortOrder.Modified -> compareBy<ImageItem> { it.modifiedMs }.then(byName)
        SortOrder.Size -> compareBy<ImageItem> { it.sizeBytes }.then(byName)
        // Start from a fixed order so a seed gives the same shuffle whatever order the scan returned.
        SortOrder.Random -> return items.sortedWith(byPath).shuffled(Random(spec.randomSeed))
    }
    return items.sortedWith(if (spec.ascending) comparator else comparator.reversed())
}
