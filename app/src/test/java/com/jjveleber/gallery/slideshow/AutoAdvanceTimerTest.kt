package com.jjveleber.gallery.slideshow

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoAdvanceTimerTest {
    private var advances = 0

    private fun TestScope.timer(intervalMs: Long = 5_000) =
        AutoAdvanceTimer(backgroundScope) { advances++ }.apply { setInterval(intervalMs) }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test fun ticksEveryInterval() = runTest {
        val t = timer()
        t.setEnabled(true)
        advance(4_999); assertEquals(0, advances)
        advance(1); assertEquals(1, advances)
        advance(5_000); assertEquals(2, advances)
    }

    @Test fun disabledNeverTicks() = runTest {
        timer()
        advance(60_000)
        assertEquals(0, advances)
    }

    @Test fun resetRestartsTheCountdown() = runTest {
        val t = timer()
        t.setEnabled(true)
        advance(4_000)
        t.reset()
        advance(4_000); assertEquals(0, advances)
        advance(1_000); assertEquals(1, advances)
    }

    @Test fun overlappingPausesAllMustResume() = runTest {
        val t = timer()
        t.setEnabled(true)
        t.pause(PauseReason.Zoomed)
        t.pause(PauseReason.Sheet)
        t.resume(PauseReason.Zoomed)
        advance(20_000); assertEquals(0, advances)
        assertFalse(t.isTicking)
        t.resume(PauseReason.Sheet)
        assertTrue(t.isTicking)
        advance(5_000); assertEquals(1, advances)
    }

    @Test fun resumingAnUnpausedReasonDoesNotRestart() = runTest {
        val t = timer()
        t.setEnabled(true)
        advance(4_000)
        t.resume(PauseReason.Background)
        advance(1_000); assertEquals(1, advances)
    }

    @Test fun intervalChangeAppliesImmediately() = runTest {
        val t = timer()
        t.setEnabled(true)
        advance(2_000)
        t.setInterval(3_000)
        advance(2_999); assertEquals(0, advances)
        advance(1); assertEquals(1, advances)
    }
}
