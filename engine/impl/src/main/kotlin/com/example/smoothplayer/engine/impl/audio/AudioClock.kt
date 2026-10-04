package com.example.smoothplayer.engine.impl.audio

import android.media.AudioTrack
import com.example.smoothplayer.engine.api.model.MICROS_PER_SECOND
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.impl.clock.MasterClock

private const val HEAD_MASK = 0xFFFF_FFFFL

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

    /** Records that the device is playing [ptsUs] now. Call once, when the first PCM chunk starts playing. */
    fun anchorAt(ptsUs: Micros) {
        anchor = Anchor(ptsUs.value, playedHead())
    }

    /** Records how many frames were written since the anchor, so the clock can report that it has stalled at the end. */
    fun finishAt(framesSinceAnchor: Long) {
        endFrames = framesSinceAnchor
    }

    /** Forgets the anchor. Until the next [anchorAt], the clock reads zero and video waits. */
    fun reset() {
        anchor = null
        endFrames = null
    }

    override fun nowUs(): Micros {
        val point = anchor ?: return Micros(0L)
        return Micros(audioPositionUs(point.ptsUs, point.headFrames, playedHead(), sampleRate))
    }

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
