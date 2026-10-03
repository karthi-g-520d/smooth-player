package com.example.smoothplayer.engine.api.model

import com.example.smoothplayer.engine.api.model.CapabilityCheckId.COMPUTE_INVOCATIONS
import com.example.smoothplayer.engine.api.model.CapabilityCheckId.COMPUTE_SHARED_MEMORY
import com.example.smoothplayer.engine.api.model.CapabilityCheckId.ESSL3_EXTERNAL_IMAGE
import com.example.smoothplayer.engine.api.model.CapabilityCheckId.GLES_31
import com.example.smoothplayer.engine.api.model.CapabilityCheckId.H264_HW_1080P60
import com.example.smoothplayer.engine.api.model.CapabilityCheckId.THERMAL_API
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityChecksTest {
    private val essl3 = "GL_OES_EGL_image_external_essl3"

    private fun caps(
        major: Int = 3,
        minor: Int = 2,
        invocations: Int = 1024,
        sharedBytes: Int = 32 * 1024,
        extensions: List<String> = listOf(essl3),
    ) = GlCapabilities(
        version = "OpenGL ES 3.2",
        shadingLanguageVersion = "OpenGL ES GLSL ES 3.20",
        renderer = "Test GPU",
        esMajor = major,
        esMinor = minor,
        extensions = extensions,
        compute = ComputeLimits(invocations, listOf(1024, 1024, 64), sharedBytes),
    )

    private fun report(
        gl: GlProbeResult = GlProbeResult.Available(caps()),
        decoders: List<DecoderInfo> = emptyList(),
        thermal: Double? = null,
    ) = CapabilityReport(
        generatedAtMillis = 0L,
        device = DeviceInfo("Test", "Device", 36),
        gl = gl,
        displayModes = emptyList(),
        activeDisplayModeId = null,
        decoders = decoders,
        thermalHeadroom = thermal,
    )

    private fun CapabilityReport.check(id: CapabilityCheckId): CapabilityCheck = checks().first { it.id == id }

    @Test
    fun `GLES 3_1 and newer pass and 3_0 fails`() {
        assertTrue(caps(major = 3, minor = 1).supportsEs31)
        assertTrue(caps(major = 3, minor = 2).supportsEs31)
        assertTrue(caps(major = 4, minor = 0).supportsEs31)
        assertFalse(caps(major = 3, minor = 0).supportsEs31)
    }

    @Test
    fun `compute invocations threshold is inclusive at 128`() {
        assertFalse(report(GlProbeResult.Available(caps(invocations = 127))).check(COMPUTE_INVOCATIONS).passed)
        assertTrue(report(GlProbeResult.Available(caps(invocations = 128))).check(COMPUTE_INVOCATIONS).passed)
    }

    @Test
    fun `shared memory threshold is inclusive at 16 KB`() {
        val justBelow = report(GlProbeResult.Available(caps(sharedBytes = 16 * 1024 - 1)))
        val exact = report(GlProbeResult.Available(caps(sharedBytes = 16 * 1024)))
        assertFalse(justBelow.check(COMPUTE_SHARED_MEMORY).passed)
        assertTrue(exact.check(COMPUTE_SHARED_MEMORY).passed)
    }

    @Test
    fun `essl3 external image extension is required`() {
        assertTrue(report().check(ESSL3_EXTERNAL_IMAGE).passed)
        val missing = report(GlProbeResult.Available(caps(extensions = emptyList())))
        assertFalse(missing.check(ESSL3_EXTERNAL_IMAGE).passed)
    }

    @Test
    fun `missing GL context fails every GL check and keeps the reason`() {
        val result = report(GlProbeResult.Unavailable("eglChooseConfig failed"))
        listOf(GLES_31, ESSL3_EXTERNAL_IMAGE, COMPUTE_INVOCATIONS, COMPUTE_SHARED_MEMORY).forEach { id ->
            assertFalse(result.check(id).passed)
        }
        assertEquals("eglChooseConfig failed", result.check(GLES_31).detail)
    }

    @Test
    fun `H264 hardware check needs a hardware decoder that handles 1080p60`() {
        val software = DecoderInfo("c2.android.avc.decoder", "video/avc", hardwareAccelerated = false, supports1080p60 = true)
        val hardware = DecoderInfo("c2.qti.avc.decoder", "video/avc", hardwareAccelerated = true, supports1080p60 = true)
        assertFalse(report(decoders = listOf(software)).check(H264_HW_1080P60).passed)
        assertTrue(report(decoders = listOf(software, hardware)).check(H264_HW_1080P60).passed)
    }

    @Test
    fun `thermal check passes only when headroom is reported`() {
        assertFalse(report(thermal = null).check(THERMAL_API).passed)
        assertTrue(report(thermal = 0.4).check(THERMAL_API).passed)
    }
}
