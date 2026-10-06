package com.example.smoothplayer.engine.impl.gl

import android.opengl.GLES30
import com.example.smoothplayer.engine.impl.threading.GlThread

private const val SLOT_COUNT = 4
private const val NO_SLOT = -1
private const val LUMA_QUARTER_DIVISOR = 4
private const val LUMA_EIGHTH_FROM_QUARTER = 2

/**
 * A short ring of decoded frames, held as RGBA8 textures next to their luma at 1/4 and 1/8 resolution. The copy pass
 * writes a slot each time a frame arrives, and the blend pass reads two slots. Motion estimation will read the luma.
 *
 * Each slot keeps its presentation time in microseconds, so the blend can pick the frame either side of a content time.
 * Every slot is the content's visible size before rotation. The display pass applies rotation and letterboxing.
 *
 * Only the GL thread may use this class. [release] frees the textures and the framebuffer.
 */
@GlThread
internal class FrameRing(val width: Int, val height: Int) : AutoCloseable {
    val quarterWidth: Int = ceilDiv(width, LUMA_QUARTER_DIVISOR)
    val quarterHeight: Int = ceilDiv(height, LUMA_QUARTER_DIVISOR)
    val eighthWidth: Int = ceilDiv(quarterWidth, LUMA_EIGHTH_FROM_QUARTER)
    val eighthHeight: Int = ceilDiv(quarterHeight, LUMA_EIGHTH_FROM_QUARTER)

    private val frames = IntArray(SLOT_COUNT)
    private val lumaQuarter = IntArray(SLOT_COUNT)
    private val lumaEighth = IntArray(SLOT_COUNT)
    private val ptsUs = LongArray(SLOT_COUNT) { EMPTY_SLOT_PTS_US }
    private val framebuffer: Int
    private var nextSlot = 0

    /** The slot written most recently, or [NO_SLOT] before the first frame. */
    var latest: Int = NO_SLOT
        private set

    init {
        for (slot in 0 until SLOT_COUNT) {
            frames[slot] = createTexture(GLES30.GL_RGBA8, GLES30.GL_RGBA, width, height)
            lumaQuarter[slot] = createTexture(GLES30.GL_R8, GLES30.GL_RED, quarterWidth, quarterHeight)
            lumaEighth[slot] = createTexture(GLES30.GL_R8, GLES30.GL_RED, eighthWidth, eighthHeight)
        }
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        // Checked once per format. A format that cannot be rendered to would otherwise fail silently on every frame.
        requireComplete(frames[0])
        requireComplete(lumaQuarter[0])
        requireComplete(lumaEighth[0])
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Presentation time of [slot] in microseconds, or [EMPTY_SLOT_PTS_US]. The array is shared, so do not modify it. */
    fun ptsArray(): LongArray = ptsUs

    fun pts(slot: Int): Long = ptsUs[slot]

    fun frameTexture(slot: Int): Int = frames[slot]

    fun lumaQuarterTexture(slot: Int): Int = lumaQuarter[slot]

    fun lumaEighthTexture(slot: Int): Int = lumaEighth[slot]

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
        GLES30.glDeleteTextures(SLOT_COUNT, lumaQuarter, 0)
        GLES30.glDeleteTextures(SLOT_COUNT, lumaEighth, 0)
    }

    private fun requireComplete(texture: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "framebuffer incomplete: 0x${status.toString(16)}" }
    }

    private companion object {
        fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

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
