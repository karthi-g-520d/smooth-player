package com.example.smoothplayer.engine.impl.gl

/** Marks a ring slot that holds no frame yet. */
internal const val EMPTY_SLOT_PTS_US = Long.MIN_VALUE

/**
 * Two neighbouring frames of the ring and the phase between them. When [b] equals [a], only one frame is used and [t]
 * is zero, so the blend shader shows [a] unchanged.
 */
internal data class BlendPick(val a: Int, val b: Int, val t: Float)

/**
 * Two frames further apart than this are not blended. A seek can leave a stale frame in the ring, and blending
 * across it would flash.
 */
internal const val MAX_BLEND_GAP_US = 200_000L

/**
 * Picks the frame at or before [contentUs] as A, the frame after it as B, and t = (contentUs - ptsA) / (ptsB - ptsA).
 *
 * Returns null when no resident frame is at or before [contentUs]. The caller then shows the newest frame instead.
 * A missing B, or a B more than [maxGapUs] after A, gives t = 0, so the picture holds on A rather than blending into
 * an unrelated frame.
 */
internal fun pickBlendPair(ptsUs: LongArray, contentUs: Long, maxGapUs: Long): BlendPick? {
    var a = -1
    for (slot in ptsUs.indices) {
        val pts = ptsUs[slot]
        if (pts == EMPTY_SLOT_PTS_US || pts > contentUs) continue
        if (a < 0 || pts > ptsUs[a]) a = slot
    }
    if (a < 0) return null

    var b = -1
    for (slot in ptsUs.indices) {
        val pts = ptsUs[slot]
        if (pts == EMPTY_SLOT_PTS_US || pts <= ptsUs[a]) continue
        if (b < 0 || pts < ptsUs[b]) b = slot
    }
    if (b < 0) return BlendPick(a, a, 0f)

    val gapUs = ptsUs[b] - ptsUs[a]
    if (gapUs > maxGapUs) return BlendPick(a, a, 0f)
    val t = ((contentUs - ptsUs[a]).toDouble() / gapUs).toFloat().coerceIn(0f, 1f)
    return BlendPick(a, b, t)
}
