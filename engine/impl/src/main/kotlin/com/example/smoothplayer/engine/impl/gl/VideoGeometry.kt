package com.example.smoothplayer.engine.impl.gl

/** A rectangle in surface pixels, GL convention (origin at the bottom-left). */
internal data class Viewport(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Largest rectangle with the content's aspect ratio that fits inside the surface, centred.
 * The leftover area becomes letterbox or pillarbox bars, which the caller clears to black.
 */
internal fun fitViewport(surfaceWidth: Int, surfaceHeight: Int, contentWidth: Int, contentHeight: Int): Viewport {
    if (surfaceWidth <= 0 || surfaceHeight <= 0 || contentWidth <= 0 || contentHeight <= 0) {
        return Viewport(0, 0, surfaceWidth.coerceAtLeast(0), surfaceHeight.coerceAtLeast(0))
    }
    val scale = minOf(surfaceWidth.toFloat() / contentWidth, surfaceHeight.toFloat() / contentHeight)
    val width = (contentWidth * scale).toInt()
    val height = (contentHeight * scale).toInt()
    return Viewport(
        x = (surfaceWidth - width) / 2,
        y = (surfaceHeight - height) / 2,
        width = width,
        height = height,
    )
}

/**
 * Column-major 2x2 matrix, written into [out], that rotates clip-space positions clockwise by
 * [degreesClockwise]. The container's rotation metadata is clockwise, and clip space is y-up, so
 * the matrices below are the counterclockwise angles that match. Values other than 0/90/180/270
 * fall back to the identity.
 */
internal fun clockwiseRotation(degreesClockwise: Int, out: FloatArray = FloatArray(4)): FloatArray {
    val values = when (Math.floorMod(degreesClockwise, 360)) {
        90 -> floatArrayOf(0f, -1f, 1f, 0f)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f)
        270 -> floatArrayOf(0f, 1f, -1f, 0f)
        else -> floatArrayOf(1f, 0f, 0f, 1f)
    }
    values.copyInto(out)
    return out
}
