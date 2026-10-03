package com.example.smoothplayer.engine.impl.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import com.example.smoothplayer.engine.impl.threading.GlThread

private const val GLES_CLIENT_VERSION = 3
private const val CHANNEL_BITS = 8
private const val PBUFFER_SIZE = 1

/** Owns one EGL display, context and 1x1 pbuffer. Create and use it only on the GL thread. */
@GlThread
internal class EglCore private constructor(
    private val display: EGLDisplay,
    private val context: EGLContext,
    private val surface: EGLSurface,
) : AutoCloseable {

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { eglFailure("eglMakeCurrent") }
    }

    override fun close() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        EGL14.eglReleaseThread()
    }

    companion object {
        /** Creates a GLES 3.x context with an offscreen pbuffer, so no window surface is needed. */
        @GlThread
        fun createPbuffer(): EglCore {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { eglFailure("eglGetDisplay") }
            check(EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)) { eglFailure("eglInitialize") }

            val config = chooseConfig(display)
            val context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, GLES_CLIENT_VERSION, EGL14.EGL_NONE),
                0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { eglFailure("eglCreateContext") }

            try {
                val surface = EGL14.eglCreatePbufferSurface(
                    display,
                    config,
                    intArrayOf(EGL14.EGL_WIDTH, PBUFFER_SIZE, EGL14.EGL_HEIGHT, PBUFFER_SIZE, EGL14.EGL_NONE),
                    0,
                )
                check(surface != EGL14.EGL_NO_SURFACE) { eglFailure("eglCreatePbufferSurface") }
                return EglCore(display, context, surface)
            } catch (e: IllegalStateException) {
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                throw e
            }
        }

        private fun chooseConfig(display: EGLDisplay): EGLConfig {
            val attributes = intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_RED_SIZE, CHANNEL_BITS,
                EGL14.EGL_GREEN_SIZE, CHANNEL_BITS,
                EGL14.EGL_BLUE_SIZE, CHANNEL_BITS,
                EGL14.EGL_ALPHA_SIZE, CHANNEL_BITS,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val ok = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0)
            check(ok && count[0] > 0) { eglFailure("eglChooseConfig") }
            return checkNotNull(configs[0])
        }

        private fun eglFailure(call: String): String =
            "$call failed (EGL error 0x${EGL14.eglGetError().toString(16)})"
    }
}
