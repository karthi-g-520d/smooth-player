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
import com.example.smoothplayer.engine.api.model.DebugView
import com.example.smoothplayer.engine.api.model.InterpolationMode
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.api.model.Nanos
import com.example.smoothplayer.engine.api.model.VideoInfo
import com.example.smoothplayer.engine.api.model.WarpFallback
import com.example.smoothplayer.engine.impl.clock.MasterClock
import com.example.smoothplayer.engine.impl.interp.GpuMotionSearch
import com.example.smoothplayer.engine.impl.interp.MotionFields
import com.example.smoothplayer.engine.impl.interp.WarpGovernor
import com.example.smoothplayer.engine.impl.pacer.VsyncPacer
import com.example.smoothplayer.engine.impl.telemetry.FrameTelemetry
import com.example.smoothplayer.engine.impl.telemetry.InBetweenReason
import com.example.smoothplayer.engine.impl.telemetry.PipelineStage
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
// Fragment-stage uniforms share locations 6 and 7 across programs. Each program has its own uniforms, so the luma
// pass (u_texel, a vec2, and u_taps, an int) and the blend pass (u_t, a float, and u_debugView) can both use them.
private const val UNIFORM_TEXEL = 6
private const val UNIFORM_TAPS = 7
private const val UNIFORM_T = 6
private const val UNIFORM_DEBUG_VIEW = 7
// The motion view's fragment uniforms, at 6 to 11 in its own program.
private const val UNIFORM_FRAME_SIZE = 6
private const val UNIFORM_LUMA_PX = 7
private const val UNIFORM_BLOCK_LUMA = 8
private const val UNIFORM_MAX_VECTOR = 9
private const val UNIFORM_MAX_RESIDUAL = 10
private const val UNIFORM_VIEW = 11
// The warp's fragment uniforms, at 6 to 10 in its own program.
private const val UNIFORM_WARP_T = 6
private const val UNIFORM_LUMA_TO_UV = 7
private const val UNIFORM_FIELD_SCALE = 8
private const val UNIFORM_WARP_WEIGHT = 9
private const val UNIFORM_WARP_DEBUG_VIEW = 10
private const val WARP_VIEW_PICTURE = 0
private const val WARP_VIEW_CONFIDENCE = 1
private const val FIELD_TEXTURE_UNIT = 2
private const val VERDICT_TEXTURE_UNIT = 3
/** The phase the confidence view uses when only one frame is on screen. */
private const val HALF_PHASE = 0.5f
private const val MOTION_VIEW_VECTORS = 1
private const val MOTION_VIEW_MATCH_ERROR = 2
/** The residual the match-error view shows as fully red: a best-match SAD as large as the block's own detail. */
private const val MAX_VIEW_RESIDUAL = 1f
private const val UV_OFFSET_BYTES = 2 * BYTES_PER_FLOAT
private const val VERTEX_SHADER_ASSET = "shaders/quad.vert"
private const val COPY_SHADER_ASSET = "shaders/oes_to_rgba.frag"
private const val LUMA_FINE_SHADER_ASSET = "shaders/luma_downsample.frag"
private const val LUMA_COARSE_SHADER_ASSET = "shaders/luma_half.frag"
private const val BLEND_SHADER_ASSET = "shaders/blend.frag"
private const val MOTION_VIEW_SHADER_ASSET = "shaders/motion_view.frag"
private const val WARP_SHADER_ASSET = "shaders/warp.frag"
private const val TEXTURE_MATRIX_SIZE = 16
private const val ROTATION_MATRIX_SIZE = 4
private const val QUAD_HALF_EXTENT = 1f
private const val NANOS_PER_MICRO = 1_000L
private const val TARGET_REFRESH_HZ = 60f
private const val NO_PENDING_SLOT = -1

/**
 * Time a motion search needs before the next vsync to run on arrival: 3.8 ms p50 at 1080p on the vivo, plus the
 * stats pass and room for a slow pair.
 */
private const val SEARCH_ON_ARRIVAL_BUDGET_NS = 8_000_000L
private const val NO_SLOT = -1

