package com.example.smoothplayer.engine.impl.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryMathTest {
    @Test
    fun `thirty frames across one second is 30 fps`() {
        // 31 frames at 33_333 us spacing span 30 intervals, so 30 frames per second.
        assertEquals(30f, contentFps(frames = 31L, firstPtsUs = 0L, lastPtsUs = 30L * 33_333L), 0.01f)
    }

    @Test
    fun `one frame has no content rate`() {
        assertEquals(0f, contentFps(frames = 1L, firstPtsUs = 0L, lastPtsUs = 0L), 0f)
    }

    @Test
    fun `identical timestamps do not divide by zero`() {
        assertEquals(0f, contentFps(frames = 5L, firstPtsUs = 100L, lastPtsUs = 100L), 0f)
    }

    @Test
    fun `nearest-rank percentile picks the median and the 95th value`() {
        val sorted = longArrayOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)
        assertEquals(5L, percentile(sorted, 0.50))
        assertEquals(10L, percentile(sorted, 0.95))
    }

    @Test
    fun `percentile of no samples is null`() {
        assertNull(percentile(LongArray(0), 0.50))
    }

    @Test
    fun `percentile of one sample is that sample`() {
        assertEquals(42L, percentile(longArrayOf(42L), 0.95))
    }
}
