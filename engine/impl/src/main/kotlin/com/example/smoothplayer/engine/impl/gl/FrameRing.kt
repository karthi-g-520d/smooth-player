package com.example.smoothplayer.engine.impl.gl

import android.opengl.GLES30
import com.example.smoothplayer.engine.impl.threading.GlThread

private const val SLOT_COUNT = 4
private const val NO_SLOT = -1
private const val COARSE_FROM_FINE = 2

/**
 * A short ring of decoded frames, held as RGBA8 textures next to two levels of motion luma. The fine level is
 * 1/[motionLumaDivisor] of the frame: 1/4 up to 720p, smaller above that so it stays near 320x180 (see
 * [motionLumaDivisor]). The coarse level is half the fine one. The copy pass writes a slot each time a frame arrives,
 * and the blend pass reads two slots. Motion estimation reads both luma levels.
 *
 * Each slot keeps its presentation time in microseconds, so the blend can pick the frame either side of a content time.
 * Every slot is the content's visible size before rotation. The display pass applies rotation and letterboxing.
 *
 * Only the GL thread may use this class. [release] frees the textures and the framebuffer.
 */
@GlThread
internal class FrameRing(val width: Int, val height: Int) : AutoCloseable {
    val motionLumaDivisor: Int = motionLumaDivisor(width, height)
    val fineWidth: Int = ceilDiv(width, motionLumaDivisor)
    val fineHeight: Int = ceilDiv(height, motionLumaDivisor)
    val coarseWidth: Int = ceilDiv(fineWidth, COARSE_FROM_FINE)
    val coarseHeight: Int = ceilDiv(fineHeight, COARSE_FROM_FINE)

    private val frames = IntArray(SLOT_COUNT)
    private val lumaFine = IntArray(SLOT_COUNT)
    private val lumaCoarse = IntArray(SLOT_COUNT)
    private val ptsUs = LongArray(SLOT_COUNT) { EMPTY_SLOT_PTS_US }
    private val framebuffer: Int
    private var nextSlot = 0

    /** The slot written most recently, or [NO_SLOT] before the first frame. */
    var latest: Int = NO_SLOT
        private set

    init {
        for (slot in 0 until SLOT_COUNT) {
            frames[slot] = createTexture(GLES30.GL_RGBA8, GLES30.GL_RGBA, width, height)
            lumaFine[slot] = createTexture(GLES30.GL_R8, GLES30.GL_RED, fineWidth, fineHeight)
            lumaCoarse[slot] = createTexture(GLES30.GL_R8, GLES30.GL_RED, coarseWidth, coarseHeight)
        }
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        // Checked once per format. A format that cannot be rendered to would otherwise fail silently on every frame.
        requireComplete(frames[0])
        requireComplete(lumaFine[0])
        requireComplete(lumaCoarse[0])
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Presentation time of [slot] in microseconds, or [EMPTY_SLOT_PTS_US]. The array is shared, so do not modify it. */
    fun ptsArray(): LongArray = ptsUs

    fun pts(slot: Int): Long = ptsUs[slot]

    fun frameTexture(slot: Int): Int = frames[slot]

    /** The slot written just before [slot]. Its PTS is [EMPTY_SLOT_PTS_US] until that many frames have arrived. */
    fun previous(slot: Int): Int = (slot - 1 + SLOT_COUNT) % SLOT_COUNT

    fun lumaFineTexture(slot: Int): Int = lumaFine[slot]

    fun lumaCoarseTexture(slot: Int): Int = lumaCoarse[slot]

    /**
     * Claims the next slot for a frame at [presentationUs] and returns its index. Frames arrive in order,
     * so this reuses the oldest slot.
     */
    fun acquire(presentationUs: Long): Int {
        val slot = nextSlot
        nextSlot = (nextSlot + 1) % SLOT_COUNT
        ptsUs[slot] = presentationUs
        latest = slot
        return slot
    }

    /** Points the framebuffer at [texture] and sets the viewport to [viewportWidth] by [viewportHeight]. */
    fun attach(texture: Int, viewportWidth: Int, viewportHeight: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun close() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES30.glDeleteTextures(SLOT_COUNT, frames, 0)
        GLES30.glDeleteTextures(SLOT_COUNT, lumaFine, 0)
        GLES30.glDeleteTextures(SLOT_COUNT, lumaCoarse, 0)
    }

    private fun requireComplete(texture: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "framebuffer incomplete: 0x${status.toString(16)}" }
    }

    private companion object {
        /** Linear filtering with clamped edges: the blend and luma passes read between texels, and the edges repeat. */
        fun createTexture(internalFormat: Int, format: Int, width: Int, height: Int): Int {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                internalFormat,
                width,
                height,
                0,
                format,
                GLES30.GL_UNSIGNED_BYTE,
                null,
            )
            return ids[0]
        }
    }
}
