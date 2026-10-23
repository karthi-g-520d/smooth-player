package com.example.smoothplayer.engine.impl.gl

/** Shorter side of the fine motion luma once the cap applies: the 1/4 luma of a 720p frame. */
internal const val MOTION_LUMA_MAX_SHORT_SIDE = 180
private const val MIN_MOTION_LUMA_DIVISOR = 4
private const val TEXELS_PER_TAP = 2

/**
 * How many source pixels, along each axis, make one fine motion-luma pixel for a [width] by [height] frame.
 *
 * Motion search costs grow with the luma size, so the fine level is capped at the 1/4 luma of a 720p frame
 * (320x180): 720p and smaller use 1/4, 1080p uses 1/6 and 4K 1/12. The divisor is always even, because the luma pass
 * averages pairs of source texels with one bilinear tap. Motion vectors come out in fine motion-luma pixels, so a
 * vector times this divisor is the motion in source pixels.
 */
internal fun motionLumaDivisor(width: Int, height: Int): Int {
    require(width > 0 && height > 0) { "frame size must be positive: ${width}x$height" }
    val fitted = maxOf(MIN_MOTION_LUMA_DIVISOR, ceilDiv(minOf(width, height), MOTION_LUMA_MAX_SHORT_SIDE))
    return ceilDiv(fitted, TEXELS_PER_TAP) * TEXELS_PER_TAP
}

/** Bilinear taps along each axis that the luma pass needs to average a [divisor] by [divisor] block exactly. */
internal fun motionLumaTaps(divisor: Int): Int = divisor / TEXELS_PER_TAP

internal fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
