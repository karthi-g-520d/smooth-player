package com.example.smoothplayer.engine.impl.clock

import com.example.smoothplayer.engine.api.model.Micros

/**
 * The time base that video frames are paced against. Read on the decoder thread, so implementations
 * must be safe to read from another thread than the one that controls them.
 */
internal interface MasterClock {
    /** Current media time, in the same PTS timeline as the video frames. */
    fun nowUs(): Micros

    /** True once the clock can no longer advance by itself. Video stops waiting on it, so it can finish after audio. */
    fun isStalled(): Boolean
}
