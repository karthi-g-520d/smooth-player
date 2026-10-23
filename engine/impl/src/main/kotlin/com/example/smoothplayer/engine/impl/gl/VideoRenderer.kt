package com.example.smoothplayer.engine.impl.gl

import android.content.res.AssetManager
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.smoothplayer.engine.api.model.InterpolationMode
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.api.model.VideoInfo
import com.example.smoothplayer.engine.impl.clock.MasterClock
import com.example.smoothplayer.engine.impl.interp.GpuMotionSearch
import com.example.smoothplayer.engine.impl.pacer.VsyncPacer
import com.example.smoothplayer.engine.impl.telemetry.FrameTelemetry
import com.example.smoothplayer.engine.impl.telemetry.TraceNames
import com.example.smoothplayer.engine.impl.telemetry.trace
import com.example.smoothplayer.engine.impl.threading.GlThread
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "FI/GL"
private const val GL_THREAD_NAME = "FI-GL"
private const val SETUP_TIMEOUT_MS = 2_000L
private const val RELEASE_TIMEOUT_MS = 500L
private const val BYTES_PER_FLOAT = 4
private const val FLOATS_PER_VERTEX = 4
private const val VERTEX_COUNT = 4
private const val ATTR_POSITION = 0
private const val ATTR_UV = 1
private const val UNIFORM_ROTATION = 0
private const val UNIFORM_TEX_MATRIX = 2
// Fragment-stage uniforms share location 6 across programs. Each program has its own uniforms, so the luma pass
// (u_texel, a vec2) and the blend pass (u_t, a float) can both use it.
private const val UNIFORM_TEXEL = 6
private const val UNIFORM_T = 6
private const val UNIFORM_DEBUG_VIEW = 7
private const val UV_OFFSET_BYTES = 2 * BYTES_PER_FLOAT
private const val VERTEX_SHADER_ASSET = "shaders/quad.vert"
private const val COPY_SHADER_ASSET = "shaders/oes_to_rgba.frag"
private const val LUMA_QUARTER_SHADER_ASSET = "shaders/luma_quarter.frag"
private const val LUMA_EIGHTH_SHADER_ASSET = "shaders/luma_eighth.frag"
private const val BLEND_SHADER_ASSET = "shaders/blend.frag"
private const val TEXTURE_MATRIX_SIZE = 16
private const val ROTATION_MATRIX_SIZE = 4
private const val QUAD_HALF_EXTENT = 1f
private const val NANOS_PER_MICRO = 1_000L
private const val TARGET_REFRESH_HZ = 60f

/**
 * Diagnostic switch for the GPU present timer. Turning it off removes the per-draw timer query and its
 * disjoint check, so a trace shows whether those calls are what makes the draw slow.
 */
internal const val GPU_TIMERS_ENABLED = true

/**
 * Diagnostic switch for the luma passes. They run on every arriving frame, and nothing reads them until motion
 * estimation exists. Turning them off isolates their cost on the GL thread.
 */
internal const val LUMA_PASSES_ENABLED = true

/**
 * Diagnostic switch for motion estimation. When on, each arriving frame's 1/4 luma is matched against the frame before
 * it on the GPU. Nothing shows the vectors yet. Needs [LUMA_PASSES_ENABLED].
 *
 * Off by default: the wavefront search measured 26 ms per 1080p pair on the vivo V2334 (GpuMotionSearchBenchmark),
 * which dropped playback cadence to about 70%. Turn it on only to profile until a faster search lands.
 */
internal const val MOTION_SEARCH_ENABLED = false

/**
 * Diagnostic switch for the present draw. When on, each vsync still clears and swaps the window, but skips the blend
 * draw, so the picture is black. If misses drop with it on, the draw is the cause. If they stay, the cause is the
 * swap waiting on the compositor.
 */
internal const val EMPTY_PRESENT_ENABLED = false

/**
 * Diagnostic view for the blend shader. 0 is the normal picture. 1 shows where the two blended frames differ, amplified,
 * so motion is bright. 2 shows the blend phase as grey, so a changing grey proves the phase reaches the shader.
 */
internal const val BLEND_DEBUG_VIEW = 0

// x, y, u, v per vertex, as a triangle strip covering clip space.
private val QUAD = floatArrayOf(
    -QUAD_HALF_EXTENT, -QUAD_HALF_EXTENT, 0f, 0f,
    QUAD_HALF_EXTENT, -QUAD_HALF_EXTENT, 1f, 0f,
    -QUAD_HALF_EXTENT, QUAD_HALF_EXTENT, 0f, 1f,
    QUAD_HALF_EXTENT, QUAD_HALF_EXTENT, 1f, 1f,
)

