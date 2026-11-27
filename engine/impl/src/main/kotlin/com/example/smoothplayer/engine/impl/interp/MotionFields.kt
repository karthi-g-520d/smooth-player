package com.example.smoothplayer.engine.impl.interp

import android.opengl.GLES30
import android.opengl.GLES31
import com.example.smoothplayer.engine.impl.threading.GlThread
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val NO_FENCE = 0L
private const val NO_PTS = Long.MIN_VALUE

/**
 * One motion field and one stats buffer per frame-ring slot. The field of a slot is the motion from the frame before it
 * to the frame in it, so every pair still in the ring keeps its own vectors and the warp never uses a newer pair's.
 *
 * Each slot moves through three states: empty, searched (the GPU has the work, behind a fence), and ready (the fence
 * signalled and the stats were read). The stats are read without waiting: [stats] polls the fence and returns null
 * until the GPU is done.
 *
 * Only the GL thread may use this class, with a GLES 3.1 context current. [close] frees everything.
 */
@GlThread
internal class MotionFields(slotCount: Int, val columns: Int, val rows: Int) : AutoCloseable {
    private val textures = IntArray(slotCount)
    private val buffers = IntArray(slotCount)
    private val fences = LongArray(slotCount)
    private val prevPts = LongArray(slotCount) { NO_PTS }
    private val nextPts = LongArray(slotCount) { NO_PTS }
    private val ready = BooleanArray(slotCount)
    private val lowConfidence = FloatArray(slotCount)
    private val histogram = FloatArray(slotCount)
    private val cut = BooleanArray(slotCount)

    init {
        GLES30.glGenTextures(slotCount, textures, 0)
        for (texture in textures) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            // Immutable storage, so the median pass can bind it as an image.
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, columns, rows)
            // Linear, so the warp upsamples the block field bilinearly.
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glGenBuffers(slotCount, buffers, 0)
        for (buffer in buffers) {
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer)
            GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, GpuMotionSearch.STATS_BYTES, null, GLES30.GL_DYNAMIC_READ)
        }
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
    }

    fun texture(slot: Int): Int = textures[slot]

    fun statsBuffer(slot: Int): Int = buffers[slot]

    /** Forgets [slot]'s field, for example because a new frame was written into the slot. */
    fun invalidate(slot: Int) {
        deleteFence(slot)
        prevPts[slot] = NO_PTS
        nextPts[slot] = NO_PTS
        ready[slot] = false
    }

    /** Forgets every field, for a seek. */
    fun invalidateAll() {
        for (slot in textures.indices) invalidate(slot)
    }

    /**
     * Records that the search for the pair ([prevPtsUs], [nextPtsUs]) into [slot] was just queued, and puts a fence
     * behind it. Call straight after [GpuMotionSearch.run].
     */
    fun markSearched(slot: Int, prevPtsUs: Long, nextPtsUs: Long) {
        deleteFence(slot)
        fences[slot] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        prevPts[slot] = prevPtsUs
        nextPts[slot] = nextPtsUs
        ready[slot] = false
    }

    /** True when [slot] holds the field of exactly the pair ([prevPtsUs], [nextPtsUs]). It may still be on the GPU. */
    fun holds(slot: Int, prevPtsUs: Long, nextPtsUs: Long): Boolean =
        prevPts[slot] == prevPtsUs && nextPts[slot] == nextPtsUs && prevPtsUs != NO_PTS

    /**
     * True once the GPU has finished [slot]'s pair and its stats were read. Never waits: the first call after the fence
     * signals maps the 16-byte buffer once, and later calls only check a flag. Cheap enough for every refresh.
     */
    fun statsReady(slot: Int): Boolean {
        if (ready[slot]) return true
        if (prevPts[slot] == NO_PTS) return false
        val fence = fences[slot]
        if (fence == NO_FENCE) return false
        val status = GLES30.glClientWaitSync(fence, 0, 0L)
        if (status != GLES30.GL_ALREADY_SIGNALED && status != GLES30.GL_CONDITION_SATISFIED) return false
        deleteFence(slot)
        ready[slot] = readStats(slot)
        return ready[slot]
    }

    /** True when [slot]'s pair spans a scene cut (see [isSceneCut]). Only valid once [statsReady] is true. */
    fun isCut(slot: Int): Boolean = cut[slot]

    /** The stats of [slot]'s pair. Only valid once [statsReady] is true. Allocates, so call it once per pair. */
    fun stats(slot: Int): PairStats = PairStats(lowConfidence[slot], histogram[slot])

    /**
     * Maps the stats buffer. The map returns a new direct buffer object each time, so this allocates a little once per
     * pair (about 30 times a second), never per refresh.
     */
    private fun readStats(slot: Int): Boolean {
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[slot])
        val mapped = GLES30.glMapBufferRange(
            GLES31.GL_SHADER_STORAGE_BUFFER, 0, GpuMotionSearch.STATS_BYTES, GLES30.GL_MAP_READ_BIT,
        )
        val ok = mapped is ByteBuffer
        if (mapped is ByteBuffer) {
            val bytes = mapped.order(ByteOrder.nativeOrder())
            val counts = PairStats.fromCounts(
                texturedBlocks = bytes.getInt(0),
                lowConfidenceBlocks = bytes.getInt(Int.SIZE_BYTES),
                pixels = bytes.getInt(2 * Int.SIZE_BYTES),
                histogramDiff = bytes.getInt(3 * Int.SIZE_BYTES),
            )
            lowConfidence[slot] = counts.lowConfidenceFraction
            histogram[slot] = counts.histogramDistance
            cut[slot] = isSceneCut(counts)
            GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        }
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        return ok
    }

    private fun deleteFence(slot: Int) {
        if (fences[slot] == NO_FENCE) return
        GLES30.glDeleteSync(fences[slot])
        fences[slot] = NO_FENCE
    }

    override fun close() {
        for (slot in textures.indices) deleteFence(slot)
        GLES30.glDeleteTextures(textures.size, textures, 0)
        GLES30.glDeleteBuffers(buffers.size, buffers, 0)
    }
}
