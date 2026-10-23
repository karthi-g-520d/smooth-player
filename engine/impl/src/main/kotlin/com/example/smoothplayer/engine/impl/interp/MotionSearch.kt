package com.example.smoothplayer.engine.impl.interp

import kotlin.math.abs
import kotlin.math.floor
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
 * The search runs coarse to fine. At 1/8 resolution, each block (half [blockSize] on a side) searches ±[coarseRange]
 * with [coarseWeight] SAD added per pixel of distance from zero, so flat or ambiguous blocks stay still. At 1/4
 * resolution, each block searches ±[refineRange] around a predictor: the median of the 3x3 coarse neighbourhood,
 * doubled. [smoothnessWeight] is the SAD added per pixel of distance from that predictor. It pushes the field towards
 * coherent motion instead of per-block noise.
 *
 * No block depends on another block's result at the same level, so the GPU can run every block at once.
 */
internal data class MotionSearchConfig(
    val blockSize: Int = 8,
    val coarseRange: Int = 4,
    val refineRange: Int = 2,
    val coarseWeight: Int = 2,
    val smoothnessWeight: Int = 4,
) {
    init {
        require(blockSize % 2 == 0) { "blockSize must be even, so a coarse block is half of it" }
    }

    val coarseBlockSize: Int get() = blockSize / 2

    /** The largest vector the search can return at 1/4 resolution, per axis. */
    val maxVector: Int get() = 2 * coarseRange + refineRange
}

/** Halves [plane] by averaging each 2x2 group, rounding down. The odd last row or column averages with its edge copy. */
internal fun downsampled(plane: LumaPlane): LumaPlane {
    val width = (plane.width + 1) / 2
    val height = (plane.height + 1) / 2
    val pixels = IntArray(width * height) { i ->
        val x = 2 * (i % width)
        val y = 2 * (i / width)
        (plane.at(x, y) + plane.at(x + 1, y) + plane.at(x, y + 1) + plane.at(x + 1, y + 1)) / 4
    }
    return LumaPlane(width, height, pixels)
}

/** [estimateMotion] with coarse planes made by [downsampled]. The renderer passes its own 1/8 luma instead. */
internal fun estimateMotion(
    prev: LumaPlane,
    next: LumaPlane,
    config: MotionSearchConfig = MotionSearchConfig(),
): MotionField = estimateMotion(prev, next, downsampled(prev), downsampled(next), config)

/**
 * Block matching on luma, coarse to fine (see [MotionSearchConfig]). [prevCoarse] and [nextCoarse] are the frames at
 * half the size of [prev] and [next]. Each block minimises SAD plus its penalty, with ties going to the first candidate
 * in raster order. A parabola through the SAD at the best vector and its two neighbours gives the subpixel offset. The
 * integer and subpixel parts go through a 3x3 median filter.
 *
 * This is the CPU reference for the GPU compute passes. It defines the result the GPU must match.
 */
