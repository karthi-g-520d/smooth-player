package com.example.smoothplayer.engine.impl.clock

import android.os.SystemClock
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.api.model.Nanos

/**
 * Monotonic clock for files with no audio track. It pauses and resumes the way the audio clock does.
 *
 * The engine calls [start], [pause] and [resume]. The decoder thread calls [nowUs]. Every method is
 * synchronized, because the two sides run on different threads.
 */
internal class WallClock : MasterClock {
    private var accumulatedNs = 0L
    private var runningSinceNs: Long? = null

    @Synchronized
    fun start() {
        accumulatedNs = 0L
        resume()
    }

    @Synchronized
    fun pause() {
        val since = runningSinceNs ?: return
        accumulatedNs += SystemClock.elapsedRealtimeNanos() - since
        runningSinceNs = null
    }

    @Synchronized
    fun resume() {
        if (runningSinceNs == null) runningSinceNs = SystemClock.elapsedRealtimeNanos()
    }

    /** Sets the media time to [at]. Keeps the running or paused state as it was. */
    @Synchronized
    fun reset(at: Micros) {
        accumulatedNs = at.toNanos().value
        if (runningSinceNs != null) runningSinceNs = SystemClock.elapsedRealtimeNanos()
    }

    @Synchronized
    override fun nowUs(): Micros {
        val runningNs = runningSinceNs?.let { SystemClock.elapsedRealtimeNanos() - it } ?: 0L
        return Nanos(accumulatedNs + runningNs).toMicros()
    }

    override fun isStalled(): Boolean = false

    /** The wall clock needs no anchor, so it is always ready. */
    override fun isReady(): Boolean = true
}
