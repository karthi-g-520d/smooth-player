package com.example.smoothplayer.engine.impl.audio

import android.media.AudioTrack
import com.example.smoothplayer.engine.api.model.MICROS_PER_SECOND
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.impl.clock.MasterClock

private const val HEAD_MASK = 0xFFFF_FFFFL
private const val NANOS_PER_SECOND = 1_000_000_000L

// Longest stretch the clock will extrapolate past the last report. Past this, the device is probably stalled,
// and running ahead would be wrong.
private const val MAX_EXTRAPOLATION_NS = 200_000_000L

// Time constant of the offset average. Long enough to hide report bursts, short enough to follow real drift.
private const val OFFSET_SMOOTHING_NS = 400_000_000L
private const val NANOS_PER_MICRO = 1_000L

/**
 * Master clock read from the audio device. It moves only while [track] plays, so pausing the track
 * pauses the clock with no extra bookkeeping.
 *
 * [anchorAt], [finishAt] and [reset] run on the audio side. [nowUs] and [isStalled] run on the decoder
 * thread. Neither side takes a lock, because the track's position getter is thread-safe.
 */
internal class AudioClock(
    private val track: AudioTrack,
    private val sampleRate: Int,
) : MasterClock {
    @Volatile private var anchor: Anchor? = null
    @Volatile private var endFrames: Long? = null

    // The device reports its playback position in chunks. Between two reports the clock estimates the position
    // from elapsed wall time, so the release target moves smoothly rather than in steps.
    private var playing = false
    private var lastHead = 0L
    private var lastHeadNs = 0L

    // The offset between the audio position and wall time changes slowly, while the reports jitter by tens of
    // milliseconds. Averaging the offset removes the jitter, and the long-term rate is still the audio's own.
    private var offsetInitialized = false
    private var smoothedOffsetUs = 0L
    private var lastOffsetNs = 0L

    /** Records that the device is playing [ptsUs] now. Call once, when the first PCM chunk starts playing. */
    @Synchronized
    fun anchorAt(ptsUs: Micros) {
        val head = playedHead()
        anchor = Anchor(ptsUs.value, head)
        lastHead = head
        lastHeadNs = System.nanoTime()
        offsetInitialized = false
    }

    /** Tells the clock whether the track is playing. While it is not, the position is not extrapolated. */
    @Synchronized
    fun setPlaying(value: Boolean) {
        playing = value
        lastHeadNs = System.nanoTime()
        if (!value) offsetInitialized = false
    }

    /** Records how many frames were written since the anchor, so the clock can report that it has stalled at the end. */
    fun finishAt(framesSinceAnchor: Long) {
        endFrames = framesSinceAnchor
    }

    /** Forgets the anchor. Until the next [anchorAt], the clock reads zero and video waits. */
    @Synchronized
    fun reset() {
        anchor = null
        endFrames = null
        offsetInitialized = false
    }

    @Synchronized
    override fun nowUs(): Micros {
        val point = anchor ?: return Micros(0L)
        val nowNs = System.nanoTime()
        val head = playedHead()
        if (head != lastHead) {
            lastHead = head
            lastHeadNs = nowNs
        }
        var framesSinceAnchor = (head - point.headFrames) and HEAD_MASK
        if (playing) {
            val sinceReportNs = (nowNs - lastHeadNs).coerceIn(0L, MAX_EXTRAPOLATION_NS)
            framesSinceAnchor += sinceReportNs * sampleRate / NANOS_PER_SECOND
        }
        val rawUs = point.ptsUs + framesSinceAnchor * MICROS_PER_SECOND / sampleRate
        return Micros(smoothed(rawUs, nowNs))
    }

    /**
     * Averages the offset between [rawUs] and wall time. While the track is not playing the raw value is returned,
     * and the average restarts when playback resumes, because wall time would otherwise move the clock while paused.
     */
    private fun smoothed(rawUs: Long, nowNs: Long): Long {
        if (!playing) {
            offsetInitialized = false
            return rawUs
        }
        val wallUs = nowNs / NANOS_PER_MICRO
        val rawOffsetUs = rawUs - wallUs
        if (!offsetInitialized) {
            smoothedOffsetUs = rawOffsetUs
            offsetInitialized = true
        } else {
            val sinceLastNs = (nowNs - lastOffsetNs).coerceIn(0L, OFFSET_SMOOTHING_NS)
            smoothedOffsetUs += (rawOffsetUs - smoothedOffsetUs) * sinceLastNs / OFFSET_SMOOTHING_NS
        }
        lastOffsetNs = nowNs
        return wallUs + smoothedOffsetUs
    }

    override fun isReady(): Boolean = anchor != null

    override fun isStalled(): Boolean {
        val end = endFrames ?: return false
        val point = anchor
        val played = if (point == null) 0L else (playedHead() - point.headFrames) and HEAD_MASK
        return played >= end
    }

    private fun playedHead(): Long = track.playbackHeadPosition.toLong() and HEAD_MASK

    private class Anchor(val ptsUs: Long, val headFrames: Long)
}

/**
 * Media time, in microseconds, that is [head] frames past the anchor taken at [anchorHead].
 * The track's position is a 32-bit counter that wraps, so the difference is masked.
 */
internal fun audioPositionUs(anchorPtsUs: Long, anchorHead: Long, head: Long, sampleRate: Int): Long {
    val framesSinceAnchor = (head - anchorHead) and HEAD_MASK
    return anchorPtsUs + framesSinceAnchor * MICROS_PER_SECOND / sampleRate
}

/**
 * Frames at the start of a chunk that fall before [targetUs]. Equals [chunkFrames] when the whole chunk
 * is before the target, and zero when the chunk starts at or after it.
 */
internal fun framesBeforeTarget(chunkPtsUs: Long, chunkFrames: Long, targetUs: Long, sampleRate: Int): Long {
    if (chunkPtsUs >= targetUs) return 0L
    val skip = (targetUs - chunkPtsUs) * sampleRate / MICROS_PER_SECOND
    return minOf(skip, chunkFrames)
}
