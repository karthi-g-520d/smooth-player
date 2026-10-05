package com.example.smoothplayer.engine.impl.gl

import android.opengl.GLES30
import com.example.smoothplayer.engine.api.model.Nanos
import com.example.smoothplayer.engine.impl.threading.GlThread

private const val EXTENSION_NAME = "GL_EXT_disjoint_timer_query"
private const val GL_TIME_ELAPSED_EXT = 0x88BF
private const val GL_GPU_DISJOINT_EXT = 0x8FBB
private const val RING_SIZE = 4

/**
 * GPU time for one pass, measured with `GL_EXT_disjoint_timer_query`. The result for a pass is only read
 * two passes later, once the GPU has finished it, so measuring never waits on the GPU.
 *
 * A result is thrown away when the GPU reports a disjoint event, because the timer is not reliable across one.
 * Only the GL thread may call this class.
 */
@GlThread
internal class GpuTimerRing private constructor(private val queries: IntArray) {
    private var passes = 0L
    private var samples = 0L

    /**
     * Times [pass] on one call in [SAMPLE_EVERY]. Other calls just run [pass]. The result for an earlier sample is
     * delivered through [onResult] when it is ready. Timing every pass stalled every draw, because reading the
     * disjoint flag waits on the GPU, so the cost is paid on a sample only.
     */
    fun time(pass: () -> Unit, onResult: (Nanos) -> Unit) {
        val sampled = passes++ % SAMPLE_EVERY == 0L
        if (!sampled) {
            pass()
            return
        }
        val slot = (samples % RING_SIZE).toInt()
        GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, queries[slot])
        pass()
        GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
        samples++
        readOlderSample(onResult)
    }

    private fun readOlderSample(onResult: (Nanos) -> Unit) {
        if (samples <= READ_LAG) return
        val slot = ((samples - 1 - READ_LAG) % RING_SIZE).toInt()
        val query = queries[slot]

        val available = IntArray(1)
        GLES30.glGetQueryObjectuiv(query, GLES30.GL_QUERY_RESULT_AVAILABLE, available, 0)
        if (available[0] == 0) return

        val disjoint = IntArray(1)
        GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, disjoint, 0)
        val elapsed = IntArray(1)
        GLES30.glGetQueryObjectuiv(query, GLES30.GL_QUERY_RESULT, elapsed, 0)
        if (disjoint[0] != 0) return
        onResult(Nanos(elapsed[0].toLong() and UINT_MASK))
    }

    fun close() {
        GLES30.glDeleteQueries(RING_SIZE, queries, 0)
    }

    companion object {
        private const val READ_LAG = 2
        private const val SAMPLE_EVERY = 30L
        private const val UINT_MASK = 0xFFFF_FFFFL

        /** Null when the device does not offer the extension. Callers then skip timing and report it as unavailable. */
        fun createIfSupported(): GpuTimerRing? {
            val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
            if (!extensions.contains(EXTENSION_NAME)) return null
            val queries = IntArray(RING_SIZE)
            GLES30.glGenQueries(RING_SIZE, queries, 0)
            return GpuTimerRing(queries)
        }
    }
}
