package com.example.smoothplayer.engine.impl.interp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grids that cover a single block, a single row or column, and the 1080p quarter-resolution field (60 x 34). */
private val GRIDS = listOf(1 to 1, 1 to 5, 5 to 1, 2 to 2, 3 to 7, 60 to 34)

class MotionWavefrontTest {
    @Test
    fun `every block is dispatched exactly once`() {
        for ((columns, rows) in GRIDS) {
            val seen = waveOfEachBlock(columns, rows)
            assertTrue("grid $columns x $rows has an undispatched block", seen.all { it >= 0 })
        }
    }

    @Test
    fun `each block's causal neighbours finish on an earlier wavefront`() {
        for ((columns, rows) in GRIDS) {
            val wave = waveOfEachBlock(columns, rows)
            for (by in 0 until rows) {
                for (bx in 0 until columns) {
                    val own = wave[by * columns + bx]
                    if (bx > 0) assertTrue(wave[by * columns + bx - 1] < own)
                    if (by > 0) assertTrue(wave[(by - 1) * columns + bx] < own)
                    if (by > 0 && bx + 1 < columns) assertTrue(wave[(by - 1) * columns + bx + 1] < own)
                }
            }
        }
    }

    /** The wavefront that dispatches each block, failing on a block outside the grid or dispatched twice. */
    private fun waveOfEachBlock(columns: Int, rows: Int): IntArray {
        val wave = IntArray(columns * rows) { -1 }
        for (w in 0 until wavefrontCount(columns, rows)) {
            for (by in wavefrontFirstRow(w, columns)..wavefrontLastRow(w, rows)) {
                val bx = w - 2 * by
                assertTrue("block ($bx, $by) is outside $columns x $rows", bx in 0 until columns && by in 0 until rows)
                assertEquals("block ($bx, $by) dispatched twice", -1, wave[by * columns + bx])
                wave[by * columns + bx] = w
            }
        }
        return wave
    }
}
