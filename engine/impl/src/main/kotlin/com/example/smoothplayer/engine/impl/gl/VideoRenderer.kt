package com.example.smoothplayer.engine.impl.gl

import android.content.res.AssetManager
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.example.smoothplayer.engine.api.model.VideoInfo
import com.example.smoothplayer.engine.impl.threading.GlThread
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
private const val UV_OFFSET_BYTES = 2 * BYTES_PER_FLOAT
private const val VERTEX_SHADER_ASSET = "shaders/oes_passthrough.vert"
private const val FRAGMENT_SHADER_ASSET = "shaders/oes_passthrough.frag"
private const val TEXTURE_MATRIX_SIZE = 16
private const val ROTATION_MATRIX_SIZE = 4
private const val QUAD_HALF_EXTENT = 1f

// x, y, u, v per vertex, as a triangle strip covering clip space.
private val QUAD = floatArrayOf(
    -QUAD_HALF_EXTENT, -QUAD_HALF_EXTENT, 0f, 0f,
    QUAD_HALF_EXTENT, -QUAD_HALF_EXTENT, 1f, 0f,
    -QUAD_HALF_EXTENT, QUAD_HALF_EXTENT, 0f, 1f,
    QUAD_HALF_EXTENT, QUAD_HALF_EXTENT, 1f, 1f,
)

/**
 * GL side of playback. Owns one EGL context on the FI-GL thread, an external (OES) texture wrapped in a
 * [SurfaceTexture] for the decoder to write into, and an optional window surface to draw into.
 *
 * Every GL call runs on the FI-GL thread. The public methods only post work to it, except
 * [awaitFrameSurface], which the decoder thread calls and which blocks that thread only.
 */
@GlThread
internal class VideoRenderer(private val assets: AssetManager) {
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

    // GL-thread-only state.
    private var core: EglCore? = null
    private var windowSurface: EGLSurface? = null
    private var windowWidth = 0
    private var windowHeight = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var textureId = 0
    private var program = 0
    private var vertexArray = 0
    private var vertexBuffer = 0
    private var hasFrame = false
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

    /** Draws into [surface]. Redraws the last frame straight away if one exists, so a rotation does not go black. */
    fun attachWindow(surface: Surface, width: Int, height: Int) {
        handler.post {
            val eglCore = core ?: return@post
            detachWindowOnGlThread()
            windowSurface = eglCore.createWindowSurface(surface)
            windowWidth = width
            windowHeight = height
            if (hasFrame) drawFrame()
        }
    }

    /** Stops drawing and destroys the window surface. Blocks until that has happened, so the Surface can be released. */
    fun detachWindow() {
        awaitGlThread { detachWindowOnGlThread() }
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
            program = linkProgram(loadAsset(VERTEX_SHADER_ASSET), loadAsset(FRAGMENT_SHADER_ASSET))
            createVertexState()
        } catch (e: Exception) {
            // Reported to the decoder thread by awaitFrameSurface, instead of crashing the GL thread.
            setupError = e
        } finally {
            frameSurfaceReady.countDown()
        }
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
        texture.updateTexImage()
        texture.getTransformMatrix(texMatrix)
        hasFrame = true
        drawFrame()
    }

    @GlThread
    private fun drawFrame() {
        val eglCore = core ?: return
        val target = windowSurface ?: return
        eglCore.makeCurrent(target)

        val rotation = contentRotation
        val quarterTurn = Math.floorMod(rotation, 180) != 0
        val displayWidth = if (quarterTurn) contentHeight else contentWidth
        val displayHeight = if (quarterTurn) contentWidth else contentHeight
        val viewport = fitViewport(windowWidth, windowHeight, displayWidth, displayHeight)
        GLES30.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(program)
        GLES30.glBindVertexArray(vertexArray)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniformMatrix2fv(UNIFORM_ROTATION, 1, false, clockwiseRotation(rotation, rotationMatrix), 0)
        GLES30.glUniformMatrix4fv(UNIFORM_TEX_MATRIX, 1, false, texMatrix, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)

        eglCore.swapBuffers(target)
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
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteBuffers(1, intArrayOf(vertexBuffer), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
        GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
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
