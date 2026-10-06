package com.example.smoothplayer.engine.impl.interp

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plan's Step 6 bar: median vector error under 0.5 px on a synthetic pan. Worst case is held to the same bar here. */
private const val PLAN_BAR_PX = 0.5
private const val MEDIAN_BAR_PX = 0.05
private const val PLANE_WIDTH = 128
private const val PLANE_HEIGHT = 96
private const val PAN_DX = 3
private const val PAN_DY = -2
private const val BLOCK = 8
private const val RANGE = 8

/** A deterministic, non-repeating texture, so each block has one clear best match. */
private fun texture(x: Int, y: Int): Int {
    val h = (x * 73_856_093L) xor (y * 19_349_663L) xor ((x * y) * 83_492_791L)
    return ((h * 2_654_435_761L) ushr 24 and 0xFF).toInt()
}

private fun plane(sample: (Int, Int) -> Int): LumaPlane =
    LumaPlane(PLANE_WIDTH, PLANE_HEIGHT, IntArray(PLANE_WIDTH * PLANE_HEIGHT) { i -> sample(i % PLANE_WIDTH, i / PLANE_WIDTH) })

/** Blocks whose search window stays inside the plane, so the true vector is the only exact match. */
private fun isInterior(bx: Int, by: Int, columns: Int, rows: Int): Boolean {
    val margin = 2
    return bx in margin until columns - margin && by in margin until rows - margin
}

class MotionSearchTest {
    @Test
    fun `a rigid pan gives the true vector in every interior block`() {
        val prev = plane { x, y -> texture(x, y) }
        // The content moved by (PAN_DX, PAN_DY), so next(p) = prev(p - v).
        val next = plane { x, y -> texture(x - PAN_DX, y - PAN_DY) }

        val field = estimateMotion(prev, next, MotionSearchConfig(blockSize = BLOCK, searchRange = RANGE))

        for (by in 0 until field.rows) {
            for (bx in 0 until field.columns) {
                if (!isInterior(bx, by, field.columns, field.rows)) continue
                val index = by * field.columns + bx
                assertEquals("dx at block ($bx, $by)", PAN_DX, field.dx[index])
                assertEquals("dy at block ($bx, $by)", PAN_DY, field.dy[index])
            }
        }
    }

    @Test
    fun `identical frames give zero motion everywhere`() {
        val frame = plane { x, y -> texture(x, y) }

        val field = estimateMotion(frame, frame, MotionSearchConfig(blockSize = BLOCK, searchRange = RANGE))

        assertEquals(true, field.dx.all { it == 0 })
        assertEquals(true, field.dy.all { it == 0 })
    }

    @Test
    fun `flat content keeps zero vectors, because the smoothness penalty breaks every tie`() {
        val prev = plane { _, _ -> 128 }
        val next = plane { _, _ -> 128 }

        val field = estimateMotion(prev, next, MotionSearchConfig(blockSize = BLOCK, searchRange = RANGE))

        assertEquals(true, field.dx.all { it == 0 })
        assertEquals(true, field.dy.all { it == 0 })
    }

    @Test
    fun `an exact whole-pixel pan has no subpixel offset and zero residual`() {
        val prev = plane { x, y -> texture(x, y) }
        val next = plane { x, y -> texture(x - PAN_DX, y - PAN_DY) }

        val field = estimateMotion(prev, next, MotionSearchConfig(blockSize = BLOCK, searchRange = RANGE))

        for (by in 0 until field.rows) {
            for (bx in 0 until field.columns) {
                if (!isInterior(bx, by, field.columns, field.rows)) continue
                val index = by * field.columns + bx
                assertEquals("subX at ($bx, $by)", 0.0, field.subX[index].toDouble(), 0.0)
                assertEquals("subY at ($bx, $by)", 0.0, field.subY[index].toDouble(), 0.0)
                assertEquals("residual at ($bx, $by)", 0.0, field.residual[index].toDouble(), 0.0)
            }
        }
    }

    @Test
    fun `a half-pixel pan gives a total vector near one half, within the plan bar of half a pixel`() {
        val prev = plane { x, y -> texture(x, y) }
        // next(p) = prev(p - 0.5): the average of the two texels either side of p.
        val next = plane { x, y -> (texture(x - 1, y) + texture(x, y) + 1) / 2 }

        val field = estimateMotion(prev, next, MotionSearchConfig(blockSize = BLOCK, searchRange = RANGE))

        // Integer search ties at 0 and 1, so the split between dx and subX varies; the total must not.
        // Measured with a replica of this code: median error 0.002 px, and one tie block at 0.32 px.
        val errors = mutableListOf<Double>()
        for (by in 0 until field.rows) {
            for (bx in 0 until field.columns) {
                if (!isInterior(bx, by, field.columns, field.rows)) continue
                val index = by * field.columns + bx
                val total = (field.dx[index] + field.subX[index]).toDouble()
                errors.add(abs(total - 0.5))
            }
        }
        val sorted = errors.sorted()
        assertTrue("median error ${sorted[sorted.size / 2]} px", sorted[sorted.size / 2] < MEDIAN_BAR_PX)
        assertTrue("worst error ${sorted.last()} px", sorted.last() < PLAN_BAR_PX)
    }

    @Test
    fun `a single wrong block is replaced by the median of its neighbours`() {
        // Interior vectors all agree, so the 3x3 median must restore a lone outlier in the middle.
        val columns = 5
        val rows = 5
        val field = MotionField(columns, rows)
        field.dx.fill(PAN_DX)
        field.dy.fill(PAN_DY)
        val centre = 2 * columns + 2
        field.dx[centre] = -7
        field.dy[centre] = 6

        val filtered = medianFiltered(field)

        assertEquals(PAN_DX, filtered.dx[centre])
        assertEquals(PAN_DY, filtered.dy[centre])
    }
}
