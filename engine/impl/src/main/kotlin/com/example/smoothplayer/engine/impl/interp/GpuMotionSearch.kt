package com.example.smoothplayer.engine.impl.interp

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.GLES31
import com.example.smoothplayer.engine.impl.threading.GlThread
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val SEARCH_SHADER_ASSET = "shaders/motion_search.comp"
private const val MEDIAN_SHADER_ASSET = "shaders/motion_median.comp"
private const val SEARCH_WORKGROUP_SIZE = 64
private const val MEDIAN_WORKGROUP_SIDE = 8
private const val MIN_GLES_MAJOR = 3
private const val MIN_GLES_MINOR = 1
private const val MAX_LUMA = 255
private const val UINT_BITS = 32
// std430 layout of one block: ivec2 vector, vec2 subpixel, float residual, float pad.
private const val BYTES_PER_BLOCK = 24
private const val BINDING_PREV_LUMA = 0
private const val BINDING_NEXT_LUMA = 1
private const val BINDING_RAW_FIELD = 0
private const val BINDING_FILTERED_FIELD = 1
private const val BINDING_MOTION_IMAGE = 0
private const val LOC_PLANE_SIZE = 0
private const val LOC_GRID = 1
private const val LOC_WAVE = 2
private const val LOC_FIRST_ROW = 3

/**
 * The GPU form of [estimateMotion], as two GLES 3.1 compute passes: the block search (with its subpixel fit and
 * residual) and the 3x3 median. [estimateMotion] defines the result. Integer vectors match it exactly; subpixel
 * offsets and residuals match to float rounding.
 *
 * The search runs one dispatch per wavefront of blocks (see [wavefrontCount]), so each block sees the same causal
 * predictor as on the CPU. The median pass writes [motionTexture], which the warp will sample.
 *
 * Only the GL thread may use this class, with a GLES 3.1 context current. [close] frees its buffers and programs.
 */
