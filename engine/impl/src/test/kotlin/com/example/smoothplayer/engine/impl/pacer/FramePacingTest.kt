package com.example.smoothplayer.engine.impl.pacer

import com.example.smoothplayer.engine.api.model.Micros
import org.junit.Assert.assertEquals
import org.junit.Test

class FramePacingTest {
    @Test
    fun `a frame at the clock is shown`() {
        assertEquals(FrameDecision.Render, decideFrame(Micros(1_000_000), Micros(1_000_000), stalled = false))
    }

    @Test
    fun `a frame slightly behind the clock is still shown`() {
        assertEquals(FrameDecision.Render, decideFrame(Micros(1_000_000), Micros(1_030_000), stalled = false))
    }

    @Test
    fun `a frame exactly 40 ms late is still shown`() {
        assertEquals(FrameDecision.Render, decideFrame(Micros(1_000_000), Micros(1_040_000), stalled = false))
    }

    @Test
    fun `a frame more than 40 ms late is dropped`() {
        assertEquals(FrameDecision.Drop, decideFrame(Micros(1_000_000), Micros(1_040_001), stalled = false))
    }

    @Test
    fun `an early frame waits for the clock to reach it`() {
        assertEquals(FrameDecision.Wait(5_000L), decideFrame(Micros(1_005_000), Micros(1_000_000), stalled = false))
    }

    @Test
    fun `a far-off frame polls at most every 8 ms`() {
        assertEquals(FrameDecision.Wait(8_000L), decideFrame(Micros(2_000_000), Micros(1_000_000), stalled = false))
    }

    @Test
    fun `a stalled clock never holds a frame back`() {
        assertEquals(FrameDecision.Render, decideFrame(Micros(2_000_000), Micros(1_000_000), stalled = true))
    }
}
