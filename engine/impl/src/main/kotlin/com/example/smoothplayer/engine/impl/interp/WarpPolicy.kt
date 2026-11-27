package com.example.smoothplayer.engine.impl.interp

import com.example.smoothplayer.engine.api.model.WarpFallback
import kotlin.math.abs

/**
 * A block counts as low-confidence when its residual (best SAD over the block's own detail, see [MotionField]) is above
 * this. The motion stats pass uses the same value, so the two cannot drift.
 */
internal const val LOW_CONFIDENCE_RESIDUAL = 0.6f

/**
 * A block whose pixels deviate from their mean by less than this, on average, in luma levels, is flat: dark sky, a
 * wall. It has nothing to match, so its residual says nothing about the pair, and the stats leave it out.
 */
internal const val FLAT_DEVIATION = 2.0f

/** Luma histogram bins used for scene-cut detection. 256 levels divide evenly into them. */
internal const val HISTOGRAM_BINS = 16

/** The stats pass reads every [HISTOGRAM_STEP]th luma pixel along each axis. At 320x180 that is 160x90 pixels. */
internal const val HISTOGRAM_STEP = 2

private const val LUMA_LEVELS = 256

/**
 * Whole-pair measurements from the stats pass, used to decide how a pair is shown.
 *
 * @property lowConfidenceFraction share of textured blocks (see [FLAT_DEVIATION]) with a residual above
 * [LOW_CONFIDENCE_RESIDUAL], 0 to 1. Zero when no block is textured.
 * @property histogramDistance L1 distance between the two frames' luma histograms, each normalised to sum to 1, so 0
 * (same distribution) to 2 (no overlap).
 */
internal data class PairStats(val lowConfidenceFraction: Float, val histogramDistance: Float) {
    companion object {
        /**
         * From the raw counts the GPU writes. Zero textured blocks or pixels give zero, so an empty or all-flat pass
         * never reads as a cut.
         */
        fun fromCounts(texturedBlocks: Int, lowConfidenceBlocks: Int, pixels: Int, histogramDiff: Int): PairStats =
            PairStats(
                lowConfidenceFraction = if (texturedBlocks > 0) lowConfidenceBlocks.toFloat() / texturedBlocks else 0f,
                histogramDistance = if (pixels > 0) histogramDiff.toFloat() / pixels else 0f,
            )
    }
}

/**
 * Thresholds for the pair decisions. The values are first guesses from the synthetic pans and a synthetic cut (see
 * WarpPolicyTest); the plan asks for calibration on real scene-cut clips.
 */
internal data class WarpPolicyConfig(
    /**
     * A cut usually changes both the histogram and the matches; a pan changes neither, much. The match share was 0.5
     * at first, but a racing clip with no cuts had a pair at 0.61 unmatched and 0.81 tone change (fast motion into a
     * lighting change, phone run of 2026-11-27); a real cut leaves almost nothing matched.
     */
    val cutHistogramDistance: Float = 0.5f,
    val cutLowConfidence: Float = 0.7f,
    /** A histogram change this large is a cut on its own, even where the search found something to match. */
    val cutHistogramAlone: Float = 1.0f,
    /**
     * Almost no textured block matching, with at least some change in tones, is a cut too. The histogram floor keeps
     * fast motion beyond the search's reach (same scene, same tones) from counting: the first phone run on a racing
     * clip with no cuts found 4. A cut between two shots of the same tones then falls to the per-pixel confidence.
     */
    val cutLowConfidenceAlone: Float = 0.8f,
    val cutLowConfidenceAloneHistogram: Float = 0.25f,
    /** The plan's "over ~40% low-confidence blocks: blend". */
    val lowConfidenceEnter: Float = 0.40f,
    val lowConfidenceExit: Float = 0.30f,
    val lowConfidenceEnterPairs: Int = 2,
    val lowConfidenceExitPairs: Int = 8,
    /**
     * Thermal headroom at which the warp gives way to held frames, and below which it comes back. The plan said 0.9,
     * but the vivo V2334 sits at 0.95 even in plain Duplicate playback, so 0.9 would never let the warp run there. 1.0
     * is where Android forecasts severe throttling.
     */
    val thermalEnter: Float = 1.0f,
    val thermalExit: Float = 0.95f,
    /**
     * Present GPU time, smoothed, above which the warp is over budget. The plan's budget is 10 ms per presented frame
     * for the whole pipeline, and motion search takes about 4 ms of it on the vivo.
     */
    val gpuBudgetNs: Long = 8_000_000L,
    /** How long a heat or GPU fallback holds before the warp is tried again. */
    val holdNs: Long = 5_000_000_000L,
)

/** True when the pair straddles a scene cut, so it should not be interpolated at all. */
internal fun isSceneCut(stats: PairStats, config: WarpPolicyConfig = WarpPolicyConfig()): Boolean =
    stats.histogramDistance >= config.cutHistogramAlone ||
        (stats.lowConfidenceFraction >= config.cutLowConfidenceAlone &&
            stats.histogramDistance >= config.cutLowConfidenceAloneHistogram) ||
        (stats.histogramDistance >= config.cutHistogramDistance && stats.lowConfidenceFraction >= config.cutLowConfidence)

