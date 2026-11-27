package com.example.smoothplayer.engine.api.model

/**
 * How the in-between refreshes of [InterpolationMode.MotionCompensated] were drawn since the file opened, one count per
 * refresh. Each refresh falls in exactly one count.
 */
public data class InBetweenCounts(
    /** Drawn with the warp. */
    public val warped: Long = 0L,
    /** Blended: the pair's motion search was still waiting for the next swap, so it had no field yet. */
    public val searchPending: Long = 0L,
    /**
     * Blended: the pair was never searched, for example because two frames arrived between swaps and only the newer
     * pair was searched, or the gap between the frames was too long.
     */
    public val notSearched: Long = 0L,
    /** Blended by the low-confidence fallback. */
    public val lowConfidence: Long = 0L,
    /** Held on the earlier frame by the heat or GPU-budget fallback. */
    public val held: Long = 0L,
) {
    public val total: Long get() = warped + searchPending + notSearched + lowConfidence + held
}
