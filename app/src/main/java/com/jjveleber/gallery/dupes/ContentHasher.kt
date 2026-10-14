package com.jjveleber.gallery.dupes

import android.content.ContentResolver
import android.net.Uri
import com.jjveleber.gallery.model.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** The SHA-256 of [input]'s bytes as lowercase hex. Reads to the end; does not close [input]. */
fun sha256Hex(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * File hashes kept in [file], one "key\thash" line each. A key holds the URI, size and modified time, so an edited
 * file gets a new key instead of a stale hash. Entries for deleted files stay until the file is cleared; each is
 * about 100 bytes.
 */
class HashCache(private val file: File) {
    private val entries = ConcurrentHashMap<String, String>()
    private val lock = Mutex()
    private var loaded = false
    private var dirty = false

    fun key(item: ImageItem): String = "${item.uri}|${item.sizeBytes}|${item.modifiedMs}"

    suspend fun get(key: String): String? {
        load()
        return entries[key]
    }

    suspend fun put(key: String, hash: String) {
        load()
        entries[key] = hash
        lock.withLock { dirty = true }
    }

    /** Writes the entries when any were added, through a temporary file so a crash cannot leave half a file. */
    suspend fun save() = lock.withLock {
        if (!dirty) return@withLock
        withContext(Dispatchers.IO) {
            val tmp = File(file.path + ".tmp")
            tmp.bufferedWriter().use { w -> entries.forEach { (k, v) -> w.append(k).append('\t').append(v).append('\n') } }
            if (!tmp.renameTo(file)) throw IOException("Cannot replace $file")
        }
        dirty = false
    }

    private suspend fun load() = lock.withLock {
        if (loaded) return@withLock
        withContext(Dispatchers.IO) {
            if (file.exists()) {
                file.forEachLine { line ->
                    val tab = line.lastIndexOf('\t')
                    if (tab > 0) entries[line.substring(0, tab)] = line.substring(tab + 1)
                }
            }
        }
        loaded = true
    }
}

/** Hashes MediaStore and document files through [resolver], remembering results in [cache]. */
class ContentHasher(private val resolver: ContentResolver, private val cache: HashCache) : ImageHasher {
    override suspend fun hash(item: ImageItem): String? {
        val key = cache.key(item)
        cache.get(key)?.let { return it }
        val hash = withContext(Dispatchers.IO) {
            try {
                resolver.openInputStream(Uri.parse(item.uri))?.use(::sha256Hex)
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
        } ?: return null
        cache.put(key, hash)
        return hash
    }

    override suspend fun save() {
        try {
            cache.save()
        } catch (e: IOException) {
            // Only a cache: the hashes are computed again next time.
        }
    }
}