/** How one refresh of [InterpolationMode.MotionCompensated] is drawn. */
private const val DRAW_PLAIN = 0 // one frame, or the phase is on a frame: nothing to interpolate
private const val DRAW_WARP = 1
private const val DRAW_BLEND = 2 // no field for this pair: never searched
private const val DRAW_SEARCH_PENDING = 5 // no field yet: its search runs after the next swap
private const val DRAW_HOLD = 3 // the heat or GPU fallback: show the earlier frame
private const val DRAW_LOW_CONFIDENCE = 4 // too little has matched lately: blend, but still hold at a scene cut

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
 * Switch for motion estimation. When on, each arriving frame's motion luma is matched against the frame
 * before it on the GPU. The motion-compensated mode and the motion views need it. Needs [LUMA_PASSES_ENABLED].
 *
 * Off by default until GpuMotionSearchBenchmark shows the search within the Step 6 bar on the target devices. The
 * first, raster-order search took 26 ms per 1080p pair on the vivo V2334 and dropped playback cadence to about 70%.
 * The coarse-to-fine search on uncapped 1/4 luma took 6 to 8 ms and gave 93.9% cadence. The motion luma is now capped
 * at 320x180 (see [motionLumaDivisor]), where the search measured about 4 ms. Run straight after the swap, Blend-mode
 * playback with it on averaged 95.9% cadence on the vivo (thermal 0.83), against 89.9% with it off (thermal 0.98).
 */
internal const val MOTION_SEARCH_ENABLED = true

/**
 * Diagnostic switch for the GPU timer around motion estimation. Off, because on the vivo V2334 (MediaTek) the timer
 * query around compute never returns, and the timer ring then reuses queries the driver still holds. Turn it on only on
 * a device whose driver reports compute timings.
 */
