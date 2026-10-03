package com.example.smoothplayer.engine.impl.render

import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.Surface
import com.example.smoothplayer.engine.api.RenderTargetController
import com.example.smoothplayer.engine.impl.gl.EglCore
import com.example.smoothplayer.engine.impl.threading.GlThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val GL_THREAD_NAME = "FI-GL"
private const val COLOR_CYCLE_FRAMES = 256
private const val CLEAR_BLUE = 0.5f
private const val CLEAR_ALPHA = 1f

// Surface teardown must finish before surfaceDestroyed()/close() return to the caller — the
// system is free to release the native surface the moment that call returns.
private const val GL_THREAD_RELEASE_TIMEOUT_MS = 500L

/**
 * Hello GL: clears the surface to a colour that changes on every vsync.
 *
 * All EGL and GL state lives on one dedicated thread. Choreographer on that thread drives the frames,
 * so each callback draws one frame at the display's vsync.
 */
public class HelloGlRenderer : RenderTargetController<Surface> {
    private val glThread = HandlerThread(GL_THREAD_NAME).apply { start() }
    private val handler = Handler(glThread.looper)

    // The fields below are touched only on the GL thread.
    private var core: EglCore? = null
    private var windowSurface: EGLSurface? = null
    private var choreographer: Choreographer? = null
    private var frameCount = 0
    private val frameCallback = Choreographer.FrameCallback { drawFrame() }

    override fun onSurfaceChanged(surface: Surface, width: Int, height: Int) {
        handler.post { attach(surface, width, height) }
    }

    override fun onSurfaceDestroyed() {
        // Block the caller: once this returns, the system may release the native surface, so the
        // GL thread must have stopped touching it first.
        awaitGlThread { detachWindow() }
    }

    /** Releases EGL and GL resources and stops the GL thread. Safe to call more than once. */
    public fun close() {
        awaitGlThread {
            detachWindow()
            core?.close()
            core = null
        }
        glThread.quitSafely()
    }

    private fun awaitGlThread(block: () -> Unit) {
        val done = CountDownLatch(1)
        handler.post {
            block()
            done.countDown()
        }
        done.await(GL_THREAD_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @GlThread
    private fun attach(surface: Surface, width: Int, height: Int) {
        val eglCore = core ?: EglCore.createPbuffer().also { core = it }
        // Unbind any previous window surface before destroying it.
        detachWindow()
        val target = eglCore.createWindowSurface(surface)
        windowSurface = target
        eglCore.makeCurrent(target)
        GLES30.glViewport(0, 0, width, height)
        startFrames()
    }

    @GlThread
    private fun detachWindow() {
        choreographer?.removeFrameCallback(frameCallback)
        val eglCore = core ?: return
        val target = windowSurface ?: return
        eglCore.makeCurrent()
        eglCore.destroySurface(target)
        windowSurface = null
    }

    @GlThread
    private fun startFrames() {
        val source = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        source.removeFrameCallback(frameCallback)
        source.postFrameCallback(frameCallback)
    }

    @GlThread
    private fun drawFrame() {
        val eglCore = core ?: return
        val target = windowSurface ?: return
        // Ramp red up and green down over the cycle, so each vsync shows a visibly different colour.
        val phase = (frameCount % COLOR_CYCLE_FRAMES) / (COLOR_CYCLE_FRAMES - 1f)
        frameCount++
        GLES30.glClearColor(phase, 1f - phase, CLEAR_BLUE, CLEAR_ALPHA)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        eglCore.swapBuffers(target)
        choreographer?.postFrameCallback(frameCallback)
    }
}
