package com.jjveleber.gallery.crash

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class CrashLogTest {
    private lateinit var dir: File
    private lateinit var original: Thread.UncaughtExceptionHandler

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("crashlog").toFile()
        original = Thread.getDefaultUncaughtExceptionHandler() ?: Thread.UncaughtExceptionHandler { _, _ -> }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
        dir.deleteRecursively()
    }

    private fun log() = CrashLog(dir, "App 1.2.3 (45)\nAcme Phone 9\nAndroid 14 (SDK 34)")

    @Test
    fun `writes header and the throwable with its cause`() {
        val crash = IllegalStateException("outer boom", IllegalArgumentException("inner cause"))
        log().write(Thread.currentThread(), crash)

        val text = log().read()!!
        assertTrue(text, "App 1.2.3 (45)" in text)
        assertTrue(text, "Acme Phone 9" in text)
        assertTrue(text, "Android 14 (SDK 34)" in text)
        assertTrue(text, "Thread: ${Thread.currentThread().name}" in text)
        assertTrue(text, "java.lang.IllegalStateException: outer boom" in text)
        assertTrue(text, "java.lang.IllegalArgumentException: inner cause" in text)
    }

    @Test
    fun `handler calls the previous handler with the same thread and throwable`() {
        var seen: Pair<Thread, Throwable>? = null
        Thread.setDefaultUncaughtExceptionHandler { t, e -> seen = t to e }
        log().install()
        val crash = RuntimeException("x")

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash)

        assertSame(Thread.currentThread(), seen!!.first)
        assertSame(crash, seen!!.second)
        assertNotNull(log().read())
    }

    @Test
    fun `handler still calls the previous handler when writing fails`() {
        var seen: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen = e }
        log().install()
        dir.deleteRecursively()
        val crash = RuntimeException("x")

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash)

        assertSame(crash, seen)
    }

    @Test
    fun `a second crash overwrites the first`() {
        log().write(Thread.currentThread(), RuntimeException("first crash"))
        log().write(Thread.currentThread(), RuntimeException("second crash"))

        val text = log().read()!!
        assertFalse(text, "first crash" in text)
        assertTrue(text, "second crash" in text)
    }

    @Test
    fun `a huge trace is capped at 64 KB and the header survives`() {
        log().write(Thread.currentThread(), RuntimeException("x".repeat(500_000)))

        val file = File(dir, "crash-log.txt")
        assertTrue(file.length() <= 64 * 1024)
        assertTrue(file.length() > 60 * 1024)
        assertTrue(log().read()!!.contains("Acme Phone 9"))
    }

    @Test
    fun `read is null with no file and clear removes the file`() {
        assertNull(log().read())

        log().write(Thread.currentThread(), RuntimeException("x"))
        assertNotNull(log().read())
        log().clear()

        assertNull(log().read())
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `a blank file reads as null`() {
        File(dir, "crash-log.txt").writeText("  \n")
        assertNull(log().read())
    }

    @Test
    fun `an unreadable file reads as null`() {
        val f = File(dir, "crash-log.txt")
        f.writeText("something")
        assumeTrue(f.setReadable(false) && !f.canRead())
        assertNull(log().read())
    }
}
