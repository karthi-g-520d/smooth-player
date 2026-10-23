package com.example.smoothplayer.engine.impl.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionLumaTest {
    @Test
    fun `720p and smaller use quarter luma`() {
        assertEquals(4, motionLumaDivisor(1280, 720))
        assertEquals(4, motionLumaDivisor(640, 360))
        assertEquals(4, motionLumaDivisor(1, 1))
    }

    @Test
    fun `1080p is capped to 320x180`() {
        assertEquals(6, motionLumaDivisor(1920, 1080))
        assertEquals(6, motionLumaDivisor(1080, 1920))
    }

    @Test
    fun `4K is capped to 320x180`() {
        assertEquals(12, motionLumaDivisor(3840, 2160))
    }

    @Test
    fun `divisor is rounded up to an even number`() {
        // 900 / 180 = 5, which the two-texel taps cannot average exactly.
        assertEquals(6, motionLumaDivisor(1600, 900))
        assertEquals(8, motionLumaDivisor(2560, 1440))
    }

    @Test
    fun `short side never exceeds the cap`() {
        for (shortSide in 1..2400) {
            val divisor = motionLumaDivisor(shortSide * 16 / 9, shortSide)
            assertEquals("short side $shortSide", 0, divisor % 2)
            assertTrue("short side $shortSide", ceilDiv(shortSide, divisor) <= MOTION_LUMA_MAX_SHORT_SIDE)
        }
    }

    @Test
    fun `taps cover the divisor in pairs`() {
        assertEquals(2, motionLumaTaps(4))
        assertEquals(3, motionLumaTaps(6))
        assertEquals(6, motionLumaTaps(12))
    }
}
