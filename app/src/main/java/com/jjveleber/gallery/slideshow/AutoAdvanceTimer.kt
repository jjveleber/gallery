package com.jjveleber.gallery.slideshow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class PauseReason { Zoomed, Sheet, Background }

/** Calls [onAdvance] every interval while enabled and not paused for any reason. Any change restarts the countdown. */
class AutoAdvanceTimer(private val scope: CoroutineScope, private val onAdvance: () -> Unit) {
    private var job: Job? = null
    private var enabled = false
    private var intervalMs = 5_000L
    private val pauses = mutableSetOf<PauseReason>()

    val isTicking: Boolean get() = job?.isActive == true

    fun setEnabled(on: Boolean) {
        enabled = on
        restart()
    }

    fun setInterval(ms: Long) {
        intervalMs = ms
        restart()
    }

    /** Call after manual navigation so the next auto advance is a full interval away. */
    fun reset() = restart()

    fun pause(reason: PauseReason) {
        if (pauses.add(reason)) restart()
    }

    fun resume(reason: PauseReason) {
        if (pauses.remove(reason)) restart()
    }

    private fun restart() {
        job?.cancel()
        job = if (enabled && pauses.isEmpty()) {
            scope.launch {
                while (isActive) {
                    delay(intervalMs)
                    onAdvance()
                }
            }
        } else {
            null
        }
    }
}
