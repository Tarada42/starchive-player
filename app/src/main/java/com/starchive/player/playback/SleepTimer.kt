package com.starchive.player.playback

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Simple per-session countdown helper.
 *
 * Started timers are cancelled when a new duration is started, when [cancel] is called,
 * or when the owning [CoroutineScope] is cancelled. The remaining time is measured with
 * a monotonic clock so it stays correct across device sleep.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val onExpired: () -> Unit,
) {
    private var job: Job? = null
    private var startTimeMs: Long = 0L
    private var durationMs: Long = 0L

    fun start(remainingMs: Long) {
        cancel()
        durationMs = remainingMs.coerceAtLeast(0L)
        startTimeMs = SystemClock.elapsedRealtime()
        job = scope.launch {
            delay(durationMs)
            onExpired()
            job = null
            startTimeMs = 0L
            durationMs = 0L
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        startTimeMs = 0L
        durationMs = 0L
    }

    fun remainingMs(): Long? {
        val j = job ?: return null
        if (!j.isActive) return null
        val elapsed = SystemClock.elapsedRealtime() - startTimeMs
        return (durationMs - elapsed).coerceAtLeast(0L)
    }
}
