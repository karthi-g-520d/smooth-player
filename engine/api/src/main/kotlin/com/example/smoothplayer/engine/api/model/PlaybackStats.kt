package com.example.smoothplayer.engine.api.model

/**
 * One second of playback measurements for the debug overlay. Null means the device cannot measure the value.
 *
 * @property avDrift video PTS minus the audio clock for the last frame shown. Null without an audio track.
 * @property logPath the CSV file being written, or null when logging is off.
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
)
