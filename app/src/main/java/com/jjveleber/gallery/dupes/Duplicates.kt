package com.jjveleber.gallery.dupes

import com.jjveleber.gallery.model.ImageItem

/** Content hashes of image files. Implemented over ContentResolver on the device and faked in tests. */
interface ImageHasher {
    /** A hash of the file's bytes, or null when it cannot be read. */
    suspend fun hash(item: ImageItem): String?

    /** Persists hashes computed so far, so the next launch need not read those files again. */
    suspend fun save() = Unit
}

/** The other byte-identical copies of an image. [hash] is shared by the whole group, so copies can be shown together. */
data class Copies(val hash: String, val others: List<ImageItem>)

/**
 * The byte-identical copies in [pool] of each of [targets], keyed by target URI; targets without copies are left out.
 * Files can only be identical when their sizes match, so only files that share a size with a target are hashed: a
 * folder whose sizes are all unique costs no reads. Files of unknown size (0) are skipped. [onProgress] gets the
 * number of files hashed and the number to hash.
 */
suspend fun findDuplicates(
    targets: List<ImageItem>,
    pool: List<ImageItem>,
    hasher: ImageHasher,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): Map<String, Copies> {
    val bySize = (targets + pool).distinctBy { it.uri }.filter { it.sizeBytes > 0 }.groupBy { it.sizeBytes }
    val toHash = targets.map { it.sizeBytes }.distinct().mapNotNull { bySize[it] }.filter { it.size > 1 }.flatten()
    onProgress(0, toHash.size)
    val byHash = toHash.mapIndexedNotNull { i, item ->
        hasher.hash(item).also { onProgress(i + 1, toHash.size) }?.let { it to item }
    }.groupBy({ it.first }, { it.second })
    val targetUris = targets.mapTo(HashSet()) { it.uri }
    return buildMap {
        for ((hash, group) in byHash) {
            if (group.size < 2) continue
            for (item in group) if (item.uri in targetUris) put(item.uri, Copies(hash, group.filter { it.uri != item.uri }))
        }
    }
}

/**
 * The URIs to select to keep one copy of each group in [items]: every image with copies except the first of its group
 * in [items] order. A group's copies outside [items] do not count, so at least one copy always stays in [items].
 */
fun extraCopies(items: List<ImageItem>, copies: Map<String, Copies>): Set<String> =
    items.filter { it.uri in copies }
        .groupBy { copies.getValue(it.uri).hash }
        .values
        .flatMapTo(HashSet()) { group -> group.drop(1).map { it.uri } }
