package com.example.smoothplayer.engine.impl.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioClockMathTest {
    @Test
    fun `one second of frames advances the clock by one second`() {
        assertEquals(2_000_000L, audioPositionUs(anchorPtsUs = 1_000_000L, anchorHead = 0L, head = 48_000L, sampleRate = 48_000))
    }

    @Test
    fun `the clock starts at the anchor pts before any frames play`() {
        assertEquals(500_000L, audioPositionUs(anchorPtsUs = 500_000L, anchorHead = 0L, head = 0L, sampleRate = 44_100))
    }

    @Test
    fun `the head counter wrapping past 2^32 still measures the frames played`() {
        val anchorHead = 0xFFFF_FFF0L
        val head = 0x10L
        assertEquals(666L, audioPositionUs(anchorPtsUs = 0L, anchorHead = anchorHead, head = head, sampleRate = 48_000))
    }

    @Test
    fun `a clock anchored mid-stream counts only frames after the anchor`() {
        assertEquals(1_500_000L, audioPositionUs(anchorPtsUs = 1_000_000L, anchorHead = 24_000L, head = 48_000L, sampleRate = 48_000))
    }
}
