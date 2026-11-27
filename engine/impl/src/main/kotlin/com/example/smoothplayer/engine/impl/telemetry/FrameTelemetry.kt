package com.example.smoothplayer.engine.impl.telemetry

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Display
import com.example.smoothplayer.engine.api.model.MICROS_PER_SECOND
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.api.model.Nanos
import com.example.smoothplayer.engine.api.model.PlaybackStats
import com.example.smoothplayer.engine.api.model.InBetweenCounts
import com.example.smoothplayer.engine.api.model.InterpolationMode
import com.example.smoothplayer.engine.api.model.WarpFallback
import java.io.File

private const val TAG = "FI/Telemetry"
private const val WINDOW_NS = 1_000_000_000L
private const val GPU_SAMPLES = 120
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val THERMAL_FORECAST_SECONDS = 0

/** The last [GPU_SAMPLES] GPU times of one pass, in nanoseconds. The caller holds the telemetry lock. */
private class GpuSamples {
    private val samplesNs = LongArray(GPU_SAMPLES)
    private var count = 0
    private var next = 0

    fun add(durationNs: Long) {
        samplesNs[next] = durationNs
        next = (next + 1) % GPU_SAMPLES
        if (count < GPU_SAMPLES) count++
    }

    /** The valid samples in ascending order. Copying here keeps the ring writable while the stats are computed. */
    fun sorted(): LongArray = samplesNs.copyOf(count).also { it.sort() }

    fun clear() {
        count = 0
        next = 0
    }
}

/**
 * Collects per-frame facts from the decoder and GL threads and publishes [PlaybackStats] about once a second.
 * Also writes one CSV row per released frame while logging is on.
 *
 * The decoder thread calls [onFrameReleased]. The GL thread calls [onPresentGpu] and [onMotionGpu]. All are serialised
 * on [lock], which is held only for a few arithmetic operations, except for the CSV write.
 */
