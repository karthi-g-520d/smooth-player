package com.example.smoothplayer.engine.impl.interp

import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import com.example.smoothplayer.engine.impl.gl.SharedEglContext
import com.example.smoothplayer.engine.impl.threading.GlThread
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "MotionFields"
private const val NO_FENCE = 0L
private const val NO_PTS = Long.MIN_VALUE
private const val NO_GENERATION = 0L

/** How long the reader waits on one fence before checking whether it should stop. */
private const val FENCE_WAIT_NS = 50_000_000L
private const val QUEUE_POLL_MS = 50L
private const val QUEUE_PER_SLOT = 4
private const val JOIN_TIMEOUT_MS = 500L

/**
 * One motion field, one scene-cut verdict and one stats buffer per frame-ring slot. The field of a slot is the motion
 * from the frame before it to the frame in it, so every pair still in the ring keeps its own vectors and the warp never
 * uses a newer pair's.
 *
 * The GPU decides scene cuts itself and writes the verdict next to the field, so the warp can draw a pair as soon as
 * its search is queued, with no CPU round trip. The CPU still wants the stats, for the low-confidence fallback and the
 * overlay, but it reads them on a worker thread with its own shared context: on the vivo V2334 (Mali) mapping the
 * buffer on the GL thread stalled it for up to a whole refresh, wherever in the frame it was done.
 *
 * Only the GL thread may call the public methods, with a GLES 3.1 context current. [close] stops the worker and frees
 * everything.
 */