private val IDENTITY_2 = floatArrayOf(1f, 0f, 0f, 1f)
private val IDENTITY_4 = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f,
    0f, 0f, 0f, 1f,
)

/**
 * GL side of playback. Owns one EGL context on the FI-GL thread, an external (OES) texture wrapped in a
 * [SurfaceTexture] for the decoder to write into, and an optional window surface to draw into.
 *
 * Each arriving frame is copied out of the OES texture into a [FrameRing] slot, with its luma at 1/4 and 1/8, and
 * its motion from the frame before is estimated on the 1/4 luma.
 * On every vsync the window is drawn from the ring: the newest frame in [InterpolationMode.Duplicate], or the two
 * frames around the screen's content time in [InterpolationMode.Blend].
 *
 * Every GL call runs on the FI-GL thread. The public methods only post work to it, except
 * [awaitFrameSurface], which the decoder thread calls and which blocks that thread only.
 */
@GlThread
internal class VideoRenderer(
    private val assets: AssetManager,
    private val telemetry: FrameTelemetry,
    initialMode: InterpolationMode,
) {
    private val glThread = HandlerThread(GL_THREAD_NAME).apply { start() }
    private val handler = Handler(glThread.looper)
    private val frameSurfaceReady = CountDownLatch(1)

    // Written on the GL thread during setup; read by the decoder thread after frameSurfaceReady.
    @Volatile private var frameSurface: Surface? = null
    @Volatile private var setupError: Throwable? = null

    // Set from any thread, read on the GL thread when drawing.
    @Volatile private var contentWidth = 0
    @Volatile private var contentHeight = 0
    @Volatile private var contentRotation = 0
    @Volatile private var mode = initialMode

    /** Runs on the GL thread after each arriving frame is in the ring. The decoder releases the next frame from it. */
    @Volatile var onFrameCopied: (() -> Unit)? = null

    /** True while the blend mode is chosen. Read from the decoder thread, which releases frames one ahead when it is. */
    val isBlending: Boolean get() = mode == InterpolationMode.Blend

    // GL-thread-only state.
    private var core: EglCore? = null
    private var windowSurface: EGLSurface? = null
    private var windowWidth = 0
    private var windowHeight = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var textureId = 0
    private var copyProgram = 0
    private var lumaQuarterProgram = 0
    private var lumaEighthProgram = 0
    private var blendProgram = 0
    private var vertexArray = 0
    private var vertexBuffer = 0
    private var ring: FrameRing? = null
    private var gpuTimers: GpuTimerRing? = null
    private var motionSearch: GpuMotionSearch? = null
    private var motionTimers: GpuTimerRing? = null
    @Volatile private var pacer: VsyncPacer? = null
    private var paced = false
    private val texMatrix = FloatArray(TEXTURE_MATRIX_SIZE)
    private val rotationMatrix = FloatArray(ROTATION_MATRIX_SIZE)
    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * BYTES_PER_FLOAT)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD)
            position(0)
        }

    init {
        handler.post { setUpOnGlThread() }
    }

    /** Blocks the calling (decoder) thread until the texture-backed Surface exists, then returns it. */
    fun awaitFrameSurface(): Surface {
        check(frameSurfaceReady.await(SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "GL setup timed out" }
        setupError?.let { throw IllegalStateException("GL setup failed", it) }
        return checkNotNull(frameSurface)
    }

    /** Sets the geometry used for letterboxing and rotation. Takes effect on the next drawn frame. */
    fun setContent(info: VideoInfo) {
        contentWidth = info.width
        contentHeight = info.height
        contentRotation = info.rotationDegrees
    }

    /** Chooses how the next refreshes pick their frames. Takes effect at the next vsync. */
    fun setInterpolationMode(newMode: InterpolationMode) {
        mode = newMode
    }

    /** Draws into [surface]. Redraws the newest frame straight away if one exists, so a rotation does not go black. */
    fun attachWindow(surface: Surface, width: Int, height: Int) {
        handler.post {
            val eglCore = core ?: return@post
            requestRefreshRate(surface)
            detachWindowOnGlThread()
            windowSurface = eglCore.createWindowSurface(surface)
            windowWidth = width
            windowHeight = height
            presentLatest(presentNs = null)
        }
    }

    /** Stops drawing and destroys the window surface. Blocks until that has happened, so the Surface can be released. */
    fun detachWindow() {
        awaitGlThread { detachWindowOnGlThread() }
    }

    /**
     * Starts drawing on the display's vsync. The decoder then releases each frame when the screen is about to show it.
     * [clock] is the playback's master clock.
     */
    fun startPacing(clock: MasterClock, onTick: () -> Unit) {
        handler.post {
            paced = true
            pacer?.start(clock, onTick)
        }
    }

    /** Stops vsync drawing. Frames are then drawn as they arrive, and the decoder falls back to the clock. */
    fun stopPacing() {
        handler.post {
            paced = false
            pacer?.stop()
        }
    }

    /**
     * Stops vsync drawing and waits until the GL thread has done so. Call this before releasing the clock's audio
     * track, because a vsync callback that is already queued would otherwise read the released track.
     */
    fun stopPacingAndWait() {
        awaitGlThread {
            paced = false
            pacer?.stopNow()
        }
    }

    /** The content time that the next vsync will show, or null when not pacing. Read from the decoder thread. */
    fun releaseTargetUs(): Micros? = pacer?.releaseTargetUs

    /** Draws the chosen frames on this vsync, with presentation time set so the compositor shows it at [presentNs]. */
    @GlThread
    private fun onVsync(presentNs: Long, periodNs: Long) {
        if (windowSurface == null) return
        val contentUs = pacer?.displayContentUsOrNull(presentNs)
        val pick = chooseFrames(contentUs) ?: return
        present(presentNs, pick)
        telemetry.onVsyncDraw(checkNotNull(ring).pts(pick.a), periodNs, contentUs)
    }

    /**
     * The two frames to show at [contentUs]. In duplicate mode, or while the clock has no reference point, that is the
     * newest frame. In blend mode it is the pair around the content time, falling back to the newest frame when no
     * frame is at or before that time.
     */
    @GlThread
    private fun chooseFrames(contentUs: Long?): BlendPick? {
        val frames = ring ?: return null
        if (isBlending && contentUs != null) {
            pickBlendPair(frames.ptsArray(), contentUs, MAX_BLEND_GAP_US)?.let { return it }
        }
        return latestPick()
    }

    /** The newest frame, shown alone. Null before the first frame arrives. */
    @GlThread
    private fun latestPick(): BlendPick? {
        val latest = ring?.latest ?: return null
        if (latest < 0) return null
        return BlendPick(latest, latest, 0f)
    }

    /** Draws the newest frame without pacing, for example when the picture must follow a seek while paused. */
    @GlThread
    private fun presentLatest(presentNs: Long?) {
        val pick = latestPick() ?: return
        present(presentNs, pick)
    }

    /** Asks the compositor for the target refresh rate. Only the device's own choice of rate decides what is shown. */
    @GlThread
    private fun requestRefreshRate(surface: Surface) {
        // The three-argument setFrameRate is API 31. The two-argument form from API 30 is not used here.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        surface.setFrameRate(
            TARGET_REFRESH_HZ,
            Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
            Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
        )
    }

    /** Releases all GL resources and stops the thread. Blocks briefly. Call after the decoder is released. */
    fun close() {
        awaitGlThread { releaseOnGlThread() }
        glThread.quitSafely()
    }

    private fun awaitGlThread(block: () -> Unit) {
        val done = CountDownLatch(1)
        handler.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        done.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @GlThread
    private fun setUpOnGlThread() {
        try {
            val eglCore = EglCore.createPbuffer()
            core = eglCore
            eglCore.makeCurrent()
            textureId = createExternalTexture()
            val texture = SurfaceTexture(textureId)
            texture.setOnFrameAvailableListener(SurfaceTexture.OnFrameAvailableListener { onFrameAvailable() }, handler)
            surfaceTexture = texture
            frameSurface = Surface(texture)
            val vertexSource = loadAsset(VERTEX_SHADER_ASSET)
            copyProgram = linkProgram(vertexSource, loadAsset(COPY_SHADER_ASSET))
            lumaQuarterProgram = linkProgram(vertexSource, loadAsset(LUMA_QUARTER_SHADER_ASSET))
            lumaEighthProgram = linkProgram(vertexSource, loadAsset(LUMA_EIGHTH_SHADER_ASSET))
            blendProgram = linkProgram(vertexSource, loadAsset(BLEND_SHADER_ASSET))
            createVertexState()
            gpuTimers = if (GPU_TIMERS_ENABLED) GpuTimerRing.createIfSupported() else null
            motionSearch = if (MOTION_SEARCH_ENABLED && LUMA_PASSES_ENABLED) createMotionSearch() else null
            motionTimers = if (GPU_TIMERS_ENABLED && motionSearch != null) GpuTimerRing.createIfSupported() else null
            pacer = VsyncPacer(handler) { presentNs, periodNs -> onVsync(presentNs, periodNs) }
        } catch (e: Exception) {
            // Reported to the decoder thread by awaitFrameSurface, instead of crashing the GL thread.
            setupError = e
        } finally {
            frameSurfaceReady.countDown()
        }
    }

    /** Null when the device has no GLES 3.1 or a shader fails to build. Playback then goes on without it. */
    @GlThread
    private fun createMotionSearch(): GpuMotionSearch? =
        try {
            GpuMotionSearch.createIfSupported(assets).also {
                if (it == null) Log.w(TAG, "no GLES 3.1, motion search off")
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "motion search off", e)
            null
        }

    @GlThread
    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    @GlThread
    private fun createVertexState() {
        val arrays = IntArray(1)
        GLES30.glGenVertexArrays(1, arrays, 0)
        vertexArray = arrays[0]
        GLES30.glBindVertexArray(vertexArray)

        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        vertexBuffer = buffers[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, QUAD.size * BYTES_PER_FLOAT, quadBuffer, GLES30.GL_STATIC_DRAW)

        val stride = FLOATS_PER_VERTEX * BYTES_PER_FLOAT
        GLES30.glEnableVertexAttribArray(ATTR_POSITION)
        GLES30.glVertexAttribPointer(ATTR_POSITION, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(ATTR_UV)
        GLES30.glVertexAttribPointer(ATTR_UV, 2, GLES30.GL_FLOAT, false, stride, UV_OFFSET_BYTES)

        GLES30.glBindVertexArray(0)
    }

    @GlThread
    private fun onFrameAvailable() {
        val texture = surfaceTexture ?: return
        // updateTexImage needs a current context. The pbuffer is enough, the window is not required.
        core?.makeCurrent()
        trace(TraceNames.GL_UPDATE) { texture.updateTexImage() }
        texture.getTransformMatrix(texMatrix)
        try {
            copyToRing(texture.timestamp / NANOS_PER_MICRO)
        } finally {
            // Always signalled, so the decoder is never left waiting for a copy that did not happen.
            onFrameCopied?.invoke()
        }
        // While pacing, the vsync callback draws. Otherwise draw now, as when paused.
        if (!paced) presentLatest(presentNs = null)
    }

    /**
     * Copies the frame now in the OES texture into the next ring slot, then derives its luma at 1/4 and 1/8.
     * Runs once per arriving frame, never per vsync, so the cost does not grow with the refresh rate.
     */
    @GlThread
    private fun copyToRing(ptsUs: Long) {
        val width = contentWidth
        val height = contentHeight
        if (width <= 0 || height <= 0) return
        val frames = ringFor(width, height)
        val slot = frames.acquire(ptsUs)

        trace(TraceNames.GL_COPY) {
            frames.attach(frames.frameTexture(slot), width, height)
            GLES30.glUseProgram(copyProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            drawFullScreenQuad(rotation = IDENTITY_2, uvMatrix = texMatrix)
        }

        if (LUMA_PASSES_ENABLED) trace(TraceNames.GL_LUMA) {
            frames.attach(frames.lumaQuarterTexture(slot), frames.quarterWidth, frames.quarterHeight)
            GLES30.glUseProgram(lumaQuarterProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frames.frameTexture(slot))
            GLES30.glUniform2f(UNIFORM_TEXEL, 1f / width, 1f / height)
            drawFullScreenQuad(rotation = IDENTITY_2, uvMatrix = IDENTITY_4)

            frames.attach(frames.lumaEighthTexture(slot), frames.eighthWidth, frames.eighthHeight)
            GLES30.glUseProgram(lumaEighthProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frames.lumaQuarterTexture(slot))
            drawFullScreenQuad(rotation = IDENTITY_2, uvMatrix = IDENTITY_4)
        }

        if (LUMA_PASSES_ENABLED) estimateMotion(frames, slot)
    }

    /**
     * Matches [slot]'s 1/4 luma against the frame before it. Runs once per arriving frame, so the vectors of a pair
     * serve every refresh between the two frames.
     */
    @GlThread
    private fun estimateMotion(frames: FrameRing, slot: Int) {
        val search = motionSearch ?: return
        val previous = frames.previous(slot)
        if (frames.pts(previous) == EMPTY_SLOT_PTS_US) return
        trace(TraceNames.INTERP_ME) {
            val pass = {
                search.run(
                    frames.lumaQuarterTexture(previous),
                    frames.lumaQuarterTexture(slot),
                    frames.quarterWidth,
                    frames.quarterHeight,
                )
            }
            val timers = motionTimers
            if (timers != null) timers.time(pass, telemetry::onMotionGpu) else pass()
        }
    }

    /** The ring for this content size. Replaced when the size changes, for example when a new file is opened. */
    @GlThread
    private fun ringFor(width: Int, height: Int): FrameRing {
        val current = ring
        if (current != null && current.width == width && current.height == height) return current
        current?.close()
        return FrameRing(width, height).also { ring = it }
    }

    /**
     * Draws the chosen frames to the window. Viewport, clear and framebuffer are set here, so no state from a copy
     * pass leaks into the window draw.
     */
    @GlThread
    private fun present(presentNs: Long?, pick: BlendPick) {
        val eglCore = core ?: return
        val target = windowSurface ?: return
        val frames = ring ?: return
        eglCore.makeCurrent(target)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        val rotation = contentRotation
        val quarterTurn = Math.floorMod(rotation, 180) != 0
        val displayWidth = if (quarterTurn) contentHeight else contentWidth
        val displayHeight = if (quarterTurn) contentWidth else contentHeight
        val viewport = fitViewport(windowWidth, windowHeight, displayWidth, displayHeight)
        GLES30.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val timers = gpuTimers
        if (!EMPTY_PRESENT_ENABLED) trace(TraceNames.GL_DRAW) {
            if (timers != null) {
                timers.time({ drawBlend(frames, pick, rotation) }, telemetry::onPresentGpu)
            } else {
                drawBlend(frames, pick, rotation)
            }
        }

        trace(TraceNames.GL_SWAP) { eglCore.swapBuffers(target, presentNs) }
    }

    @GlThread
    private fun drawBlend(frames: FrameRing, pick: BlendPick, rotation: Int) {
        GLES30.glUseProgram(blendProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frames.frameTexture(pick.a))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frames.frameTexture(pick.b))
        GLES30.glUniform1f(UNIFORM_T, pick.t)
        GLES30.glUniform1i(UNIFORM_DEBUG_VIEW, BLEND_DEBUG_VIEW)
        drawFullScreenQuad(rotation = clockwiseRotation(rotation, rotationMatrix), uvMatrix = IDENTITY_4)
    }

    /**
     * One full-screen quad with the given transforms. Every pass calls this, so each one sets the vertex state and
     * both uniforms it needs. Nothing is inherited from the pass before.
     */
    @GlThread
    private fun drawFullScreenQuad(rotation: FloatArray, uvMatrix: FloatArray) {
        GLES30.glBindVertexArray(vertexArray)
        GLES30.glUniformMatrix2fv(UNIFORM_ROTATION, 1, false, rotation, 0)
        GLES30.glUniformMatrix4fv(UNIFORM_TEX_MATRIX, 1, false, uvMatrix, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
    }

    @GlThread
    private fun detachWindowOnGlThread() {
        val eglCore = core ?: return
        val target = windowSurface ?: return
        eglCore.makeCurrent()
        eglCore.destroySurface(target)
        windowSurface = null
    }

    @GlThread
    private fun releaseOnGlThread() {
        detachWindowOnGlThread()
        surfaceTexture?.setOnFrameAvailableListener(null)
        frameSurface?.release()
        surfaceTexture?.release()
        frameSurface = null
        surfaceTexture = null
        val eglCore = core ?: return
        eglCore.makeCurrent()
        ring?.close()
        ring = null
        GLES30.glDeleteProgram(copyProgram)
        GLES30.glDeleteProgram(lumaQuarterProgram)
        GLES30.glDeleteProgram(lumaEighthProgram)
        GLES30.glDeleteProgram(blendProgram)
        GLES30.glDeleteBuffers(1, intArrayOf(vertexBuffer), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
        GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        pacer?.stop()
        paced = false
        gpuTimers?.close()
        gpuTimers = null
        motionTimers?.close()
        motionTimers = null
        motionSearch?.close()
        motionSearch = null
        eglCore.close()
        core = null
    }

    private fun loadAsset(path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }

    @GlThread
    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val linked = GLES30.glCreateProgram()
        GLES30.glAttachShader(linked, vertex)
        GLES30.glAttachShader(linked, fragment)
        GLES30.glLinkProgram(linked)
        val status = IntArray(1)
        GLES30.glGetProgramiv(linked, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "program link failed: ${GLES30.glGetProgramInfoLog(linked)}" }
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        return linked
    }

    @GlThread
    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { "shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}" }
        return shader
    }
}