internal class FrameTelemetry(
    private val context: Context,
    private val publish: (PlaybackStats) -> Unit,
) {
    private val lock = Any()
    private var masterIsAudio = false
    private var csv: TelemetryCsv? = null
    private var logFile: File? = null
    private var frameIndex = 0L
    private var totalRendered = 0L
    private var totalDropped = 0L
    private var lastDrift: Micros? = null
    @Volatile private var lastThermal: Float? = null

    // The warp, from the GL thread: in-between refreshes drawn and how many were warped, scene cuts, the fallback.
    private var windowInBetween = 0L
    private var windowWarped = 0L
    private val totalInBetweens = LongArray(InBetweenReason.entries.size)
    private var totalSceneCuts = 0L
    private var lastCutLowConfidence: Float? = null
    private var lastCutHistogram: Float? = null
    private var warpFallback: WarpFallback? = null

    private var windowStartNs: Long? = null
    private var windowRendered = 0L
    private var windowFirstPtsUs = 0L
    private var windowLastPtsUs = 0L

    // Timestamp check: every drawn texture should carry a PTS the decoder released. Anything else means the
    // texture timestamp is not the frame's presentation time, and the cadence numbers cannot be trusted.
    private val recentReleasedPts = LongArray(RECENT_RELEASED) { -1L }
    private var recentNext = 0
    private var windowDraws = 0L
    private var windowUnknownDraws = 0L

    // Cadence: how many vsyncs each content frame stayed on screen, from the GL thread's draws.
    private var lastDrawnPtsUs: Long? = null
    private var drawRunLength = 0
    private var contentPeriodUs = 0L
    private var windowRuns = 0L
    private var windowExactRuns = 0L
    private var totalRuns = 0L
    private var totalExactRuns = 0L

    private val presentGpu = GpuSamples()
    private val motionGpu = GpuSamples()
    private val drawCpu = GpuSamples()
    private var windowStatsPollMaxNs = 0L

    // Step 8 benchmark run: the per-second log, the stage-timing schedule, and this second's stage samples.
    private val deviceVitals = DeviceVitals(context)
    @Volatile private var mode = InterpolationMode.Duplicate
    private var bench: BenchLog? = null
    private var benchStartNs = 0L
    private var probe: StageProbeSchedule? = null
    private val windowInBetweens = LongArray(InBetweenReason.entries.size)
    private val windowStageNs = LongArray(PipelineStage.entries.size) { NO_SAMPLE }
    private val stageSamples = Array(PipelineStage.entries.size) { GpuSamples() }

    /** Clears the per-playback counters. A log file that is already open keeps writing. */
    fun reset(masterIsAudio: Boolean) {
        synchronized(lock) {
            this.masterIsAudio = masterIsAudio
            frameIndex = 0L
            totalRendered = 0L
            totalDropped = 0L
            lastDrift = null
            windowStartNs = null
            windowRendered = 0L
            windowFirstPtsUs = 0L
            windowLastPtsUs = 0L
            lastDrawnPtsUs = null
            drawRunLength = 0
            windowRuns = 0L
            windowExactRuns = 0L
            windowDraws = 0L
            windowUnknownDraws = 0L
            totalRuns = 0L
            totalExactRuns = 0L
            windowInBetween = 0L
            windowWarped = 0L
            windowInBetweens.fill(0L)
            totalInBetweens.fill(0L)
            totalSceneCuts = 0L
            lastCutLowConfidence = null
            lastCutHistogram = null
            warpFallback = null
        }
    }

    /** The thermal headroom read at the last stats publish, about once a second. Null when the device has none. */
    val thermalHeadroom: Float? get() = lastThermal

    /**
     * Records a frame that left the decoder. [rendered] is false for a frame that was dropped.
     * [releaseNs] is the wall-clock time of the release, from [System.nanoTime]. A/V drift is not taken here,
     * because the release happens before the frame is shown. [onVsyncDraw] measures it at display time.
     */
    fun onFrameReleased(
        ptsUs: Micros,
        rendered: Boolean,
        releaseNs: Long,
    ) {
        // The battery and screen readings are binder calls, so they are made before taking the lock the GL thread
        // shares. windowStartNs is read unlocked: a stale value costs at most one extra or one late reading.
        val windowStart = windowStartNs
        val vitals = if (windowStart != null && releaseNs - windowStart >= WINDOW_NS) deviceVitals.read() else null
        synchronized(lock) {
            frameIndex++
            if (rendered) {
                totalRendered++
                recentReleasedPts[recentNext] = ptsUs.value
                recentNext = (recentNext + 1) % RECENT_RELEASED
                if (windowRendered == 0L) windowFirstPtsUs = ptsUs.value
                windowLastPtsUs = ptsUs.value
                windowRendered++
            } else {
                totalDropped++
            }
            csv?.row(csvRow(ptsUs, releaseNs, rendered))

            val start = windowStartNs ?: releaseNs.also { windowStartNs = it }
            if (releaseNs - start >= WINDOW_NS) {
                if (windowDraws > 0L) {
                    Log.i(TAG, "vsync draws=$windowDraws, drawn pts not released by decoder=$windowUnknownDraws")
                }
                windowDraws = 0L
                windowUnknownDraws = 0L
                publish(snapshotLocked(releaseNs, vitals ?: deviceVitals.read()))
                windowStartNs = releaseNs
                windowRendered = 0L
                windowFirstPtsUs = 0L
                windowLastPtsUs = 0L
                windowRuns = 0L
                windowExactRuns = 0L
                windowInBetween = 0L
                windowWarped = 0L
                windowStatsPollMaxNs = 0L
                windowInBetweens.fill(0L)
                windowStageNs.fill(NO_SAMPLE)
            }
        }
    }

    /**
     * Records one vsync draw of the content frame at [ptsUs]. Called on the GL thread once per refresh.
     * A run of draws with the same PTS is one content frame on screen. When a run ends, it counts as exact if
     * its length matches the cadence expected from the refresh period and the content's frame period.
     */
    fun onVsyncDraw(ptsUs: Long, vsyncPeriodNs: Long, displayClockUs: Long?) {
        synchronized(lock) {
            // Drift is video PTS minus the audio clock at the time the screen shows this frame. Null when unknown.
            if (masterIsAudio) lastDrift = displayClockUs?.let { Micros(ptsUs - it) }
            windowDraws++
            if (recentReleasedPts.none { it == ptsUs }) windowUnknownDraws++
            val previous = lastDrawnPtsUs
            if (previous != null && ptsUs == previous) {
                drawRunLength++
                return
            }
            if (previous != null) {
                val gap = ptsUs - previous
                if (gap in MIN_FRAME_GAP_US..MAX_FRAME_GAP_US) {
                    contentPeriodUs = if (contentPeriodUs == 0L) gap else (contentPeriodUs * 7 + gap) / 8
                }
                if (contentPeriodUs > 0L) {
                    val expected = expectedVsyncsPerFrame(vsyncPeriodNs, contentPeriodUs)
                    windowRuns++
                    totalRuns++
                    if (drawRunLength == expected) {
                        windowExactRuns++
                        totalExactRuns++
                    }
                }
            }
            lastDrawnPtsUs = ptsUs
            drawRunLength = 1
        }
    }

    /** Records one in-between refresh of the motion-compensated mode and how it was drawn. Called on the GL thread. */
    fun onInBetweenDraw(reason: InBetweenReason) {
        synchronized(lock) {
            windowInBetween++
            if (reason == InBetweenReason.Warped) windowWarped++
            totalInBetweens[reason.ordinal]++
            windowInBetweens[reason.ordinal]++
        }
    }

    /** Records the interpolation mode, for the benchmark log. Any thread. */
    fun setMode(newMode: InterpolationMode) {
        mode = newMode
    }

    /**
     * Starts a benchmark run that writes one row a second to [log] and times the stages on [StageProbeSchedule]'s
     * schedule. Replaces any run already going.
     */
    fun startBench(log: BenchLog, nowNs: Long) {
        synchronized(lock) {
            bench?.close()
            bench = log
            benchStartNs = nowNs
            probe = StageProbeSchedule(nowNs)
            stageSamples.forEach { it.clear() }
        }
    }

    fun stopBench() {
        synchronized(lock) {
            bench?.close()
            bench = null
            probe = null
        }
    }

    /**
     * True when the GL thread should time the stages of the frame arriving now. Only during a benchmark run's probe
     * window, at most once a second. GL thread.
     */
    fun takeStageSample(nowNs: Long): Boolean = synchronized(lock) { probe?.takeSample(nowNs) ?: false }

    /** Records the wall time, to glFinish, of one pipeline stage. GL thread. */
    fun onStage(stage: PipelineStage, durationNs: Long) {
        synchronized(lock) {
            windowStageNs[stage.ordinal] = durationNs
            stageSamples[stage.ordinal].add(durationNs)
        }
    }

    /**
     * Records a frame pair held across a scene cut, with the stats that made it one, so the overlay can show what the
     * cut rule saw. Called on the GL thread, once per pair.
     */
    fun onSceneCut(lowConfidence: Float, histogramDistance: Float) {
        synchronized(lock) {
            totalSceneCuts++
            lastCutLowConfidence = lowConfidence
            lastCutHistogram = histogramDistance
        }
    }

    /** Records the warp's current fallback, or null outside the motion-compensated mode. Called on the GL thread. */
    fun onWarpFallback(fallback: WarpFallback?) {
        synchronized(lock) { warpFallback = fallback }
    }

    /** Records the GL thread's wall time to issue one present draw, swap excluded. Called on the GL thread. */
    fun onDrawCpu(durationNs: Long) {
        synchronized(lock) { drawCpu.add(durationNs) }
    }

    /** Records the wall time of one poll of a pair's stats fence (and read, when it had signalled). GL thread. */
    fun onStatsPoll(durationNs: Long) {
        synchronized(lock) { if (durationNs > windowStatsPollMaxNs) windowStatsPollMaxNs = durationNs }
    }

    /** Records one GPU time for the present pass. Called on the GL thread. */
    fun onPresentGpu(duration: Nanos) {
        synchronized(lock) { presentGpu.add(duration.value) }
    }

    /** Records one GPU time for motion estimation on a frame pair. Called on the GL thread. */
    fun onMotionGpu(duration: Nanos) {
        synchronized(lock) { motionGpu.add(duration.value) }
    }

    /** Opens [file] and writes the CSV header. Returns the path, which the engine publishes in its stats. */
    fun startLog(file: File): String {
        synchronized(lock) {
            csv?.close()
            csv = TelemetryCsv.open(file)
            logFile = file
        }
        return file.path
    }

    fun stopLog() {
        synchronized(lock) {
            csv?.close()
            csv = null
            logFile = null
        }
    }

    /** One benchmark row for the second that ends at [nowNs], from the window counters before they reset. */
    private fun benchRow(nowNs: Long, stats: PlaybackStats, vitals: Vitals, probing: Boolean): String {
        fun ms(ns: Long): String = if (ns == NO_SAMPLE) "" else "%.3f".format(java.util.Locale.ROOT, ns / NANOS_PER_MILLI)
        return listOf(
            "%.1f".format(java.util.Locale.ROOT, (nowNs - benchStartNs) / NANOS_PER_SECOND),
            mode.name,
            if (probing) "1" else "0",
            "%.2f".format(java.util.Locale.ROOT, stats.displayFps),
            "%.2f".format(java.util.Locale.ROOT, stats.contentFps),
            windowRuns.toString(),
            windowExactRuns.toString(),
            totalDropped.toString(),
            stats.avDrift?.let { (it.value / MICROS_PER_MILLI).toString() }.orEmpty(),
            stats.drawCpuP95?.let { ms(it.value) }.orEmpty(),
            ms(windowStageNs[PipelineStage.CopyAndLuma.ordinal]),
            ms(windowStageNs[PipelineStage.Motion.ordinal]),
            ms(windowStageNs[PipelineStage.Warp.ordinal]),
            stats.thermalHeadroom?.let { "%.3f".format(java.util.Locale.ROOT, it) }.orEmpty(),
            vitals.thermalStatus?.toString().orEmpty(),
            vitals.batteryPercent?.toString().orEmpty(),
            vitals.batteryCurrentMa?.toString().orEmpty(),
            vitals.batteryTempC?.toString().orEmpty(),
            vitals.charging?.let { if (it) "1" else "0" }.orEmpty(),
            vitals.brightness?.toString().orEmpty(),
            warpFallback?.name.orEmpty(),
            windowInBetweens[InBetweenReason.Warped.ordinal].toString(),
            windowInBetweens[InBetweenReason.SearchPending.ordinal].toString(),
            windowInBetweens[InBetweenReason.NotSearched.ordinal].toString(),
            windowInBetweens[InBetweenReason.LowConfidence.ordinal].toString(),
            windowInBetweens[InBetweenReason.Held.ordinal].toString(),
            totalSceneCuts.toString(),
        ).joinToString(",")
    }

    private fun csvRow(ptsUs: Micros, releaseNs: Long, rendered: Boolean): String = listOf(
        frameIndex.toString(),
        ptsUs.value.toString(),
        releaseNs.toString(),
        "",
        "",
        "REAL",
        "",
        "",
        "",
        "",
        lastThermal?.toString().orEmpty(),
        if (rendered) "0" else "1",
    ).joinToString(",")

    private fun snapshotLocked(nowNs: Long, vitals: Vitals): PlaybackStats {
        val elapsedSeconds = (nowNs - (windowStartNs ?: nowNs)).coerceAtLeast(1L) / NANOS_PER_SECOND
        val gpu = presentGpu.sorted()
        val motion = motionGpu.sorted()
        val draw = drawCpu.sorted()
        lastThermal = readThermalHeadroom()
        val probing = probe?.inWindow(nowNs) ?: false
        val stats = PlaybackStats(
            displayFps = (windowRendered / elapsedSeconds).toFloat(),
            contentFps = contentFps(windowRendered, windowFirstPtsUs, windowLastPtsUs),
            renderedFrames = totalRendered,
            droppedFrames = totalDropped,
            avDrift = lastDrift,
            presentGpuP50 = percentile(gpu, P50)?.let { Nanos(it) },
            presentGpuP95 = percentile(gpu, P95)?.let { Nanos(it) },
            motionGpuP50 = percentile(motion, P50)?.let { Nanos(it) },
            motionGpuP95 = percentile(motion, P95)?.let { Nanos(it) },
            refreshHz = refreshHz(),
            thermalHeadroom = lastThermal,
            cadenceExactPercent = if (windowRuns > 0L) windowExactRuns * PERCENT / windowRuns else null,
            cadenceAverageExactPercent = if (totalRuns > 0L) totalExactRuns * PERCENT / totalRuns else null,
            logPath = logFile?.path,
            warpFallback = warpFallback,
            warpPercent = if (warpFallback != null && windowInBetween > 0L) windowWarped * PERCENT / windowInBetween else null,
            inBetweens = if (warpFallback != null) inBetweenCounts() else null,
            sceneCuts = totalSceneCuts,
            lastCutLowConfidence = lastCutLowConfidence,
            lastCutHistogram = lastCutHistogram,
            drawCpuP95 = percentile(draw, P95)?.let { Nanos(it) },
            statsPollMax = if (windowStatsPollMaxNs > 0L) Nanos(windowStatsPollMaxNs) else null,
            benchPath = bench?.displayPath,
            stageProbeOn = probing,
            stageCopyP95 = stageP95(PipelineStage.CopyAndLuma),
            stageMotionP95 = stageP95(PipelineStage.Motion),
            stageWarpP95 = stageP95(PipelineStage.Warp),
            batteryPercent = vitals.batteryPercent,
            batteryCurrentMa = vitals.batteryCurrentMa,
            batteryTempC = vitals.batteryTempC,
            thermalStatus = vitals.thermalStatus,
        )
        bench?.let { log ->
            try {
                log.row(benchRow(nowNs, stats, vitals, probing))
            } catch (e: java.io.IOException) {
                Log.w(TAG, "benchmark log write failed, run stopped", e)
                bench = null
                probe = null
            }
        }
        return stats
    }

    private fun stageP95(stage: PipelineStage): Nanos? =
        percentile(stageSamples[stage.ordinal].sorted(), P95)?.let { Nanos(it) }


    private fun inBetweenCounts(): InBetweenCounts = InBetweenCounts(
        warped = totalInBetweens[InBetweenReason.Warped.ordinal],
        searchPending = totalInBetweens[InBetweenReason.SearchPending.ordinal],
        notSearched = totalInBetweens[InBetweenReason.NotSearched.ordinal],
        lowConfidence = totalInBetweens[InBetweenReason.LowConfidence.ordinal],
        held = totalInBetweens[InBetweenReason.Held.ordinal],
    )

    private fun refreshHz(): Float {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        return display?.refreshRate ?: 0f
    }

    /** Null before Android 11, which is when the thermal API arrived. */
    private fun readThermalHeadroom(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val power = context.getSystemService(PowerManager::class.java) ?: return null
        val headroom = power.getThermalHeadroom(THERMAL_FORECAST_SECONDS)
        return if (headroom.isNaN()) null else headroom
    }

    private companion object {
        const val P50 = 0.50
        const val P95 = 0.95
        const val PERCENT = 100f
        const val RECENT_RELEASED = 16
        const val MIN_FRAME_GAP_US = 5_000L
        const val MAX_FRAME_GAP_US = 200_000L
        const val NO_SAMPLE = -1L
        const val NANOS_PER_MILLI = 1_000_000.0
        const val MICROS_PER_MILLI = 1_000L
    }
}

