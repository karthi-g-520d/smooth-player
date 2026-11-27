package com.example.smoothplayer.engine.api.model

/**
 * One second of playback measurements for the debug overlay. Null means the device cannot measure the value.
 *
 * @property avDrift video PTS minus the audio clock for the last frame shown. Null without an audio track.
 * @property logPath the CSV file being written, or null when logging is off.
 * @property benchPath the benchmark CSV being written, or null when no benchmark run is on.
 */
public data class PlaybackStats(
    val displayFps: Float = 0f,
    val contentFps: Float = 0f,
    val renderedFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val avDrift: Micros? = null,
    val presentGpuP50: Nanos? = null,
    val presentGpuP95: Nanos? = null,
    /** GPU time of motion estimation for one frame pair, run once per decoded frame. */
    val motionGpuP50: Nanos? = null,
    val motionGpuP95: Nanos? = null,
    val refreshHz: Float = 0f,
    val thermalHeadroom: Float? = null,
    /** Percent of content frames, over the last second, that stayed on screen for exactly the expected number of refreshes. */
    val cadenceExactPercent: Float? = null,
    /** The same percentage, over the whole playback so far. */
    val cadenceAverageExactPercent: Float? = null,
    val logPath: String? = null,
    /** The warp's whole-picture fallback right now. Null outside [InterpolationMode.MotionCompensated]. */
    val warpFallback: WarpFallback? = null,
    /** Percent of in-between refreshes, over the last second, that were drawn with the warp rather than a fallback. */
    val warpPercent: Float? = null,
    /** Why each in-between refresh was warped or not, since the file opened. Null outside MotionCompensated. */
    val inBetweens: InBetweenCounts? = null,
    /** Frame pairs since the file opened that were held rather than interpolated, because they span a scene cut. */
    val sceneCuts: Long = 0L,
    /** The last scene cut's share of unmatched textured blocks, 0 to 1. Null before the first cut. */
    val lastCutLowConfidence: Float? = null,
    /** The last scene cut's luma histogram distance, 0 to 2. Null before the first cut. */
    val lastCutHistogram: Float? = null,
    /** The GL thread's time to issue one present draw, swap excluded, p95 over the recent draws. */
    val drawCpuP95: Nanos? = null,
    /** The longest poll of a pair's stats over the last second. Null when no stats were polled. */
    val statsPollMax: Nanos? = null,
    val benchPath: String? = null,
    /** True while a benchmark run is timing the stages, which waits for the GPU and so costs cadence. */
    val stageProbeOn: Boolean = false,
    /** Wall time, to glFinish, of the copy and motion-luma passes for one arriving frame. p95 of recent samples. */
    val stageCopyP95: Nanos? = null,
    /** Wall time, to glFinish, of the motion search and stats passes for one pair. p95 of recent samples. */
    val stageMotionP95: Nanos? = null,
    /** Wall time, to glFinish, of one warped in-between draw. p95 of recent samples. */
    val stageWarpP95: Nanos? = null,
    /** Battery charge, 0 to 100. Null when unknown. */
    val batteryPercent: Int? = null,
    /** Battery current in mA as the device reports it (the sign convention varies by device). Null when unknown. */
    val batteryCurrentMa: Int? = null,
    /** Battery temperature in °C. Null when unknown. */
    val batteryTempC: Float? = null,
    /** PowerManager thermal status, 0 (none) to 6 (shutdown). Null before Android 10. */
    val thermalStatus: Int? = null,
)
