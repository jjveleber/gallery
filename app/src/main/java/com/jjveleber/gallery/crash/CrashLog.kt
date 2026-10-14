package com.jjveleber.gallery.crash

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val FILE_NAME = "crash-log.txt"
private const val MAX_BYTES = 64 * 1024

/**
 * Keeps the newest uncaught exception in one file so the next launch can show it. [deviceInfo] is the
 * app and device lines of the header (the caller reads them from the platform, which keeps this JVM-testable).
 */
class CrashLog(dir: File, private val deviceInfo: String) {
    private val file = File(dir, FILE_NAME)

    /** Records every uncaught exception, then always hands it on to the handler that was installed before. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(thread, throwable)
            } catch (_: Throwable) {
                // Never let the log break the crash path (OutOfMemoryError included).
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    internal fun write(thread: Thread, throwable: Throwable) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        // Stream through a capped writer: no big String, and the header is always written first.
        PrintWriter(CappedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8), MAX_BYTES)).use { out ->
            out.println("Time: $time")
            out.println(deviceInfo)
            out.println("Thread: ${thread.name}")
            out.println()
            throwable.printStackTrace(out)
        }
    }

    /** The saved report, or null when there is none, it is blank, or it cannot be read. */
    fun read(): String? = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear() {
        file.delete()
    }
}

/** Passes through at most [maxBytes] (counted as UTF-8) and silently drops the rest. */
private class CappedWriter(private val out: Writer, private val maxBytes: Int) : Writer() {
    private var bytes = 0

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        var n = 0
        while (n < len) {
            val c = cbuf[off + n]
            val size = if (c.code < 0x80) 1 else if (c.code < 0x800) 2 else 3
            if (bytes + size > maxBytes) break
            bytes += size
            n++
        }
        if (n > 0) out.write(cbuf, off, n)
    }

    override fun flush() = out.flush()

    override fun close() = out.close()
}
