package com.example.smoothplayer.engine.impl.interp

import android.opengl.GLES30
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smoothplayer.engine.impl.gl.EglCore
import java.nio.ByteBuffer
import java.util.Random
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "FI/Bench"
private const val SEED = 6L
private const val MAX_LUMA = 256
private const val WARM_UP_RUNS = 5
private const val TIMED_RUNS = 60
private const val NANOS_PER_MILLI = 1_000_000.0
private const val P50 = 0.50
private const val P95 = 0.95

// The motion luma the renderer uses for 720p and larger frames (the cap in MotionLuma.kt), and the uncapped 1/4 luma
// of a 1080p frame, kept for comparison with the earlier measurements.
private const val CAPPED_WIDTH = 320
private const val CAPPED_HEIGHT = 180
private const val QUARTER_1080P_WIDTH = 480
private const val QUARTER_1080P_HEIGHT = 270

/**
 * Wall-clock time of one motion search, from the first GL call to glFinish, on a 5 px pan. This is the number the plan's
 * Step 6 bar (≤ 3 ms flagship, ≤ 5 ms mid-range at 1080p) is checked against. Results go to logcat under FI/Bench and to
 * the instrumentation status, so they show in the Gradle output.
 */
@RunWith(AndroidJUnit4::class)
class GpuMotionSearchBenchmark {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var core: EglCore
    private var search: GpuMotionSearch? = null
    private val textures = mutableListOf<Int>()

    @Before
    fun setUp() {
        core = EglCore.createPbuffer()
        core.makeCurrent()
        search = GpuMotionSearch.createIfSupported(instrumentation.targetContext.assets)
        Assume.assumeTrue("GLES 3.1 is not available", search != null)
    }

    @After
    fun tearDown() {
        GLES30.glDeleteTextures(textures.size, textures.toIntArray(), 0)
        search?.close()
        core.close()
    }

    @Test
    fun cappedMotionLuma() = measure("720p and up, capped", CAPPED_WIDTH, CAPPED_HEIGHT)

    @Test
    fun uncappedQuarterResolution1080p() = measure("1080p uncapped", QUARTER_1080P_WIDTH, QUARTER_1080P_HEIGHT)

    private fun measure(label: String, width: Int, height: Int) {
        val random = Random(SEED)
        val prev = IntArray(width * height) { random.nextInt(MAX_LUMA) }
        val plane = LumaPlane(width, height, prev)
        val next = IntArray(width * height) { i -> plane.at(i % width - 5, i / width - 1) }
        val prevTexture = upload(width, height, prev)
        val nextTexture = upload(width, height, next)
        val prevCoarse = uploadPlane(downsampled(plane))
        val nextCoarse = uploadPlane(downsampled(LumaPlane(width, height, next)))
        val gpu = checkNotNull(search)

        repeat(WARM_UP_RUNS) {
            gpu.run(prevTexture, nextTexture, prevCoarse, nextCoarse, width, height)
            GLES30.glFinish()
        }
        val millis = DoubleArray(TIMED_RUNS) {
            val start = System.nanoTime()
            gpu.run(prevTexture, nextTexture, prevCoarse, nextCoarse, width, height)
            GLES30.glFinish()
            (System.nanoTime() - start) / NANOS_PER_MILLI
        }
        millis.sort()
        val summary = "motion search $label (${width}x$height luma): " +
            "p50 %.2f ms, p95 %.2f ms, max %.2f ms".format(percentile(millis, P50), percentile(millis, P95), millis.last())
        Log.i(TAG, summary)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$summary\n") })
    }

    private fun percentile(sorted: DoubleArray, fraction: Double): Double =
        sorted[(kotlin.math.ceil(fraction * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]

    private fun uploadPlane(plane: LumaPlane): Int =
        upload(plane.width, plane.height, IntArray(plane.width * plane.height) { i -> plane.at(i % plane.width, i / plane.width) })

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
