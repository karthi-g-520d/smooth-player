package com.example.smoothplayer.engine.impl.telemetry

/** How often a benchmark run's probe window starts, and how long it lasts: the first minute of every five. */
internal const val PROBE_CYCLE_NS = 300_000_000_000L
internal const val PROBE_WINDOW_NS = 60_000_000_000L

/** At most one timed sample of each stage per second, so the stalls stay rare even inside a probe window. */
internal const val PROBE_SPACING_NS = 1_000_000_000L

/**
 * When a benchmark run times the pipeline stages. Timing waits for the GPU with glFinish, which can push a draw past
 * its vsync, so it runs only in the first minute of every [PROBE_CYCLE_NS] and at most once per [PROBE_SPACING_NS].
 * The rows logged outside the windows give clean cadence, and the windows spread over the run show whether the stage
 * times hold up as the phone heats. Pure, so the schedule is JVM-tested. Not thread-safe: the caller serialises.
 */
internal class StageProbeSchedule(private val startNs: Long) {
    private var lastSampleNs: Long? = null

    /** True when [nowNs] falls in a probe window. */
    fun inWindow(nowNs: Long): Boolean = probeWindowAt(nowNs - startNs)

    /** True when a sample should start at [nowNs]. Records it, so the next one waits [PROBE_SPACING_NS]. */
    fun takeSample(nowNs: Long): Boolean {
        if (!inWindow(nowNs)) return false
        val last = lastSampleNs
        if (last != null && nowNs - last < PROBE_SPACING_NS) return false
        lastSampleNs = nowNs
        return true
    }
}

/** True when [elapsedNs] since the start of a run falls in the first [PROBE_WINDOW_NS] of a [PROBE_CYCLE_NS] cycle. */
internal fun probeWindowAt(elapsedNs: Long): Boolean = elapsedNs >= 0L && elapsedNs % PROBE_CYCLE_NS < PROBE_WINDOW_NS
