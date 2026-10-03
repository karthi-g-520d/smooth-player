package com.example.smoothplayer.engine.impl.gl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGeometryTest {

    @Test
    fun `landscape video letterboxes vertically in a tall surface`() {
        val viewport = fitViewport(surfaceWidth = 1080, surfaceHeight = 2400, contentWidth = 1920, contentHeight = 1080)
        assertEquals(Viewport(x = 0, y = 896, width = 1080, height = 607), viewport)
    }

    @Test
    fun `same aspect ratio fills the surface`() {
        val viewport = fitViewport(surfaceWidth = 1920, surfaceHeight = 1080, contentWidth = 1280, contentHeight = 720)
        assertEquals(Viewport(x = 0, y = 0, width = 1920, height = 1080), viewport)
    }

    @Test
    fun `empty content falls back to the whole surface`() {
        val viewport = fitViewport(surfaceWidth = 800, surfaceHeight = 600, contentWidth = 0, contentHeight = 0)
        assertEquals(Viewport(x = 0, y = 0, width = 800, height = 600), viewport)
    }

    @Test
    fun `zero rotation is the identity`() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f), clockwiseRotation(0), 0f)
    }

    @Test
    fun `ninety degrees maps the x axis to negative y, clockwise`() {
        // Column-major: column 0 = image of (1,0) = (0,-1); column 1 = image of (0,1) = (1,0).
        assertArrayEquals(floatArrayOf(0f, -1f, 1f, 0f), clockwiseRotation(90), 0f)
    }

    @Test
    fun `one eighty degrees negates both axes`() {
        assertArrayEquals(floatArrayOf(-1f, 0f, 0f, -1f), clockwiseRotation(180), 0f)
    }

    @Test
    fun `two seventy degrees is the same as minus ninety`() {
        assertArrayEquals(floatArrayOf(0f, 1f, -1f, 0f), clockwiseRotation(270), 0f)
        assertArrayEquals(clockwiseRotation(-90), clockwiseRotation(270), 0f)
    }
}
