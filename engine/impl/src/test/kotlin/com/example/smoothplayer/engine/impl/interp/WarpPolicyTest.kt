package com.example.smoothplayer.engine.impl.interp

import com.example.smoothplayer.engine.api.model.WarpFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val WIDTH = 160
private const val HEIGHT = 96
private const val CELL = 4
private const val PAN_DX = 3
private const val FLAT_LEVEL = 40
private const val DARK_SCALE = 4
private const val OTHER_SHOT = 10_000
private const val MS = 1_000_000L
private const val SECOND = 1_000_000_000L

private fun lattice(x: Int, y: Int): Int {
    val h = (x * 73_856_093L) xor (y * 19_349_663L) xor ((x * y) * 83_492_791L)
    return ((h * 2_654_435_761L) ushr 24 and 0xFF).toInt()
}

/** Random values on a 4 px lattice, interpolated bilinearly: detail a few pixels wide, like real video. */
private fun texture(x: Int, y: Int): Int {
    val cx = Math.floorDiv(x, CELL)
    val cy = Math.floorDiv(y, CELL)
    val fx = Math.floorMod(x, CELL)
    val fy = Math.floorMod(y, CELL)
    val top = lattice(cx, cy) * (CELL - fx) + lattice(cx + 1, cy) * fx
    val bottom = lattice(cx, cy + 1) * (CELL - fx) + lattice(cx + 1, cy + 1) * fx
    return (top * (CELL - fy) + bottom * fy) / (CELL * CELL)
}

private fun plane(sample: (Int, Int) -> Int): LumaPlane =
    LumaPlane(WIDTH, HEIGHT, IntArray(WIDTH * HEIGHT) { i -> sample(i % WIDTH, i / WIDTH) })

private fun statsOf(prev: LumaPlane, next: LumaPlane): PairStats = pairStats(prev, next, estimateMotion(prev, next))

class WarpPolicyTest {
    @Test
    fun `a pan matches everywhere and keeps its histogram, so it is not a cut`() {
        val stats = statsOf(plane { x, y -> texture(x, y) }, plane { x, y -> texture(x - PAN_DX, y) })
        // Measured: low-confidence 0.00, histogram distance 0.15 (the plane is small, so the new strip at the edge counts).
        assertTrue("low confidence ${stats.lowConfidenceFraction}", stats.lowConfidenceFraction < 0.1f)
        assertTrue("histogram ${stats.histogramDistance}", stats.histogramDistance < 0.2f)
        assertFalse(isSceneCut(stats))
    }

    @Test
    fun `a cut to a darker shot changes the histogram and the matches, so it is a cut`() {
        val stats = statsOf(plane { x, y -> texture(x, y) }, plane { x, y -> texture(x + OTHER_SHOT, y) / DARK_SCALE })
        assertTrue("histogram ${stats.histogramDistance}", stats.histogramDistance > 1f)
        assertTrue(isSceneCut(stats))
    }

    @Test
    fun `a cut between two shots with the same tones is still a cut, because nothing matches`() {
        val stats = statsOf(plane { x, y -> texture(x, y) }, plane { x, y -> texture(x + OTHER_SHOT, y + OTHER_SHOT) })
        assertTrue("low confidence ${stats.lowConfidenceFraction}", stats.lowConfidenceFraction > 0.8f)
        assertTrue(isSceneCut(stats))
    }

    @Test
    fun `flat frames have no textured blocks, so they count as fully confident and never as a cut`() {
        val stats = statsOf(plane { _, _ -> FLAT_LEVEL }, plane { _, _ -> FLAT_LEVEL + 1 })
        assertEquals(0f, stats.lowConfidenceFraction, 0f)
        assertFalse(isSceneCut(stats))
    }

    @Test
    fun `the stats from raw counts divide by textured blocks and by pixels`() {
        val stats = PairStats.fromCounts(texturedBlocks = 200, lowConfidenceBlocks = 50, pixels = 1000, histogramDiff = 300)
        assertEquals(0.25f, stats.lowConfidenceFraction, 1e-6f)
        assertEquals(0.3f, stats.histogramDistance, 1e-6f)
        assertEquals(PairStats(0f, 0f), PairStats.fromCounts(0, 0, 0, 0))
    }

    @Test
    fun `low confidence falls back after two bad pairs and returns only after eight good ones`() {
        val governor = WarpGovernor()
        val bad = PairStats(lowConfidenceFraction = 0.5f, histogramDistance = 0f)
        val middling = PairStats(lowConfidenceFraction = 0.35f, histogramDistance = 0f)
        val good = PairStats(lowConfidenceFraction = 0.1f, histogramDistance = 0f)

        governor.onPair(bad)
        assertEquals(WarpFallback.None, governor.fallback)
        governor.onPair(bad)
        assertEquals(WarpFallback.LowConfidence, governor.fallback)
        // Between the two thresholds nothing changes: that gap is the hysteresis.
        repeat(20) { governor.onPair(middling) }
        assertEquals(WarpFallback.LowConfidence, governor.fallback)
        repeat(7) { governor.onPair(good) }
        assertEquals(WarpFallback.LowConfidence, governor.fallback)
        governor.onPair(good)
        assertEquals(WarpFallback.None, governor.fallback)
        assertFalse(governor.holdsFrames)
    }

    @Test
    fun `heat holds frames at 0_9 headroom and lets go below 0_8 once the hold has passed`() {
        val governor = WarpGovernor()
        governor.onThermal(0.85f, 0L)
        assertEquals(WarpFallback.None, governor.fallback)
        governor.onThermal(0.9f, SECOND)
        assertEquals(WarpFallback.Thermal, governor.fallback)
        assertTrue(governor.holdsFrames)
        governor.onThermal(0.7f, 2 * SECOND)
        assertEquals("too soon after the fallback started", WarpFallback.Thermal, governor.fallback)
        governor.onThermal(0.85f, 10 * SECOND)
        assertEquals("not yet cool enough", WarpFallback.Thermal, governor.fallback)
        governor.onThermal(0.79f, 11 * SECOND)
        assertEquals(WarpFallback.None, governor.fallback)
        governor.onThermal(null, 12 * SECOND)
        assertEquals(WarpFallback.None, governor.fallback)
    }

    @Test
    fun `a slow present holds frames, ignores the cheap fallback's times, and retries after the hold`() {
        val governor = WarpGovernor()
        governor.onPresentGpu(5 * MS, 0L)
        assertEquals(WarpFallback.None, governor.fallback)
        // The smoothing moves 1/16 of the way per sample, so a few slow draws in a row are needed.
        var now = 0L
        while (governor.fallback == WarpFallback.None && now < SECOND) {
            now += 16 * MS
            governor.onPresentGpu(20 * MS, now)
        }
        assertEquals(WarpFallback.GpuBudget, governor.fallback)
        governor.onPresentGpu(1 * MS, now + SECOND)
        assertEquals(WarpFallback.GpuBudget, governor.fallback)
        governor.onPresentGpu(5 * MS, now + 6 * SECOND)
        assertEquals(WarpFallback.None, governor.fallback)
    }

    @Test
    fun `heat outranks the GPU budget, which outranks low confidence, and reset clears them all`() {
        val governor = WarpGovernor()
        repeat(2) { governor.onPair(PairStats(0.9f, 0f)) }
        governor.onPresentGpu(50 * MS, 0L)
        assertEquals(WarpFallback.GpuBudget, governor.fallback)
        governor.onThermal(0.95f, 0L)
        assertEquals(WarpFallback.Thermal, governor.fallback)
        governor.reset()
        assertEquals(WarpFallback.None, governor.fallback)
    }
}