internal const val MOTION_GPU_TIMER_ENABLED = false

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
 * Each arriving frame is copied out of the OES texture into a [FrameRing] slot, with its motion luma at two levels
 * (see [FrameRing]). Its motion from the frame before is estimated on that luma straight after the next swap, so the
 * search never sits in the GPU queue ahead of a present (see [runPendingMotion]). Each slot keeps its own field and
 * pair stats in [MotionFields].
 * On every vsync the window is drawn from the ring: the newest frame in [InterpolationMode.Duplicate], or the two
 * frames around the screen's content time in [InterpolationMode.Blend] and [InterpolationMode.MotionCompensated]. The
 * last warps the two frames along the pair's motion where it is trusted, and otherwise blends or holds (see
 * [decideWarp] and warp.frag).
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
    @Volatile private var debugView = DebugView.Off

    /** Runs on the GL thread after each arriving frame is in the ring. The decoder releases the next frame from it. */
    @Volatile var onFrameCopied: (() -> Unit)? = null

    /**
     * True while the mode draws between two frames. Read from the decoder thread, which releases frames one ahead when
     * it is.
     */
    val usesFramePairs: Boolean get() = mode != InterpolationMode.Duplicate

    // Posted by onSeek, read on the GL thread before the next draw.
    @Volatile private var seekPending = false

    // GL-thread-only state.
    private var core: EglCore? = null
    private var windowSurface: EGLSurface? = null
    private var windowWidth = 0
    private var windowHeight = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var textureId = 0
    private var copyProgram = 0
    private var lumaFineProgram = 0
    private var lumaCoarseProgram = 0
    private var blendProgram = 0
    private var motionViewProgram = 0
    private var warpProgram = 0
    private var vertexArray = 0
    private var vertexBuffer = 0
    private var ring: FrameRing? = null
    private var gpuTimers: GpuTimerRing? = null
    private var motionSearch: GpuMotionSearch? = null
    private var motionTimers: GpuTimerRing? = null
    // One motion field per ring slot, so every pair in the ring keeps its own vectors. Null without motion search.
    private var fields: MotionFields? = null
    private val governor = WarpGovernor()
    private val onPresentGpu: (Nanos) -> Unit = { duration ->
        telemetry.onPresentGpu(duration)
        governor.onPresentGpu(duration.value, System.nanoTime())
    }
    // The newest ring slot whose motion is still to be estimated, and its pts so a recycled slot is not searched.
    private var pendingMotionSlot = NO_PENDING_SLOT
    private var pendingMotionPtsUs = EMPTY_SLOT_PTS_US
    // A benchmark run's stage probe: the next search and the next warped draw are timed after a timed copy.
    private var probeMotion = false
    private var probeWarp = false
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

    /**
     * Forgets the motion fields and the warp's fallback state, because the frames on either side of a seek are not a
     * pair. Takes effect before the next draw.
     */
    fun onSeek() {
        seekPending = true
    }

    /**
     * Chooses a diagnostic view in place of the picture. Takes effect at the next vsync. The motion views need motion
     * estimation; without it, the normal picture shows.
     */
    fun setDebugView(view: DebugView) {
        debugView = view
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
        governor.onThermal(telemetry.thermalHeadroom, System.nanoTime())
        present(presentNs, pick)
        // A held frame (scene cut, heat or GPU fallback) is a draw of A, the same as Duplicate's.
        telemetry.onVsyncDraw(checkNotNull(ring).pts(pick.a), periodNs, contentUs)
        telemetry.onWarpFallback(if (mode == InterpolationMode.MotionCompensated) governor.fallback else null)
    }

    /**
     * The two frames to show at [contentUs]. In duplicate mode, or while the clock has no reference point, that is the
     * newest frame. In blend mode it is the pair around the content time, falling back to the newest frame when no
     * frame is at or before that time.
     */
    @GlThread
    private fun chooseFrames(contentUs: Long?): BlendPick? {
        val frames = ring ?: return null
        if (usesFramePairs && contentUs != null) {
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
            lumaFineProgram = linkProgram(vertexSource, loadAsset(LUMA_FINE_SHADER_ASSET))
            lumaCoarseProgram = linkProgram(vertexSource, loadAsset(LUMA_COARSE_SHADER_ASSET))
            blendProgram = linkProgram(vertexSource, loadAsset(BLEND_SHADER_ASSET))
            motionViewProgram = linkProgram(vertexSource, loadAsset(MOTION_VIEW_SHADER_ASSET))
            warpProgram = linkProgram(vertexSource, loadAsset(WARP_SHADER_ASSET))
            createVertexState()
            gpuTimers = if (GPU_TIMERS_ENABLED) GpuTimerRing.createIfSupported() else null
            motionSearch = if (MOTION_SEARCH_ENABLED && LUMA_PASSES_ENABLED) createMotionSearch() else null
            motionTimers = if (MOTION_GPU_TIMER_ENABLED && motionSearch != null) GpuTimerRing.createIfSupported() else null
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
     * Copies the frame now in the OES texture into the next ring slot, then derives its two motion-luma levels.
     * Runs once per arriving frame, never per vsync, so the cost does not grow with the refresh rate.
     */
    @GlThread
    private fun copyToRing(ptsUs: Long) {
        val width = contentWidth
        val height = contentHeight
        if (width <= 0 || height <= 0) return
        val frames = ringFor(width, height)
        val slot = frames.acquire(ptsUs)
        fields?.invalidate(slot)
        val probing = telemetry.takeStageSample(System.nanoTime())
        if (probing) {
            probeMotion = true
            probeWarp = true
        }
        val copyStartNs = if (probing) startTiming() else 0L

        trace(TraceNames.GL_COPY) {
            frames.attach(frames.frameTexture(slot), width, height)
            GLES30.glUseProgram(copyProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            drawFullScreenQuad(rotation = IDENTITY_2, uvMatrix = texMatrix)
        }

        if (LUMA_PASSES_ENABLED) trace(TraceNames.GL_LUMA) {
            frames.attach(frames.lumaFineTexture(slot), frames.fineWidth, frames.fineHeight)
            GLES30.glUseProgram(lumaFineProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frames.frameTexture(slot))
            GLES30.glUniform2f(UNIFORM_TEXEL, 1f / width, 1f / height)
            GLES30.glUniform1i(UNIFORM_TAPS, motionLumaTaps(frames.motionLumaDivisor))
            drawFullScreenQuad(rotation = IDENTITY_2, uvMatrix = IDENTITY_4)

            frames.attach(frames.lumaCoarseTexture(slot), frames.coarseWidth, frames.coarseHeight)
            GLES30.glUseProgram(lumaCoarseProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frames.lumaFineTexture(slot))
            drawFullScreenQuad(rotation = IDENTITY_2, uvMatrix = IDENTITY_4)
        }
        if (probing) telemetry.onStage(PipelineStage.CopyAndLuma, finishTiming(copyStartNs))

        if (LUMA_PASSES_ENABLED && motionSearch != null) {
            pendingMotionSlot = slot
            pendingMotionPtsUs = ptsUs
            // The decoder releases each frame right after a vsync, for the draw at the next one, which is often the
            // in-between that needs this pair. Searched after that draw's swap, the pair arrived too late and blended
            // (24% of in-betweens on the vivo). So the search runs now when it finishes well before the next vsync's
            // draw; otherwise it waits for the swap as before, so it never sits on the GPU ahead of a present.
            if (searchFitsBeforeNextVsync()) runPendingMotion()
        }
    }

    /** True when a search queued now ends, with a margin, before the next vsync's draw. False when not pacing. */
    @GlThread
    private fun searchFitsBeforeNextVsync(): Boolean {
        if (!paced) return false
        val nextVsyncNs = pacer?.nextVsyncNs() ?: return false
        return nextVsyncNs - System.nanoTime() >= SEARCH_ON_ARRIVAL_BUDGET_NS
    }

    /**
     * Estimates the motion of the newest arrived frame, if any is waiting. Called straight after a swap, so the search
     * gets the GPU for the rest of the refresh period, and flushed so it starts now instead of being batched in front
     * of the next draw. When two frames arrive between swaps only the newer pair is searched, since the field holds
     * one pair anyway.
     */
    @GlThread
    private fun runPendingMotion() {
        val slot = pendingMotionSlot
        if (slot == NO_PENDING_SLOT) return
        pendingMotionSlot = NO_PENDING_SLOT
        val frames = ring ?: return
        if (frames.pts(slot) != pendingMotionPtsUs) return
        // While the heat or GPU fallback holds frames, nothing reads the fields, so the search is skipped to spare the
        // GPU. The motion views still need it.
        if (mode == InterpolationMode.MotionCompensated && governor.holdsFrames && debugView == DebugView.Off) return
        if (estimateMotion(frames, slot)) GLES30.glFlush()
    }

    /**
     * Matches [slot]'s luma against the frame before it, coarse to fine on the two motion-luma levels. Runs once per
     * arriving frame, so the vectors of a pair serve every refresh between the two frames. The vectors are in fine
     * motion-luma pixels: multiply by [FrameRing.motionLumaDivisor] for source pixels. False when nothing was searched.
     */
    @GlThread
    private fun estimateMotion(frames: FrameRing, slot: Int): Boolean {
        val search = motionSearch ?: return false
        val slotFields = fields ?: return false
        val previous = frames.previous(slot)
        val prevPts = frames.pts(previous)
        val nextPts = frames.pts(slot)
        // Not a pair: the first frame, or the two sides of a seek.
        if (prevPts == EMPTY_SLOT_PTS_US || nextPts <= prevPts || nextPts - prevPts > MAX_BLEND_GAP_US) return false
        val timed = probeMotion
        probeMotion = false
        val startNs = if (timed) startTiming() else 0L
        trace(TraceNames.INTERP_ME) {
            val pass = {
                search.run(
                    frames.lumaFineTexture(previous),
                    frames.lumaFineTexture(slot),
                    frames.lumaCoarseTexture(previous),
                    frames.lumaCoarseTexture(slot),
                    frames.fineWidth,
                    frames.fineHeight,
                    fieldTexture = slotFields.texture(slot),
                    statsBuffer = slotFields.statsBuffer(slot),
                    verdictTexture = slotFields.verdictTexture(slot),
                )
            }
            val timers = motionTimers
            if (timers != null) timers.time(pass, telemetry::onMotionGpu) else pass()
            slotFields.markSearched(slot, prevPts, nextPts)
        }
        if (timed) telemetry.onStage(PipelineStage.Motion, finishTiming(startNs))
        return true
    }

    /** The ring for this content size. Replaced when the size changes, for example when a new file is opened. */
    @GlThread
    private fun ringFor(width: Int, height: Int): FrameRing {
        val current = ring
        if (current != null && current.width == width && current.height == height) return current
        current?.close()
        fields?.close()
        fields = null
        pendingMotionSlot = NO_PENDING_SLOT
        val frames = FrameRing(width, height)
        ring = frames
        if (motionSearch != null) {
            fields = MotionFields(
                frames.slotCount,
                GpuMotionSearch.columnsFor(frames.fineWidth),
                GpuMotionSearch.rowsFor(frames.fineHeight),
                sharedContextOrNull(),
                telemetry::onStatsPoll,
            )
        }
        governor.reset()
        return frames
    }

    /**
     * A context for the stats reader, or null when the driver refuses a second one. The warp then still runs, and
     * still holds frames at scene cuts, but the low-confidence fallback and the cut count go quiet.
     */
    @GlThread
    private fun sharedContextOrNull(): SharedEglContext? =
        try {
            core?.createSharedContext()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "no shared context, pair stats off", e)
            null
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
        if (seekPending) forgetPairs()
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
        val view = debugView
        val drawStartNs = System.nanoTime()
        if (!EMPTY_PRESENT_ENABLED) trace(TraceNames.GL_DRAW) {
            val draw = { drawPicture(frames, pick, rotation, view) }
            if (timers != null) timers.time(draw, onPresentGpu) else draw()
        }
        telemetry.onDrawCpu(System.nanoTime() - drawStartNs)

        trace(TraceNames.GL_SWAP) { eglCore.swapBuffers(target, presentNs) }
        runPendingMotion()
        accountPairs()
    }

    /**
     * Feeds the stats the worker has read since the last call to the low-confidence fallback and the overlay. Never
     * waits. A cut is one pair, not a trend, so it does not move the fallback. Pairs read in another mode are
     * dropped, so Blend playback counts no cuts.
     */
    @GlThread
    private fun accountPairs() {
        val slotFields = fields ?: return
        while (true) {
            val slot = slotFields.takeReadSlot()
            if (slot < 0) return
            if (mode != InterpolationMode.MotionCompensated) continue
            val stats = slotFields.stats(slot)
            if (slotFields.isCut(slot)) {
                telemetry.onSceneCut(stats.lowConfidenceFraction, stats.histogramDistance)
            } else {
                governor.onPair(stats)
            }
        }
    }

    /** Forgets every field and the fallback state, after a seek. */
    @GlThread
    private fun forgetPairs() {
        seekPending = false
        fields?.invalidateAll()
        pendingMotionSlot = NO_PENDING_SLOT
        governor.reset()
    }

    /** The normal picture in the chosen mode, or the chosen diagnostic view. */
    @GlThread
    private fun drawPicture(frames: FrameRing, pick: BlendPick, rotation: Int, view: DebugView) {
        when (view) {
            DebugView.MotionVectors, DebugView.MatchError -> {
                val search = motionSearch
                val slot = fieldSlotForView(frames, pick)
                if (search != null && slot != NO_SLOT) {
                    val motionView = if (view == DebugView.MotionVectors) MOTION_VIEW_VECTORS else MOTION_VIEW_MATCH_ERROR
                    drawMotionView(frames, search, slot, pick, rotation, motionView)
                    return
                }
            }
            DebugView.WarpConfidence -> if (fields != null) {
                drawConfidenceView(frames, pick, rotation)
                return
            }
            DebugView.Off -> Unit
        }
        if (mode != InterpolationMode.MotionCompensated) {
            drawBlend(frames.frameTexture(pick.a), frames.frameTexture(pick.b), pick.t, rotation)
            return
        }
        val decision = decideWarp(frames, pick)
        val inBetween = pick.a != pick.b && pick.t > 0f
        if (inBetween) telemetry.onInBetweenDraw(inBetweenReason(decision))
        when {
            decision == DRAW_WARP && inBetween -> {
                val timed = probeWarp
                probeWarp = false
                val startNs = if (timed) startTiming() else 0L
                drawWarp(frames, pick.a, pick.b, pick.t, rotation, weight = 1f, view = WARP_VIEW_PICTURE)
                if (timed) telemetry.onStage(PipelineStage.Warp, finishTiming(startNs))
            }
            // Still through the warp, at weight 0, so a scene cut holds its frame instead of blending two shots.
            decision == DRAW_LOW_CONFIDENCE && inBetween ->
                drawWarp(frames, pick.a, pick.b, pick.t, rotation, weight = 0f, view = WARP_VIEW_PICTURE)
            decision == DRAW_HOLD -> drawBlend(frames.frameTexture(pick.a), frames.frameTexture(pick.a), 0f, rotation)
            else -> drawBlend(frames.frameTexture(pick.a), frames.frameTexture(pick.b), pick.t, rotation)
        }
    }

    private fun inBetweenReason(decision: Int): InBetweenReason = when (decision) {
        DRAW_WARP -> InBetweenReason.Warped
        DRAW_SEARCH_PENDING -> InBetweenReason.SearchPending
        DRAW_LOW_CONFIDENCE -> InBetweenReason.LowConfidence
        DRAW_HOLD -> InBetweenReason.Held
        else -> InBetweenReason.NotSearched
    }

    /**
     * How [pick] is drawn in the motion-compensated mode. The heat and GPU fallbacks come first, since they stop the
     * search. Then the pair needs its own field; without one it blends, as Blend mode does. The search is queued
     * before any draw of its pair, so the GPU runs it first, and the scene-cut verdict comes with it: warp.frag holds
     * the earlier frame for a cut. Too many low-confidence pairs in a row blend. Nothing here waits for the CPU to
     * read the pair's stats.
     */
    @GlThread
    private fun decideWarp(frames: FrameRing, pick: BlendPick): Int {
        if (pick.a == pick.b) return DRAW_PLAIN
        if (governor.holdsFrames) return DRAW_HOLD
        val slotFields = fields ?: return DRAW_BLEND
        val slot = pick.b
        val nextPts = frames.pts(slot)
        if (!slotFields.holds(slot, frames.pts(pick.a), nextPts)) {
            val pending = pendingMotionSlot == slot && pendingMotionPtsUs == nextPts
            return if (pending) DRAW_SEARCH_PENDING else DRAW_BLEND
        }
        return when (governor.fallback) {
            WarpFallback.None -> DRAW_WARP
            WarpFallback.LowConfidence -> DRAW_LOW_CONFIDENCE
            WarpFallback.Thermal, WarpFallback.GpuBudget -> DRAW_HOLD
        }
    }

    /**
     * The slot whose field the motion views show: the pair on screen when its field exists, otherwise the newest
     * searched pair. [NO_SLOT] when neither has one.
     */
    @GlThread
    private fun fieldSlotForView(frames: FrameRing, pick: BlendPick): Int {
        val slotFields = fields ?: return NO_SLOT
        if (holdsOwnPair(slotFields, frames, pick.b)) return pick.b
        val latest = frames.latest
        return if (latest >= 0 && holdsOwnPair(slotFields, frames, latest)) latest else NO_SLOT
    }

    /** True when [slot]'s field is the motion from the frame before it in the ring to the frame in it. */
    @GlThread
    private fun holdsOwnPair(slotFields: MotionFields, frames: FrameRing, slot: Int): Boolean =
        slotFields.holds(slot, frames.pts(frames.previous(slot)), frames.pts(slot))

    /** The motion field of [slot]'s pair over a grey copy of the newer frame of [pick]. */
    @GlThread
    private fun drawMotionView(
        frames: FrameRing,
        search: GpuMotionSearch,
        slot: Int,
        pick: BlendPick,
        rotation: Int,
        view: Int,
    ) {
        GLES30.glUseProgram(motionViewProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frames.frameTexture(pick.b))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, checkNotNull(fields).texture(slot))
        GLES30.glUniform2f(UNIFORM_FRAME_SIZE, frames.width.toFloat(), frames.height.toFloat())
        GLES30.glUniform1f(UNIFORM_LUMA_PX, frames.motionLumaDivisor.toFloat())
        GLES30.glUniform1f(UNIFORM_BLOCK_LUMA, search.blockSize.toFloat())
        GLES30.glUniform1f(UNIFORM_MAX_VECTOR, search.maxVector.toFloat())
        GLES30.glUniform1f(UNIFORM_MAX_RESIDUAL, MAX_VIEW_RESIDUAL)
        GLES30.glUniform1i(UNIFORM_VIEW, view)
        drawFullScreenQuad(rotation = clockwiseRotation(rotation, rotationMatrix), uvMatrix = IDENTITY_4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /**
     * The warp's per-pixel confidence for the pair on screen, in any mode. When only one frame is on screen (Duplicate
     * mode, or a refresh that lands on a frame), it shows the pair that ends at that frame, halfway. The whole-picture
     * fallbacks (heat, GPU budget, low confidence) are left out, so the view always shows what the field can do; only a
     * pair without a finished field, or a scene cut, shows all red.
     */
    @GlThread
    private fun drawConfidenceView(frames: FrameRing, pick: BlendPick, rotation: Int) {
        val slotFields = checkNotNull(fields)
        val paired = pick.a != pick.b
        val b = pick.b
        val a = if (paired) pick.a else frames.previous(b)
        val t = if (paired) pick.t else HALF_PHASE
        // A scene cut shows all red too: warp.frag reads the GPU's verdict.
        val trusted = slotFields.holds(b, frames.pts(a), frames.pts(b))
        drawWarp(frames, a, b, t, rotation, weight = if (trusted) 1f else 0f, view = WARP_VIEW_CONFIDENCE)
    }

    /**
     * The motion-compensated frame at [pick]'s phase, from the field of its newer slot (see warp.frag). [weight] 0
     * makes it the plain blend, which the confidence view uses to show a pair the warp does not draw.
     */
    @GlThread
    private fun drawWarp(frames: FrameRing, a: Int, b: Int, t: Float, rotation: Int, weight: Float, view: Int) {
        val slotFields = checkNotNull(fields)
        val divisor = frames.motionLumaDivisor.toFloat()
        val blockSource = divisor * GpuMotionSearch.BLOCK_SIDE
        GLES30.glUseProgram(warpProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frames.frameTexture(a))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frames.frameTexture(b))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + FIELD_TEXTURE_UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, slotFields.texture(b))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + VERDICT_TEXTURE_UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, slotFields.verdictTexture(b))
        GLES30.glUniform1f(UNIFORM_WARP_T, t)
        GLES30.glUniform2f(UNIFORM_LUMA_TO_UV, divisor / frames.width, divisor / frames.height)
        GLES30.glUniform2f(
            UNIFORM_FIELD_SCALE,
            frames.width / (blockSource * slotFields.columns),
            frames.height / (blockSource * slotFields.rows),
        )
        GLES30.glUniform1f(UNIFORM_WARP_WEIGHT, weight)
        GLES30.glUniform1i(UNIFORM_WARP_DEBUG_VIEW, view)
        drawFullScreenQuad(rotation = clockwiseRotation(rotation, rotationMatrix), uvMatrix = IDENTITY_4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /**
     * Drains the GPU and returns the start time of a timed stage. Only a benchmark run's stage probe calls this: the
     * vivo's driver reports no timer queries around compute, so stages are timed by wall clock to glFinish instead.
     */
    @GlThread
    private fun startTiming(): Long {
        GLES30.glFinish()
        return System.nanoTime()
    }

    /** Waits for the stage started at [startNs] to finish on the GPU and returns its wall time. */
    @GlThread
    private fun finishTiming(startNs: Long): Long {
        GLES30.glFinish()
        return System.nanoTime() - startNs
    }

    /** [frameA] and [frameB] mixed at [t]. The same texture twice, or t = 0, shows one frame unchanged. */
    @GlThread
    private fun drawBlend(frameA: Int, frameB: Int, t: Float, rotation: Int) {
        GLES30.glUseProgram(blendProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameA)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameB)
        GLES30.glUniform1f(UNIFORM_T, t)
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
        fields?.close()
        fields = null
        GLES30.glDeleteProgram(copyProgram)
        GLES30.glDeleteProgram(lumaFineProgram)
        GLES30.glDeleteProgram(lumaCoarseProgram)
        GLES30.glDeleteProgram(blendProgram)
        GLES30.glDeleteProgram(motionViewProgram)
        GLES30.glDeleteProgram(warpProgram)
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
