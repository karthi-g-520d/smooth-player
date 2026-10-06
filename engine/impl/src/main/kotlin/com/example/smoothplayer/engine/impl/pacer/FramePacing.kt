package com.example.smoothplayer.engine.impl.pacer

import com.example.smoothplayer.engine.api.model.Micros

private const val LATE_DROP_US = 40_000L
private const val MAX_WAIT_US = 8_000L

/** What to do with a decoded frame, given the master clock. */
internal sealed interface FrameDecision {
    /** Show the frame now. */
    data object Render : FrameDecision

    /** The frame is too late to be worth showing. */
    data object Drop : FrameDecision

    /** The frame is early. Hold it and check again after [delayUs]. */
    data class Wait(val delayUs: Long) : FrameDecision
}

/**
 * The simple A/V sync rule from the plan:
 * - show a frame whose PTS is at or before the clock,
 * - drop a frame that is more than [LATE_DROP_US] behind,
 * - otherwise wait, checking again at most every [MAX_WAIT_US].
 *
 * A stalled clock never holds a frame back, so video can finish after audio does.
 */
internal fun decideFrame(framePts: Micros, clock: Micros, stalled: Boolean): FrameDecision {
    if (stalled) return FrameDecision.Render
    val lateUs = clock.value - framePts.value
    return when {
        lateUs > LATE_DROP_US -> FrameDecision.Drop
        lateUs >= 0 -> FrameDecision.Render
        else -> FrameDecision.Wait(minOf(-lateUs, MAX_WAIT_US))
    }
}

/**
 * While blending, the frame after the last one released goes to the surface as soon as that last frame is due.
 * The GL thread then holds both ends of the next blend before the screen needs them. Duplicate mode never prefetches.
 */
internal fun shouldPrefetchNext(blending: Boolean, lastReleased: Micros?, clock: Micros): Boolean =
    blending && lastReleased != null && lastReleased <= clock
