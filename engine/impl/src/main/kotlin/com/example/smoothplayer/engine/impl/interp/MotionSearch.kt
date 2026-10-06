package com.example.smoothplayer.engine.impl.interp

import kotlin.math.abs
import kotlin.math.min

private const val NEIGHBOURHOOD = 9
private const val HALF_PIXEL = 0.5f

/** An 8-bit luma plane, row by row. Reads outside the plane clamp to the nearest edge pixel. */
internal class LumaPlane(val width: Int, val height: Int, private val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "plane has ${pixels.size} pixels, expected ${width * height}" }
    }

    fun at(x: Int, y: Int): Int = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
}

/**
 * Per-block motion. The integer vector (dx, dy) plus the subpixel offset (subX, subY), in plane pixels, means the
 * block's content in the next frame came from there in the previous frame: next(p) ≈ prev(p - v).
 *
 * [residual] is the best block's SAD divided by its detail, so a low value is a confident match. A flat block has
 * little detail, so a small SAD there is not treated as confident.
 */
internal class MotionField(val columns: Int, val rows: Int) {
    val dx = IntArray(columns * rows)
    val dy = IntArray(columns * rows)
    val subX = FloatArray(columns * rows)
    val subY = FloatArray(columns * rows)
    val residual = FloatArray(columns * rows)
}

/**
 * [smoothnessWeight] is the SAD added per pixel of distance between a candidate vector and the median of its causal
 * neighbours. It pushes the field towards coherent motion instead of per-block noise.
 */
internal data class MotionSearchConfig(
    val blockSize: Int = 8,
    val searchRange: Int = 8,
    val smoothnessWeight: Int = 4,
)

/**
 * Full-search block matching on luma, in raster order so each block sees its left, upper and upper-right neighbours.
 * Each block minimises SAD plus the smoothness penalty. A parabola through the SAD at the best vector and its two
 * neighbours gives the subpixel offset. The integer and subpixel parts go through a 3x3 median filter.
 *
 * This is the CPU reference for the GPU compute pass. It defines the result the GPU must match.
 */
internal fun estimateMotion(
    prev: LumaPlane,
    next: LumaPlane,
    config: MotionSearchConfig = MotionSearchConfig(),
): MotionField {
    require(prev.width == next.width && prev.height == next.height) { "frames differ in size" }
    val columns = (next.width + config.blockSize - 1) / config.blockSize
    val rows = (next.height + config.blockSize - 1) / config.blockSize
    val field = MotionField(columns, rows)
    val range = config.searchRange

    for (by in 0 until rows) {
        for (bx in 0 until columns) {
            val predictorX = predictedDx(field, bx, by)
            val predictorY = predictedDy(field, bx, by)
            var bestCost = Long.MAX_VALUE
            var bestDx = 0
            var bestDy = 0
            for (vy in -range..range) {
                for (vx in -range..range) {
                    val penalty = config.smoothnessWeight * (abs(vx - predictorX) + abs(vy - predictorY))
                    val cost = blockSad(prev, next, bx, by, config.blockSize, vx, vy).toLong() + penalty
                    if (cost < bestCost) {
                        bestCost = cost
                        bestDx = vx
                        bestDy = vy
                    }
                }
            }
            val index = by * columns + bx
            field.dx[index] = bestDx
            field.dy[index] = bestDy
            field.subX[index] = subpixelOffset(
                blockSad(prev, next, bx, by, config.blockSize, bestDx - 1, bestDy),
                blockSad(prev, next, bx, by, config.blockSize, bestDx, bestDy),
                blockSad(prev, next, bx, by, config.blockSize, bestDx + 1, bestDy),
            )
            field.subY[index] = subpixelOffset(
                blockSad(prev, next, bx, by, config.blockSize, bestDx, bestDy - 1),
                blockSad(prev, next, bx, by, config.blockSize, bestDx, bestDy),
                blockSad(prev, next, bx, by, config.blockSize, bestDx, bestDy + 1),
            )
            val bestSad = blockSad(prev, next, bx, by, config.blockSize, bestDx, bestDy)
            field.residual[index] = bestSad / (blockDetail(next, bx, by, config.blockSize) + 1f)
        }
    }
    return medianFiltered(field)
}