/**
 * The CPU reference of the motion stats pass (motion_stats.comp): the low-confidence share of [field]'s textured
 * blocks, and the histogram distance between [prev] and [next] sampled every [HISTOGRAM_STEP] pixels.
 */
internal fun pairStats(prev: LumaPlane, next: LumaPlane, field: MotionField): PairStats {
    var textured = 0
    var lowConfidence = 0
    for (index in field.residual.indices) {
        if (field.deviation[index] < FLAT_DEVIATION) continue
        textured++
        if (field.residual[index] > LOW_CONFIDENCE_RESIDUAL) lowConfidence++
    }
    val histPrev = IntArray(HISTOGRAM_BINS)
    val histNext = IntArray(HISTOGRAM_BINS)
    var pixels = 0
    for (y in 0 until next.height step HISTOGRAM_STEP) {
        for (x in 0 until next.width step HISTOGRAM_STEP) {
            histPrev[prev.at(x, y) * HISTOGRAM_BINS / LUMA_LEVELS]++
            histNext[next.at(x, y) * HISTOGRAM_BINS / LUMA_LEVELS]++
            pixels++
        }
    }
    var diff = 0
    for (bin in 0 until HISTOGRAM_BINS) diff += abs(histPrev[bin] - histNext[bin])
    return PairStats.fromCounts(textured, lowConfidence, pixels, diff)
}

/**
 * Decides, with hysteresis, whether the motion-compensated warp is used at all, or which fallback replaces it. Each
 * trigger keeps its own state, so one recovering does not hide another, and the strongest active one is reported:
 * heat, then GPU budget (both hold the nearer frame, as Duplicate does), then low confidence (blend).
 *
 * Plain state, no Android imports: the GL thread owns it, and JVM tests drive it with made-up times.
 */
internal class WarpGovernor(private val config: WarpPolicyConfig = WarpPolicyConfig()) {
    private var lowConfidence = false
    private var lowRun = 0
    private var highRun = 0
    private var thermal = false
    private var thermalSinceNs = 0L
    private var overBudget = false
    private var overBudgetSinceNs = 0L
    private var smoothedPresentNs = 0L

    /** The strongest active fallback, or [WarpFallback.None] when the warp is in use. */
    val fallback: WarpFallback
        get() = when {
            thermal -> WarpFallback.Thermal
            overBudget -> WarpFallback.GpuBudget
            lowConfidence -> WarpFallback.LowConfidence
            else -> WarpFallback.None
        }

    /** True while a fallback holds the nearer frame, so motion search can stop to save the GPU. */
    val holdsFrames: Boolean get() = thermal || overBudget

    /** Takes one new pair's stats, once per pair. Scene cuts are left out by the caller: they are not a trend. */
    fun onPair(stats: PairStats) {
        val fraction = stats.lowConfidenceFraction
        if (fraction > config.lowConfidenceEnter) highRun++ else highRun = 0
        if (fraction < config.lowConfidenceExit) lowRun++ else lowRun = 0
        if (!lowConfidence && highRun >= config.lowConfidenceEnterPairs) {
            lowConfidence = true
            lowRun = 0
        } else if (lowConfidence && lowRun >= config.lowConfidenceExitPairs) {
            lowConfidence = false
            highRun = 0
        }
    }

    /** Takes the latest thermal headroom. Null (no thermal API) never triggers. */
    fun onThermal(headroom: Float?, nowNs: Long) {
        if (headroom == null) return
        if (!thermal && headroom >= config.thermalEnter) {
            thermal = true
            thermalSinceNs = nowNs
        } else if (thermal && headroom < config.thermalExit && nowNs - thermalSinceNs >= config.holdNs) {
            thermal = false
        }
    }

    /**
     * Takes one present pass's GPU time. While over budget the present is the cheap fallback, so its time says nothing
     * about the warp: the fallback holds for [WarpPolicyConfig.holdNs], then the warp is measured again from scratch.
     */
    fun onPresentGpu(durationNs: Long, nowNs: Long) {
        if (overBudget) {
            if (nowNs - overBudgetSinceNs < config.holdNs) return
            overBudget = false
            smoothedPresentNs = 0L
        }
        smoothedPresentNs = if (smoothedPresentNs == 0L) {
            durationNs
        } else {
            smoothedPresentNs + (durationNs - smoothedPresentNs) / PRESENT_SMOOTHING
        }
        if (smoothedPresentNs > config.gpuBudgetNs) {
            overBudget = true
            overBudgetSinceNs = nowNs
        }
    }

    /** Forgets everything, for a new file or a seek. */
    fun reset() {
        lowConfidence = false
        lowRun = 0
        highRun = 0
        thermal = false
        overBudget = false
        smoothedPresentNs = 0L
    }

    private companion object {
        /** Each new present time moves the smoothed value 1/16 of the way, about a quarter second at 60 Hz. */
        const val PRESENT_SMOOTHING = 16
    }
}