internal fun estimateMotion(
    prev: LumaPlane,
    next: LumaPlane,
    prevCoarse: LumaPlane,
    nextCoarse: LumaPlane,
    config: MotionSearchConfig = MotionSearchConfig(),
): MotionField {
    require(prev.width == next.width && prev.height == next.height) { "frames differ in size" }
    require(prevCoarse.width == (prev.width + 1) / 2 && prevCoarse.height == (prev.height + 1) / 2) {
        "coarse frames must be half the size, rounded up"
    }
    require(nextCoarse.width == prevCoarse.width && nextCoarse.height == prevCoarse.height) { "coarse frames differ" }
    val columns = (next.width + config.blockSize - 1) / config.blockSize
    val rows = (next.height + config.blockSize - 1) / config.blockSize
    val coarse = coarseField(prevCoarse, nextCoarse, columns, rows, config)
    val field = MotionField(columns, rows)
    val range = config.refineRange

    for (by in 0 until rows) {
        for (bx in 0 until columns) {
            val index = by * columns + bx
            val predictorX = 2 * neighbourhoodMedian(coarse.dx, columns, rows, bx, by)
            val predictorY = 2 * neighbourhoodMedian(coarse.dy, columns, rows, bx, by)
            var bestCost = Long.MAX_VALUE
            var bestDx = 0
            var bestDy = 0
            for (oy in -range..range) {
                for (ox in -range..range) {
                    val vx = predictorX + ox
                    val vy = predictorY + oy
                    val penalty = config.smoothnessWeight * (abs(ox) + abs(oy))
                    val cost = blockSad(prev, next, bx, by, config.blockSize, vx, vy).toLong() + penalty
                    if (cost < bestCost) {
                        bestCost = cost
                        bestDx = vx
                        bestDy = vy
                    }
                }
            }
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

/**
 * Integer vectors at the coarse level, on the same block grid as the fine level. Only dx and dy are filled.
 * Each block searches ±coarseRange with a penalty for distance from zero.
 */
internal fun coarseField(
    prev: LumaPlane,
    next: LumaPlane,
    columns: Int,
    rows: Int,
    config: MotionSearchConfig,
): MotionField {
    val field = MotionField(columns, rows)
    val size = config.coarseBlockSize
    val range = config.coarseRange
    for (by in 0 until rows) {
        for (bx in 0 until columns) {
            var bestCost = Long.MAX_VALUE
            var bestDx = 0
            var bestDy = 0
            for (vy in -range..range) {
                for (vx in -range..range) {
                    val penalty = config.coarseWeight * (abs(vx) + abs(vy))
                    val cost = blockSad(prev, next, bx, by, size, vx, vy).toLong() + penalty
                    if (cost < bestCost) {
                        bestCost = cost
                        bestDx = vx
                        bestDy = vy
                    }
                }
            }
            field.dx[by * columns + bx] = bestDx
            field.dy[by * columns + bx] = bestDy
        }
    }
    return field
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

/** Median of [values] over the 3x3 neighbourhood of block (bx, by). Edges repeat the nearest block. */
private fun neighbourhoodMedian(values: IntArray, columns: Int, rows: Int, bx: Int, by: Int): Int {
    val window = IntArray(NEIGHBOURHOOD)
    var n = 0
    for (oy in -1..1) {
        for (ox in -1..1) {
            val x = (bx + ox).coerceIn(0, columns - 1)
            val y = (by + oy).coerceIn(0, rows - 1)
            window[n++] = values[y * columns + x]
        }
    }
    window.sort()
    return window[NEIGHBOURHOOD / 2]
}

/**
 * Replaces each vector with the component-wise median of its 3x3 neighbourhood. The median is taken over total vectors
 * (integer plus subpixel), so a block never mixes one neighbour's integer part with another's subpixel part. The result
 * is split back into the nearest integer and a subpixel offset in [-0.5, 0.5]. Edges repeat the nearest block. The
 * residual is kept per block, not filtered.
 */
internal fun medianFiltered(field: MotionField): MotionField {
    val out = MotionField(field.columns, field.rows)
    val xs = FloatArray(NEIGHBOURHOOD)
    val ys = FloatArray(NEIGHBOURHOOD)
    for (by in 0 until field.rows) {
        for (bx in 0 until field.columns) {
            var n = 0
            for (oy in -1..1) {
                for (ox in -1..1) {
                    val x = (bx + ox).coerceIn(0, field.columns - 1)
                    val y = (by + oy).coerceIn(0, field.rows - 1)
                    val source = y * field.columns + x
                    xs[n] = field.dx[source] + field.subX[source]
                    ys[n] = field.dy[source] + field.subY[source]
                    n++
                }
            }
            xs.sort()
            ys.sort()
            val index = by * field.columns + bx
            val totalX = xs[NEIGHBOURHOOD / 2]
            val totalY = ys[NEIGHBOURHOOD / 2]
            out.dx[index] = floor(totalX + HALF_PIXEL).toInt()
            out.dy[index] = floor(totalY + HALF_PIXEL).toInt()
            out.subX[index] = totalX - out.dx[index]
            out.subY[index] = totalY - out.dy[index]
            out.residual[index] = field.residual[index]
        }
    }
    return out
}