@GlThread
internal class GpuMotionSearch private constructor(
    private val searchProgram: Int,
    private val medianProgram: Int,
) : AutoCloseable {
    private val buffers = IntArray(2)
    private var planeWidth = 0
    private var planeHeight = 0

    var columns: Int = 0
        private set
    var rows: Int = 0
        private set

    /** RGBA16F, one texel per block: the total vector in luma pixels in x and y, the residual in z. 0 before [run]. */
    var motionTexture: Int = 0
        private set

    /**
     * Estimates the motion from [prevLuma] to [nextLuma], two R8 textures of [width] by [height]. The result is in
     * [motionTexture] for later GL commands, and in [readBack] for the CPU.
     */
    fun run(prevLuma: Int, nextLuma: Int, width: Int, height: Int) {
        ensureSize(width, height)

        GLES31.glUseProgram(searchProgram)
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + BINDING_PREV_LUMA)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, prevLuma)
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + BINDING_NEXT_LUMA)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, nextLuma)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, BINDING_RAW_FIELD, buffers[RAW])
        GLES31.glUniform2i(LOC_PLANE_SIZE, width, height)
        GLES31.glUniform2i(LOC_GRID, columns, rows)
        for (wave in 0 until wavefrontCount(columns, rows)) {
            val firstRow = wavefrontFirstRow(wave, columns)
            val lastRow = wavefrontLastRow(wave, rows)
            if (lastRow < firstRow) continue
            GLES31.glUniform1i(LOC_WAVE, wave)
            GLES31.glUniform1i(LOC_FIRST_ROW, firstRow)
            GLES31.glDispatchCompute(lastRow - firstRow + 1, 1, 1)
            // The next wavefront reads this one's vectors for its predictors.
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        }

        GLES31.glUseProgram(medianProgram)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, BINDING_FILTERED_FIELD, buffers[FILTERED])
        GLES31.glBindImageTexture(
            BINDING_MOTION_IMAGE, motionTexture, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA16F,
        )
        GLES31.glUniform2i(LOC_GRID, columns, rows)
        GLES31.glDispatchCompute(ceilDiv(columns, MEDIAN_WORKGROUP_SIDE), ceilDiv(rows, MEDIAN_WORKGROUP_SIDE), 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

        // Nothing is left bound for the passes that follow.
        GLES31.glBindImageTexture(BINDING_MOTION_IMAGE, 0, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA16F)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, BINDING_RAW_FIELD, 0)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, BINDING_FILTERED_FIELD, 0)
    }

    /**
     * Copies the last [run]'s filtered field to the CPU. It waits for the GPU and allocates, so it is for tests and
     * debugging, never the per-frame path.
     */
    fun readBack(): MotionField {
        val field = MotionField(columns, rows)
        val size = columns * rows * BYTES_PER_BLOCK
        GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[FILTERED])
        val mapped = GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, size, GLES31.GL_MAP_READ_BIT)
        check(mapped is ByteBuffer) { "motion field map failed (GL error 0x${GLES31.glGetError().toString(16)})" }
        val bytes = mapped.order(ByteOrder.nativeOrder())
        for (index in 0 until columns * rows) {
            field.dx[index] = bytes.getInt()
            field.dy[index] = bytes.getInt()
            field.subX[index] = bytes.getFloat()
            field.subY[index] = bytes.getFloat()
            field.residual[index] = bytes.getFloat()
            bytes.getFloat()
        }
        GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        return field
    }

    override fun close() {
        releaseFieldStorage()
        GLES31.glDeleteProgram(searchProgram)
        GLES31.glDeleteProgram(medianProgram)
    }

    /** Sizes the field buffers and texture for a [width] by [height] plane. Only a new size reallocates. */
    private fun ensureSize(width: Int, height: Int) {
        if (width == planeWidth && height == planeHeight) return
        releaseFieldStorage()
        planeWidth = width
        planeHeight = height
        columns = ceilDiv(width, BLOCK_SIZE)
        rows = ceilDiv(height, BLOCK_SIZE)

        GLES31.glGenBuffers(buffers.size, buffers, 0)
        for (buffer in buffers) {
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer)
            GLES31.glBufferData(
                GLES31.GL_SHADER_STORAGE_BUFFER, columns * rows * BYTES_PER_BLOCK, null, GLES31.GL_DYNAMIC_COPY,
            )
        }
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)

        val ids = IntArray(1)
        GLES31.glGenTextures(1, ids, 0)
        motionTexture = ids[0]
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, motionTexture)
        GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES31.GL_RGBA16F, columns, rows)
        // Linear, so the warp can upsample the block field bilinearly.
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, 0)
    }

    private fun releaseFieldStorage() {
        if (motionTexture == 0) return
        GLES31.glDeleteBuffers(buffers.size, buffers, 0)
        GLES31.glDeleteTextures(1, intArrayOf(motionTexture), 0)
        buffers.fill(0)
        motionTexture = 0
        planeWidth = 0
        planeHeight = 0
    }

    companion object {
        private const val RAW = 0
        private const val FILTERED = 1
        private val CONFIG = MotionSearchConfig()
        private val BLOCK_SIZE = CONFIG.blockSize

        /**
         * Builds both programs for the default [MotionSearchConfig]. Null when the context is older than GLES 3.1.
         * Throws when a shader does not compile or link, with the driver's log.
         */
        fun createIfSupported(assets: AssetManager): GpuMotionSearch? {
            val version = IntArray(2)
            GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, version, 0)
            GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, version, 1)
            if (version[0] < MIN_GLES_MAJOR || (version[0] == MIN_GLES_MAJOR && version[1] < MIN_GLES_MINOR)) {
                return null
            }
            val defines = defines(CONFIG)
            val search = linkCompute(withDefines(loadAsset(assets, SEARCH_SHADER_ASSET), defines))
            val median = try {
                linkCompute(withDefines(loadAsset(assets, MEDIAN_SHADER_ASSET), defines))
            } catch (e: IllegalStateException) {
                GLES31.glDeleteProgram(search)
                throw e
            }
            return GpuMotionSearch(search, median)
        }

        /** The shader constants. Kotlin is their only source, so the two sides cannot drift. */
        private fun defines(config: MotionSearchConfig): String {
            val candidates = (2 * config.searchRange + 1) * (2 * config.searchRange + 1)
            val candidateBits = UINT_BITS - Integer.numberOfLeadingZeros(candidates - 1)
            // The search packs (cost, candidate index) into one uint, so the largest cost must fit above the index.
            // A candidate and the predictor are each within the range, so they differ by at most 2 * range per axis.
            val maxCost = config.blockSize * config.blockSize * MAX_LUMA +
                config.smoothnessWeight * 2 * (2 * config.searchRange)
            require(maxCost.toLong() shl candidateBits < (1L shl UINT_BITS)) { "cost does not fit beside the index" }
            return """
                #define BLOCK_SIZE ${config.blockSize}
                #define SEARCH_RANGE ${config.searchRange}
                #define SMOOTHNESS_WEIGHT ${config.smoothnessWeight}
                #define CANDIDATE_BITS $candidateBits
                #define SEARCH_WORKGROUP_SIZE $SEARCH_WORKGROUP_SIZE
                #define MEDIAN_WORKGROUP_SIDE $MEDIAN_WORKGROUP_SIDE
                #define BINDING_PREV_LUMA $BINDING_PREV_LUMA
                #define BINDING_NEXT_LUMA $BINDING_NEXT_LUMA
                #define BINDING_RAW_FIELD $BINDING_RAW_FIELD
                #define BINDING_FILTERED_FIELD $BINDING_FILTERED_FIELD
                #define BINDING_MOTION_IMAGE $BINDING_MOTION_IMAGE
                #define LOC_PLANE_SIZE $LOC_PLANE_SIZE
                #define LOC_GRID $LOC_GRID
                #define LOC_WAVE $LOC_WAVE
                #define LOC_FIRST_ROW $LOC_FIRST_ROW
            """.trimIndent()
        }

        /** Inserts [defines] after the `#version` line, which must stay first. */
        private fun withDefines(source: String, defines: String): String =
            source.substringBefore('\n') + "\n" + defines + "\n" + source.substringAfter('\n')

        private fun loadAsset(assets: AssetManager, path: String): String =
            assets.open(path).bufferedReader().use { it.readText() }

        private fun linkCompute(source: String): Int {
            val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES31.glShaderSource(shader, source)
            GLES31.glCompileShader(shader)
            val status = IntArray(1)
            GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES31.glGetShaderInfoLog(shader)
                GLES31.glDeleteShader(shader)
                error("compute shader compile failed: $log")
            }
            val program = GLES31.glCreateProgram()
            GLES31.glAttachShader(program, shader)
            GLES31.glLinkProgram(program)
            GLES31.glDeleteShader(shader)
            GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES31.glGetProgramInfoLog(program)
                GLES31.glDeleteProgram(program)
                error("compute program link failed: $log")
            }
            return program
        }

        private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
    }
}
