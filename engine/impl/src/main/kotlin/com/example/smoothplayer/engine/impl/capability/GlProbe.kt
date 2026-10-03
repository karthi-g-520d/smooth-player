package com.example.smoothplayer.engine.impl.capability

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import com.example.smoothplayer.engine.api.model.ComputeLimits
import com.example.smoothplayer.engine.api.model.GlCapabilities
import com.example.smoothplayer.engine.api.model.GlProbeResult
import com.example.smoothplayer.engine.impl.gl.EglCore
import com.example.smoothplayer.engine.impl.threading.GlThread

private const val UNKNOWN = "unknown"
private const val WORK_GROUP_AXES = 3

/** Reads GL limits. Runs only on the GL thread, inside a current context. */
@GlThread
internal object GlProbe {
    fun run(): GlProbeResult = EglCore.createPbuffer().use { core ->
        core.makeCurrent()
        GlProbeResult.Available(readCapabilities())
    }

    private fun readCapabilities(): GlCapabilities {
        val version = GLES20.glGetString(GLES20.GL_VERSION) ?: UNKNOWN
        val shadingLanguage = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION) ?: UNKNOWN
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: UNKNOWN
        val esMajor = getInt(GLES30.GL_MAJOR_VERSION)
        val esMinor = getInt(GLES30.GL_MINOR_VERSION)
        val extensionCount = getInt(GLES30.GL_NUM_EXTENSIONS)
        val extensions = (0 until extensionCount)
            .map { GLES30.glGetStringi(GLES30.GL_EXTENSIONS, it).orEmpty() }
            .sorted()
        glCheck("extension list")

        val supportsCompute = esMajor > 3 || (esMajor == 3 && esMinor >= 1)
        return GlCapabilities(
            version = version,
            shadingLanguageVersion = shadingLanguage,
            renderer = renderer,
            esMajor = esMajor,
            esMinor = esMinor,
            extensions = extensions,
            compute = if (supportsCompute) readComputeLimits() else null,
        )
    }

    private fun readComputeLimits(): ComputeLimits {
        val maxInvocations = getInt(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS)
        val workGroupSize = (0 until WORK_GROUP_AXES).map { axis ->
            val value = IntArray(1)
            GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, axis, value, 0)
            value[0]
        }
        val sharedMemory = getInt(GLES31.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE)
        glCheck("compute limits")
        return ComputeLimits(maxInvocations, workGroupSize, sharedMemory)
    }

    private fun getInt(name: Int): Int {
        val value = IntArray(1)
        GLES30.glGetIntegerv(name, value, 0)
        return value[0]
    }

    private fun glCheck(where: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "GL error 0x${error.toString(16)} while reading $where" }
    }
}
