package com.example.smoothplayer.engine.impl.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val PHASE_DELTA = 1e-4f
private const val FRAME_30_US = 33_333L
private const val MAX_GAP_US = MAX_BLEND_GAP_US
private val EMPTY = EMPTY_SLOT_PTS_US

class BlendSelectionTest {
    @Test
    fun `no resident frames gives no pick`() {
        assertNull(pickBlendPair(longArrayOf(EMPTY, EMPTY), contentUs = 0L, maxGapUs = MAX_GAP_US))
    }

    @Test
    fun `content before every resident frame gives no pick, so the caller shows the newest`() {
        assertNull(pickBlendPair(longArrayOf(100L, 133L), contentUs = 50L, maxGapUs = MAX_GAP_US))
    }

    @Test
    fun `content at a frame shows that frame at phase zero`() {
        val pick = pickBlendPair(longArrayOf(0L, FRAME_30_US), contentUs = 0L, maxGapUs = MAX_GAP_US)
        assertEquals(BlendPick(a = 0, b = 1, t = 0f), pick)
    }

    @Test
    fun `content half way between two frames blends them at phase one half`() {
        val pick = requireNotNull(pickBlendPair(longArrayOf(0L, FRAME_30_US), contentUs = 16_666L, maxGapUs = MAX_GAP_US))
        assertEquals(0, pick.a)
        assertEquals(1, pick.b)
        assertEquals(0.5f, pick.t, PHASE_DELTA)
    }

    @Test
    fun `the pair is found by timestamp, not by slot order`() {
        // Slot 0 holds the newer frame, so writing order is not the same as presentation order.
        val pick = requireNotNull(pickBlendPair(longArrayOf(133_000L, 100_000L, 166_000L, 66_000L), 120_000L, MAX_GAP_US))
        assertEquals(1, pick.a)
        assertEquals(0, pick.b)
        assertEquals(20f / 33f, pick.t, PHASE_DELTA)
    }

    @Test
    fun `empty slots are ignored`() {
        val pick = requireNotNull(pickBlendPair(longArrayOf(EMPTY, 100_000L, 133_000L, EMPTY), 116_500L, MAX_GAP_US))
        assertEquals(1, pick.a)
        assertEquals(2, pick.b)
        assertEquals(16.5f / 33f, pick.t, PHASE_DELTA)
    }

    @Test
    fun `newest frame with nothing after it holds at phase zero`() {
        val pick = pickBlendPair(longArrayOf(0L, FRAME_30_US), contentUs = 90_000L, maxGapUs = MAX_GAP_US)
        assertEquals(BlendPick(a = 1, b = 1, t = 0f), pick)
    }

    @Test
    fun `a neighbour further away than the gap limit is not blended into`() {
        val pick = pickBlendPair(longArrayOf(0L, MAX_GAP_US + 1L), contentUs = 10_000L, maxGapUs = MAX_GAP_US)
        assertEquals(BlendPick(a = 0, b = 0, t = 0f), pick)
    }

    @Test
    fun `a neighbour exactly at the gap limit is blended`() {
        val pick = requireNotNull(pickBlendPair(longArrayOf(0L, MAX_GAP_US), contentUs = MAX_GAP_US / 2, maxGapUs = MAX_GAP_US))
        assertEquals(BlendPick(a = 0, b = 1, t = 0.5f), pick)
    }
}