private fun blockSad(prev: LumaPlane, next: LumaPlane, bx: Int, by: Int, size: Int, vx: Int, vy: Int): Int {
    val x0 = bx * size
    val y0 = by * size
    var sum = 0
    for (y in y0 until min(y0 + size, next.height)) {
        for (x in x0 until min(x0 + size, next.width)) {
            sum += abs(next.at(x, y) - prev.at(x - vx, y - vy))
        }
    }
    return sum
}

/** Sum of absolute deviations from the block mean. Zero for a flat block. */
private fun blockDetail(next: LumaPlane, bx: Int, by: Int, size: Int): Float {
    val x0 = bx * size
    val y0 = by * size
    val xEnd = min(x0 + size, next.width)
    val yEnd = min(y0 + size, next.height)
    var total = 0
    var count = 0
    for (y in y0 until yEnd) {
        for (x in x0 until xEnd) {
            total += next.at(x, y)
            count++
        }
    }
    val mean = total.toFloat() / count
    var detail = 0f
    for (y in y0 until yEnd) {
        for (x in x0 until xEnd) {
            detail += abs(next.at(x, y) - mean)
        }
    }
    return detail
}

/**
 * The offset of the parabola's minimum from the centre sample, clamped to half a pixel. Zero when the three samples do
 * not form a minimum, so a flat or ambiguous match is not moved. Zero also when the centre is an exact match: SAD is
 * V-shaped rather than parabolic, so a parabola would move an exact whole-pixel match by up to half a pixel.
 */
internal fun subpixelOffset(sadBefore: Int, sadCentre: Int, sadAfter: Int): Float {
    if (sadCentre == 0) return 0f
    val curvature = sadBefore - 2 * sadCentre + sadAfter
    if (curvature <= 0) return 0f
    val offset = HALF_PIXEL * (sadBefore - sadAfter).toFloat() / curvature
    return offset.coerceIn(-HALF_PIXEL, HALF_PIXEL)
}

/** Median of the causal neighbours (left, up, up-right) that exist. Zero for the first block. */
private fun predictedDx(field: MotionField, bx: Int, by: Int): Int =
    median(causalNeighbours(field, bx, by) { index -> field.dx[index] })

private fun predictedDy(field: MotionField, bx: Int, by: Int): Int =
    median(causalNeighbours(field, bx, by) { index -> field.dy[index] })

private inline fun causalNeighbours(field: MotionField, bx: Int, by: Int, component: (Int) -> Int): IntArray {
    val values = ArrayList<Int>(3)
    if (bx > 0) values.add(component(by * field.columns + bx - 1))
    if (by > 0) values.add(component((by - 1) * field.columns + bx))
    if (by > 0 && bx + 1 < field.columns) values.add(component((by - 1) * field.columns + bx + 1))
    return values.toIntArray()
}

private fun median(values: IntArray): Int {
    if (values.isEmpty()) return 0
    values.sort()
    return values[values.size / 2]
}

/**
 * Replaces each vector with the component-wise median of its 3x3 neighbourhood, including the subpixel offsets.
 * Edges repeat the nearest block. The residual is kept per block, not filtered.
 */
internal fun medianFiltered(field: MotionField): MotionField {
    val out = MotionField(field.columns, field.rows)
    val xs = IntArray(NEIGHBOURHOOD)
    val ys = IntArray(NEIGHBOURHOOD)
    val subXs = FloatArray(NEIGHBOURHOOD)
    val subYs = FloatArray(NEIGHBOURHOOD)
    for (by in 0 until field.rows) {
        for (bx in 0 until field.columns) {
            var n = 0
            for (oy in -1..1) {
                for (ox in -1..1) {
                    val x = (bx + ox).coerceIn(0, field.columns - 1)
                    val y = (by + oy).coerceIn(0, field.rows - 1)
                    val source = y * field.columns + x
                    xs[n] = field.dx[source]
                    ys[n] = field.dy[source]
                    subXs[n] = field.subX[source]
                    subYs[n] = field.subY[source]
                    n++
                }
            }
            xs.sort()
            ys.sort()
            subXs.sort()
            subYs.sort()
            val index = by * field.columns + bx
            out.dx[index] = xs[NEIGHBOURHOOD / 2]
            out.dy[index] = ys[NEIGHBOURHOOD / 2]
            out.subX[index] = subXs[NEIGHBOURHOOD / 2]
            out.subY[index] = subYs[NEIGHBOURHOOD / 2]
            out.residual[index] = field.residual[index]
        }
    }
    return out
}
