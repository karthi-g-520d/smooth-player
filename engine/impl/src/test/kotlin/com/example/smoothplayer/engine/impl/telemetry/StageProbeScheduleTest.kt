package com.example.smoothplayer.engine.impl.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SECOND_NS = 1_000_000_000L
private const val START_NS = 7 * SECOND_NS

class StageProbeScheduleTest {
    @Test
    fun `the probe runs in the first minute of every five`() {
        assertTrue(probeWindowAt(0L))
        assertTrue(probeWindowAt(59 * SECOND_NS))
        assertFalse(probeWindowAt(60 * SECOND_NS))
        assertFalse(probeWindowAt(299 * SECOND_NS))
        assertTrue(probeWindowAt(300 * SECOND_NS))
        assertFalse(probeWindowAt(-1L))
    }

    @Test
    fun `samples are at least a second apart`() {
        val schedule = StageProbeSchedule(START_NS)
        assertTrue(schedule.takeSample(START_NS))
        assertFalse(schedule.takeSample(START_NS + SECOND_NS / 2))
        assertTrue(schedule.takeSample(START_NS + SECOND_NS))
    }

    @Test
    fun `a minute-long window at 30 fps gives about sixty samples`() {
        val schedule = StageProbeSchedule(START_NS)
        val frameNs = SECOND_NS / 30
        val taken = (0 until 30 * 300).count { schedule.takeSample(START_NS + it * frameNs) }
        // Frames land just short of each whole second, so a sample now and then waits one frame more.
        assertTrue("took $taken", taken in 55..60)
    }
}