@GlThread
internal class MotionFields(
    slotCount: Int,
    val columns: Int,
    val rows: Int,
    /** The worker's context, in the GL thread's share group. Null: no stats reach the CPU. */
    sharedContext: SharedEglContext?,
    /** Gets the wall time, in nanoseconds, of each stats read on the worker. Must be thread-safe. */
    private val onRead: (Long) -> Unit = {},
) : AutoCloseable {
    private val textures = IntArray(slotCount)
    private val verdicts = IntArray(slotCount)
    private val buffers = IntArray(slotCount)
    private val prevPts = LongArray(slotCount) { NO_PTS }
    private val nextPts = LongArray(slotCount) { NO_PTS }
    private var nextGeneration = NO_GENERATION
    private val consumed = LongArray(slotCount)

    // Shared with the worker, guarded by [lock].
    private val lock = Any()
    private val requested = LongArray(slotCount)
    private val fences = LongArray(slotCount)
    private val fenceGeneration = LongArray(slotCount)
    private val readGeneration = LongArray(slotCount)
    private val lowConfidence = FloatArray(slotCount)
    private val histogram = FloatArray(slotCount)
    private val cut = BooleanArray(slotCount)

    private val queue = ArrayBlockingQueue<Int>(slotCount * QUEUE_PER_SLOT)

    @Volatile private var running = true
    private val worker: Thread?

    init {
        GLES30.glGenTextures(slotCount, textures, 0)
        for (texture in textures) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            // Immutable storage, so the median pass can bind it as an image.
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, columns, rows)
            // Linear, so the warp upsamples the block field bilinearly.
            setFilter(GLES30.GL_LINEAR)
        }
        GLES30.glGenTextures(slotCount, verdicts, 0)
        for (texture in verdicts) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R32F, 1, 1)
            // R32F cannot be filtered: a linear filter would leave the texture incomplete and read as 0 (a cut).
            setFilter(GLES30.GL_NEAREST)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glGenBuffers(slotCount, buffers, 0)
        for (buffer in buffers) {
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer)
            GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, GpuMotionSearch.STATS_BYTES, null, GLES30.GL_DYNAMIC_READ)
        }
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        worker = sharedContext?.let { context ->
            Thread({ readLoop(context) }, "SmoothPlayer-Stats").apply { start() }
        }
    }

    fun texture(slot: Int): Int = textures[slot]

    fun verdictTexture(slot: Int): Int = verdicts[slot]

    fun statsBuffer(slot: Int): Int = buffers[slot]

    /** Forgets [slot]'s field, for example because a new frame was written into the slot. */
    fun invalidate(slot: Int) {
        prevPts[slot] = NO_PTS
        nextPts[slot] = NO_PTS
        val stale = synchronized(lock) {
            requested[slot] = NO_GENERATION
            takeFence(slot)
        }
        if (stale != NO_FENCE) GLES30.glDeleteSync(stale)
    }

    /** Forgets every field, for a seek. */
    fun invalidateAll() {
        for (slot in textures.indices) invalidate(slot)
    }

    /**
     * Records that the search for the pair ([prevPtsUs], [nextPtsUs]) into [slot] was just queued, puts a fence behind
     * it and hands the fence to the worker. Call straight after [GpuMotionSearch.run], and flush before the worker can
     * see the fence signal.
     */
    fun markSearched(slot: Int, prevPtsUs: Long, nextPtsUs: Long) {
        prevPts[slot] = prevPtsUs
        nextPts[slot] = nextPtsUs
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        val generation = ++nextGeneration
        val stale = synchronized(lock) {
            requested[slot] = generation
            val old = takeFence(slot)
            fences[slot] = fence
            fenceGeneration[slot] = generation
            old
        }
        // A fence the worker never took is ours to delete; one it took, it deletes.
        if (stale != NO_FENCE) GLES30.glDeleteSync(stale)
        if (worker != null) queue.offer(slot)
    }

    /** True when [slot] holds the field of exactly the pair ([prevPtsUs], [nextPtsUs]). It may still be on the GPU. */
    fun holds(slot: Int, prevPtsUs: Long, nextPtsUs: Long): Boolean =
        prevPts[slot] == prevPtsUs && nextPts[slot] == nextPtsUs && prevPtsUs != NO_PTS

    /**
     * A slot whose current pair's stats the worker has read and that no earlier call returned, or -1. Read its stats
     * with [stats] and [isCut] straight away. Never waits.
     */
    fun takeReadSlot(): Int = synchronized(lock) {
        for (slot in textures.indices) {
            val generation = requested[slot]
            if (generation == NO_GENERATION || readGeneration[slot] != generation) continue
            if (consumed[slot] == generation) continue
            consumed[slot] = generation
            return slot
        }
        -1
    }

    /** True when [slot]'s pair spans a scene cut. Only valid for a slot [takeReadSlot] just returned. */
    fun isCut(slot: Int): Boolean = synchronized(lock) { cut[slot] }

    /** The stats of [slot]'s pair. Only valid for a slot [takeReadSlot] just returned. Allocates, once per pair. */
    fun stats(slot: Int): PairStats = synchronized(lock) { PairStats(lowConfidence[slot], histogram[slot]) }

    private fun takeFence(slot: Int): Long {
        val fence = fences[slot]
        fences[slot] = NO_FENCE
        return fence
    }

    /** The worker: waits for each searched pair's fence, maps its stats buffer and publishes the counts. */
    private fun readLoop(context: SharedEglContext) {
        try {
            context.makeCurrent()
            while (running) {
                val slot = queue.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                var fence = NO_FENCE
                var generation = NO_GENERATION
                synchronized(lock) {
                    fence = takeFence(slot)
                    generation = fenceGeneration[slot]
                }
                if (fence == NO_FENCE) continue
                var status: Int
                do {
                    status = GLES30.glClientWaitSync(fence, 0, FENCE_WAIT_NS)
                } while (status == GLES30.GL_TIMEOUT_EXPIRED && running)
                GLES30.glDeleteSync(fence)
                if (!running) break
                if (status == GLES30.GL_WAIT_FAILED) continue
                if (synchronized(lock) { requested[slot] != generation }) continue
                readStats(slot, generation)
            }
        } catch (_: InterruptedException) {
            // close() wakes the worker this way; it is done.
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Stats reader stopped", e)
        } finally {
            context.close()
        }
    }

    private fun readStats(slot: Int, generation: Long) {
        val startNs = System.nanoTime()
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[slot])
        val mapped = GLES30.glMapBufferRange(
            GLES31.GL_SHADER_STORAGE_BUFFER, 0, GpuMotionSearch.STATS_BYTES, GLES30.GL_MAP_READ_BIT,
        )
        if (mapped is ByteBuffer) {
            val bytes = mapped.order(ByteOrder.nativeOrder())
            val counts = PairStats.fromCounts(
                texturedBlocks = bytes.getInt(0),
                lowConfidenceBlocks = bytes.getInt(Int.SIZE_BYTES),
                pixels = bytes.getInt(2 * Int.SIZE_BYTES),
                histogramDiff = bytes.getInt(3 * Int.SIZE_BYTES),
            )
            GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            synchronized(lock) {
                // A newer search of the slot may have overwritten the buffer while it was mapped: drop the read.
                if (requested[slot] == generation) {
                    lowConfidence[slot] = counts.lowConfidenceFraction
                    histogram[slot] = counts.histogramDistance
                    cut[slot] = isSceneCut(counts)
                    readGeneration[slot] = generation
                }
            }
        }
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        onRead(System.nanoTime() - startNs)
    }

    private fun setFilter(filter: Int) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /** Stops the worker (it finishes its current read, at most one fence wait), then frees the GL objects. */
    override fun close() {
        running = false
        worker?.interrupt()
        worker?.join(JOIN_TIMEOUT_MS)
        for (slot in textures.indices) {
            val fence = synchronized(lock) { takeFence(slot) }
            if (fence != NO_FENCE) GLES30.glDeleteSync(fence)
        }
        GLES30.glDeleteTextures(textures.size, textures, 0)
        GLES30.glDeleteTextures(verdicts.size, verdicts, 0)
        GLES30.glDeleteBuffers(buffers.size, buffers, 0)
    }
}
