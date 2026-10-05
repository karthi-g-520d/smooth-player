package com.example.smoothplayer.engine.impl.pacer

import android.os.Handler
import android.view.Choreographer
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.impl.clock.MasterClock
import com.example.smoothplayer.engine.impl.threading.GlThread

private const val DEFAULT_PERIOD_NS = 16_666_667L
// How many refreshes after the draw the frame is presented. Two keeps more buffers in the compositor's queue, so each
// swap waited for a free buffer (58% of callback time in the trace). One returns buffers sooner.
private const val PIPELINE_DEPTH_VSYNCS = 1L
private const val NS_PER_US = 1_000L
private const val MIN_PERIOD_NS = 4_000_000L
private const val MAX_PERIOD_NS = 50_000_000L

/**
 * Drives presentation from the display. On every vsync it works out which content time will be on screen
 * when the frame drawn now is shown, and publishes that as the release target. The decoder releases a frame
 * once its timestamp reaches the target, so the decoder never runs ahead of the screen.
 *
 * Runs on the GL thread, because Choreographer callbacks come from that thread's looper. [releaseTargetUs] is read
 * from the decoder thread, so it is volatile. It is null while the pacer is stopped, and the decoder then falls
 * back to the clock.
 */
@GlThread
internal class VsyncPacer(
    private val handler: Handler,
    private val drawVsync: (presentNs: Long, periodNs: Long) -> Unit,
) {
    @Volatile var releaseTargetUs: Micros? = null
        private set

    private val choreographer = Choreographer.getInstance()
    private var clock: MasterClock? = null
    private var onTick: () -> Unit = {}
    private var running = false
    private var lastVsyncNs = 0L
    private var periodNs = DEFAULT_PERIOD_NS

    private val frameCallback = Choreographer.FrameCallback { vsyncNs -> onVsync(vsyncNs) }

    /**
     * Starts the vsync loop against [timeSource]. [onTick] runs on every vsync, after the release target is
     * published, so the decoder can release its frames right after the vsync rather than at a random moment.
     * Safe to call while already running.
     */
    fun start(timeSource: MasterClock, onTick: () -> Unit) {
        handler.post {
            clock = timeSource
            this.onTick = onTick
            if (running) return@post
            running = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    /** Stops the loop and clears the release target, so the decoder falls back to the clock. Returns at once. */
    fun stop() {
        handler.post { stopNow() }
    }

    /**
     * Stops the loop on the calling thread, which must be the GL thread. Once this returns, no callback reads the
     * clock again, so the audio track may be released.
     */
    fun stopNow() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
        releaseTargetUs = null
        lastVsyncNs = 0L
    }

    /** Vsync period as last measured. Used for presentation timing and cadence statistics. */
    fun currentPeriodNs(): Long = periodNs

    private fun onVsync(vsyncNs: Long) {
        if (!running) return
        measurePeriod(vsyncNs)
        val presentNs = vsyncNs + PIPELINE_DEPTH_VSYNCS * periodNs
        // The frame the next vsync draws is shown at presentNs + one period. Releasing against that time keeps the
        // video on the audio clock as the screen sees it, with no lead added.
        val nextPresentNs = presentNs + periodNs
        // Half a period of phase keeps every frame boundary halfway between two vsyncs. A boundary that sits right on a
        // vsync flips to the next refresh on a small clock wobble, and the refreshes per frame change.
        releaseTargetUs = Micros(contentUsAt(nextPresentNs) + periodNs / (2 * NS_PER_US))
        onTick()
        drawVsync(presentNs, periodNs)
        choreographer.postFrameCallback(frameCallback)
    }

    /** Smooths the period over recent vsyncs, so one late callback does not shift the timing. */
    private fun measurePeriod(vsyncNs: Long) {
        if (lastVsyncNs != 0L) {
            val measured = vsyncNs - lastVsyncNs
            if (measured in MIN_PERIOD_NS..MAX_PERIOD_NS) {
                periodNs = (periodNs * PERIOD_KEEP + measured) / (PERIOD_KEEP + 1)
            }
        }
        lastVsyncNs = vsyncNs
    }

    /**
     * The content time the screen shows at [presentNs], or null while the clock has no reference point yet.
     * Telemetry compares video PTS against this, so drift is measured where the viewer sees it.
     */
    fun displayContentUsOrNull(presentNs: Long): Long? {
        val source = clock ?: return null
        if (!source.isReady()) return null
        return contentUsAt(presentNs)
    }

    /** Media time, in microseconds, that the clock will read at [timeNs] if it keeps running at normal speed. */
    private fun contentUsAt(timeNs: Long): Long {
        val nowNs = System.nanoTime()
        return checkNotNull(clock).nowUs().value + (timeNs - nowNs) / NS_PER_US
    }

    private companion object {
        const val PERIOD_KEEP = 7L
    }
}