/**
 * Vsyncs a content frame should stay on screen: the content frame period divided by the refresh period, rounded.
 * Never less than one, so a content rate above the refresh rate still counts as one refresh per frame.
 */
internal fun expectedVsyncsPerFrame(vsyncPeriodNs: Long, contentPeriodUs: Long): Int {
    if (vsyncPeriodNs <= 0L) return 1
    val ratio = contentPeriodUs * NANOS_PER_MICRO / vsyncPeriodNs.toDouble()
    return kotlin.math.round(ratio).toInt().coerceAtLeast(1)
}

private const val NANOS_PER_MICRO = 1_000L

/**
 * Frames per second of the content, from the PTS span of the rendered frames in a window. Zero when the span
 * is empty, so one frame or identical timestamps do not divide by zero.
 */
internal fun contentFps(frames: Long, firstPtsUs: Long, lastPtsUs: Long): Float {
    val spanUs = lastPtsUs - firstPtsUs
    if (frames < 2L || spanUs <= 0L) return 0f
    return ((frames - 1L) * MICROS_PER_SECOND / spanUs.toDouble()).toFloat()
}

/**
 * Nearest-rank percentile of [sorted], which must already be in ascending order. Null when there are no samples.
 */
internal fun percentile(sorted: LongArray, fraction: Double): Long? {
    if (sorted.isEmpty()) return null
    val rank = kotlin.math.ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

/** How one in-between refresh of the motion-compensated mode was drawn (see [InBetweenCounts]). */
internal enum class InBetweenReason { Warped, SearchPending, NotSearched, LowConfidence, Held }

/** The stages a benchmark run times with glFinish (see [StageProbeSchedule]). */
internal enum class PipelineStage {
    /** The OES copy into the ring and both motion-luma levels, once per arriving frame. */
    CopyAndLuma,

    /** The motion search, median and stats passes, once per pair. */
    Motion,

    /** One warped in-between draw. */
    Warp,
}
