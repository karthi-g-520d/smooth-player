package com.example.smoothplayer.engine.impl.pacer

import com.example.smoothplayer.engine.api.model.Micros
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchRuleTest {
    @Test
    fun `duplicate mode never prefetches`() {
        assertFalse(shouldPrefetchNext(blending = false, lastReleased = Micros(0), clock = Micros(1_000_000)))
    }

    @Test
    fun `blending before any frame is released does not prefetch`() {
        assertFalse(shouldPrefetchNext(blending = true, lastReleased = null, clock = Micros(1_000_000)))
    }

    @Test
    fun `blending prefetches once the last released frame is due`() {
        assertTrue(shouldPrefetchNext(blending = true, lastReleased = Micros(1_000_000), clock = Micros(1_000_000)))
        assertTrue(shouldPrefetchNext(blending = true, lastReleased = Micros(1_000_000), clock = Micros(1_010_000)))
    }

    @Test
    fun `blending does not prefetch past a frame that is still in the future`() {
        assertFalse(shouldPrefetchNext(blending = true, lastReleased = Micros(1_033_333), clock = Micros(1_000_000)))
    }
}
