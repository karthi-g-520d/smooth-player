package com.example.smoothplayer.engine.impl.interp

import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smoothplayer.engine.impl.gl.EglCore
import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val SEED = 6L
private const val MAX_LUMA = 256
private const val SUBPIXEL_TOLERANCE = 1e-4f
private const val RESIDUAL_RELATIVE_TOLERANCE = 1e-4f

// 1080p at quarter resolution: 60 x 34 blocks, with a partial last row.
private const val QUARTER_1080P_WIDTH = 480
private const val QUARTER_1080P_HEIGHT = 270

/**
 * Checks that the GPU motion search gives the CPU reference's field: identical integer vectors, and subpixel offsets
 * and residuals equal to float rounding. Runs on a real device with GLES 3.1, and is skipped without it.
 */
@RunWith(AndroidJUnit4::class)
class GpuMotionSearchTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
    private lateinit var core: EglCore
    private var search: GpuMotionSearch? = null
    private val textures = mutableListOf<Int>()

    @Before
    fun setUp() {
        core = EglCore.createPbuffer()
        core.makeCurrent()
        search = GpuMotionSearch.createIfSupported(assets)
        Assume.assumeTrue("GLES 3.1 is not available", search != null)
    }

    @After
    fun tearDown() {
        GLES30.glDeleteTextures(textures.size, textures.toIntArray(), 0)
        search?.close()
        core.close()
    }

    @Test
    fun rigidPanMatchesTheCpu() {
        val prev = noise(64, 48, Random(SEED))
        compare(64, 48, prev, shifted(prev, 64, 48, dx = 3, dy = -2))
    }

    @Test
    fun unrelatedFramesMatchTheCpu() {
        // No true motion, so ties, the smoothness penalty and the predictor all decide the vectors.
        val random = Random(SEED)
        compare(70, 45, noise(70, 45, random), noise(70, 45, random))
    }

    @Test
    fun halfPixelPanMatchesTheCpu() {
        val prev = noise(64, 48, Random(SEED))
        val moved = shifted(prev, 64, 48, dx = 1, dy = 0)
        compare(64, 48, prev, IntArray(prev.size) { (prev[it] + moved[it]) / 2 })
    }

    @Test
    fun quarterResolution1080pPanMatchesTheCpu() {
        val prev = noise(QUARTER_1080P_WIDTH, QUARTER_1080P_HEIGHT, Random(SEED))
        val next = shifted(prev, QUARTER_1080P_WIDTH, QUARTER_1080P_HEIGHT, dx = 5, dy = 1)
        compare(QUARTER_1080P_WIDTH, QUARTER_1080P_HEIGHT, prev, next)
    }

    private fun compare(width: Int, height: Int, prev: IntArray, next: IntArray) {
        val prevPlane = LumaPlane(width, height, prev)
        val nextPlane = LumaPlane(width, height, next)
        val prevCoarse = downsampled(prevPlane)
        val nextCoarse = downsampled(nextPlane)
        val expected = estimateMotion(prevPlane, nextPlane, prevCoarse, nextCoarse)
        val gpu = checkNotNull(search)
        gpu.run(
            upload(width, height, prev),
            upload(width, height, next),
            upload(prevCoarse.width, prevCoarse.height, pixelsOf(prevCoarse)),
            upload(nextCoarse.width, nextCoarse.height, pixelsOf(nextCoarse)),
            width,
            height,
        )
        val actual = gpu.readBack()

        assertEquals(expected.columns, actual.columns)
        assertEquals(expected.rows, actual.rows)
        for (i in 0 until expected.columns * expected.rows) {
            val block = "block (${i % expected.columns}, ${i / expected.columns})"
            assertEquals("$block dx", expected.dx[i], actual.dx[i])
            assertEquals("$block dy", expected.dy[i], actual.dy[i])
            assertEquals("$block subX", expected.subX[i], actual.subX[i], SUBPIXEL_TOLERANCE)
            assertEquals("$block subY", expected.subY[i], actual.subY[i], SUBPIXEL_TOLERANCE)
            val residualError = abs(expected.residual[i] - actual.residual[i])
            assertTrue(
                "$block residual ${actual.residual[i]}, expected ${expected.residual[i]}",
                residualError <= RESIDUAL_RELATIVE_TOLERANCE * maxOf(1f, expected.residual[i]),
            )
        }
    }

    private fun pixelsOf(plane: LumaPlane): IntArray = IntArray(plane.width * plane.height) { i ->
        plane.at(i % plane.width, i / plane.width)
    }

    private fun noise(width: Int, height: Int, random: Random): IntArray = IntArray(width * height) { random.nextInt(MAX_LUMA) }

    /** The content of [source] moved by (dx, dy), so next(p) = prev(p - v), with edges clamped like [LumaPlane.at]. */
    private fun shifted(source: IntArray, width: Int, height: Int, dx: Int, dy: Int): IntArray {
        val plane = LumaPlane(width, height, source)
        return IntArray(width * height) { i -> plane.at(i % width - dx, i / width - dy) }
    }

    private fun upload(width: Int, height: Int, pixels: IntArray): Int {
        val bytes = ByteBuffer.allocateDirect(pixels.size)
        for (value in pixels) bytes.put(value.toByte())
        bytes.position(0)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textures += ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, width, height, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, bytes,
        )
        return ids[0]
    }
}
