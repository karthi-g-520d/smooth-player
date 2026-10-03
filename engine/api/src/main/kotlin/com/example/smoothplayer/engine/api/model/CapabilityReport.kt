package com.example.smoothplayer.engine.api.model

/** Result of the GL probe. [Unavailable] means no EGL/GLES 3 context could be created on this device. */
public sealed interface GlProbeResult {
    public data class Available(public val capabilities: GlCapabilities) : GlProbeResult
    public data class Unavailable(public val reason: String) : GlProbeResult
}

public data class GlCapabilities(
    public val version: String,
    public val shadingLanguageVersion: String,
    /** GL_RENDERER: the GPU name, e.g. "Mali-G68" or "Adreno (TM) 642". */
    public val renderer: String,
    public val esMajor: Int,
    public val esMinor: Int,
    public val extensions: List<String>,
    /** Null below GLES 3.1, where compute limits are not defined. */
    public val compute: ComputeLimits?,
) {
    /** Compute shaders need GLES 3.1, so this is the Phase 1 minimum. */
    public val supportsEs31: Boolean
        get() = esMajor > 3 || (esMajor == 3 && esMinor >= 1)

    public fun hasExtension(name: String): Boolean = name in extensions
}

public data class ComputeLimits(
    public val maxInvocations: Int,
    public val maxWorkGroupSize: List<Int>,
    public val maxSharedMemoryBytes: Int,
)

public data class DisplayModeInfo(
    public val modeId: Int,
    public val width: Int,
    public val height: Int,
    public val refreshRateHz: Float,
)

public data class DecoderInfo(
    public val codecName: String,
    public val mimeType: String,
    /** Null below API 29, where the platform does not report it. */
    public val hardwareAccelerated: Boolean?,
    public val supports1080p60: Boolean,
)

public data class DeviceInfo(
    public val manufacturer: String,
    public val model: String,
    public val sdkInt: Int,
)

public data class CapabilityReport(
    public val generatedAtMillis: Long,
    public val device: DeviceInfo,
    public val gl: GlProbeResult,
    public val displayModes: List<DisplayModeInfo>,
    public val activeDisplayModeId: Int?,
    public val decoders: List<DecoderInfo>,
    /** Result of getThermalHeadroom(0). Null when the platform is below API 30 or reports NaN. */
    public val thermalHeadroom: Double?,
)
