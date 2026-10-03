package com.example.smoothplayer.engine.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitsTest {
    @Test
    fun `micros to nanos multiplies by one thousand`() {
        assertEquals(Nanos(33_333_000L), Micros(33_333L).toNanos())
    }

    @Test
    fun `nanos to micros truncates sub-microsecond remainder`() {
        assertEquals(Micros(16_666L), Nanos(16_666_999L).toMicros())
    }

    @Test
    fun `micros arithmetic and ordering`() {
        assertEquals(Micros(500L), Micros(1_000L) - Micros(500L))
        assertTrue(Micros(1L) < Micros(2L))
    }
}
