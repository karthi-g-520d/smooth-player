package com.example.smoothplayer.engine.impl.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class CadenceMathTest {
    @Test
    fun `thirty fps content on a 60 Hz panel is two refreshes per frame`() {
        assertEquals(2, expectedVsyncsPerFrame(vsyncPeriodNs = 16_666_667L, contentPeriodUs = 33_333L))
    }

    @Test
    fun `thirty fps content on a 120 Hz panel is four refreshes per frame`() {
        assertEquals(4, expectedVsyncsPerFrame(vsyncPeriodNs = 8_333_333L, contentPeriodUs = 33_333L))
    }

    @Test
    fun `content faster than the panel still expects at least one refresh per frame`() {
        assertEquals(1, expectedVsyncsPerFrame(vsyncPeriodNs = 33_333_333L, contentPeriodUs = 16_666L))
    }

    @Test
    fun `an unknown refresh period falls back to one`() {
        assertEquals(1, expectedVsyncsPerFrame(vsyncPeriodNs = 0L, contentPeriodUs = 33_333L))
    }
}
