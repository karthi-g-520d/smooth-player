package com.example.smoothplayer.engine.impl.telemetry

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import com.example.smoothplayer.engine.api.model.MICROS_PER_SECOND
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.api.model.Nanos
import com.example.smoothplayer.engine.api.model.PlaybackStats
import java.io.File

private const val WINDOW_NS = 1_000_000_000L
private const val GPU_SAMPLES = 120
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val THERMAL_FORECAST_SECONDS = 0

/**
 * Collects per-frame facts from the decoder and GL threads and publishes [PlaybackStats] about once a second.
 * Also writes one CSV row per released frame while logging is on.
 *
 * The decoder thread calls [onFrameReleased]. The GL thread calls [onPresentGpu]. Both are serialised on [lock],
 * which is held only for a few arithmetic operations, except for the CSV write.
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
    private var lastThermal: Float? = null

    private var windowStartNs: Long? = null
    private var windowRendered = 0L
    private var windowFirstPtsUs = 0L
    private var windowLastPtsUs = 0L

    private val gpuSamplesNs = LongArray(GPU_SAMPLES)
    private var gpuCount = 0
    private var gpuNext = 0

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
        }
    }

    /**
     * Records a frame that left the decoder. [rendered] is false for a frame that was dropped.
     * [releaseNs] is the wall-clock time of the release, from [System.nanoTime]. [clockReady] is false while the
     * audio clock has no anchor, for example when a seek lands while paused. Drift is then unknown, not zero.
     */
    fun onFrameReleased(
        ptsUs: Micros,
        clockUs: Micros,
        rendered: Boolean,
        releaseNs: Long,
        clockReady: Boolean,
    ) {
        synchronized(lock) {
            frameIndex++
            if (rendered) {
                totalRendered++
                if (windowRendered == 0L) windowFirstPtsUs = ptsUs.value
                windowLastPtsUs = ptsUs.value
                windowRendered++
                if (masterIsAudio) lastDrift = if (clockReady) Micros(ptsUs.value - clockUs.value) else null
            } else {
                totalDropped++
            }
            csv?.row(csvRow(ptsUs, releaseNs, rendered))

            val start = windowStartNs ?: releaseNs.also { windowStartNs = it }
            if (releaseNs - start >= WINDOW_NS) {
                publish(snapshotLocked(releaseNs))
                windowStartNs = releaseNs
                windowRendered = 0L
                windowFirstPtsUs = 0L
                windowLastPtsUs = 0L
            }
        }
    }

    /** Records one GPU time for the present pass. Called on the GL thread. */
    fun onPresentGpu(duration: Nanos) {
        synchronized(lock) {
            gpuSamplesNs[gpuNext] = duration.value
            gpuNext = (gpuNext + 1) % GPU_SAMPLES
            if (gpuCount < GPU_SAMPLES) gpuCount++
        }
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

    private fun snapshotLocked(nowNs: Long): PlaybackStats {
        val elapsedSeconds = (nowNs - (windowStartNs ?: nowNs)).coerceAtLeast(1L) / NANOS_PER_SECOND
        val gpu = gpuSnapshotLocked()
        lastThermal = thermalHeadroom()
        return PlaybackStats(
            displayFps = (windowRendered / elapsedSeconds).toFloat(),
            contentFps = contentFps(windowRendered, windowFirstPtsUs, windowLastPtsUs),
            renderedFrames = totalRendered,
            droppedFrames = totalDropped,
            avDrift = lastDrift,
            presentGpuP50 = percentile(gpu, P50)?.let { Nanos(it) },
            presentGpuP95 = percentile(gpu, P95)?.let { Nanos(it) },
            refreshHz = refreshHz(),
            thermalHeadroom = lastThermal,
            logPath = logFile?.path,
        )
    }

    /** The valid GPU samples in ascending order. Copying here keeps the ring writable while the stats are computed. */
    private fun gpuSnapshotLocked(): LongArray {
        val copy = gpuSamplesNs.copyOf(gpuCount)
        copy.sort()
        return copy
    }

    private fun refreshHz(): Float {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        return display?.refreshRate ?: 0f
    }

    /** Null before Android 11, which is when the thermal API arrived. */
    private fun thermalHeadroom(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val power = context.getSystemService(PowerManager::class.java) ?: return null
        val headroom = power.getThermalHeadroom(THERMAL_FORECAST_SECONDS)
        return if (headroom.isNaN()) null else headroom
    }

    private companion object {
        const val P50 = 0.50
        const val P95 = 0.95
    }
}

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
