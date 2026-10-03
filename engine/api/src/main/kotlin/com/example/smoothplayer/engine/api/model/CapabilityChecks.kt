package com.example.smoothplayer.engine.api.model

private const val MIN_COMPUTE_INVOCATIONS = 128
private const val MIN_COMPUTE_SHARED_MEMORY_BYTES = 16 * 1024
private const val EXT_ESSL3_EXTERNAL_IMAGE = "GL_OES_EGL_image_external_essl3"
private const val MIME_AVC = "video/avc"
private const val MIME_HEVC = "video/hevc"
private const val NOT_APPLICABLE = "-"

/** One pass/fail item in the device report. Ids map to labels in the UI layer. */
public enum class CapabilityCheckId {
    GLES_31,
    ESSL3_EXTERNAL_IMAGE,
    COMPUTE_INVOCATIONS,
    COMPUTE_SHARED_MEMORY,
    THERMAL_API,
    H264_HW_1080P60,
    HEVC_HW_1080P60,
}

public data class CapabilityCheck(
    public val id: CapabilityCheckId,
    public val passed: Boolean,
    public val detail: String,
)

/** The pass/fail items from the Phase 1 plan, Step 1 acceptance and capability probe. */
public fun CapabilityReport.checks(): List<CapabilityCheck> {
    val caps = (gl as? GlProbeResult.Available)?.capabilities
    val compute = caps?.compute
    val noContextReason = (gl as? GlProbeResult.Unavailable)?.reason ?: NOT_APPLICABLE
    val hasEssl3 = caps?.hasExtension(EXT_ESSL3_EXTERNAL_IMAGE) == true
    return listOf(
        CapabilityCheck(
            id = CapabilityCheckId.GLES_31,
            passed = caps?.supportsEs31 == true,
            detail = caps?.let { "${it.esMajor}.${it.esMinor}" } ?: noContextReason,
        ),
        CapabilityCheck(
            id = CapabilityCheckId.ESSL3_EXTERNAL_IMAGE,
            passed = hasEssl3,
            detail = if (caps == null) noContextReason else if (hasEssl3) "yes" else "no",
        ),
        CapabilityCheck(
            id = CapabilityCheckId.COMPUTE_INVOCATIONS,
            passed = compute != null && compute.maxInvocations >= MIN_COMPUTE_INVOCATIONS,
            detail = compute?.maxInvocations?.toString() ?: NOT_APPLICABLE,
        ),
        CapabilityCheck(
            id = CapabilityCheckId.COMPUTE_SHARED_MEMORY,
            passed = compute != null && compute.maxSharedMemoryBytes >= MIN_COMPUTE_SHARED_MEMORY_BYTES,
            detail = compute?.maxSharedMemoryBytes?.toString() ?: NOT_APPLICABLE,
        ),
        CapabilityCheck(
            id = CapabilityCheckId.THERMAL_API,
            passed = thermalHeadroom != null,
            detail = thermalHeadroom?.let { "%.2f".format(it) } ?: NOT_APPLICABLE,
        ),
        hardwareDecoderCheck(CapabilityCheckId.H264_HW_1080P60, MIME_AVC),
        hardwareDecoderCheck(CapabilityCheckId.HEVC_HW_1080P60, MIME_HEVC),
    )
}

private fun CapabilityReport.hardwareDecoderCheck(id: CapabilityCheckId, mimeType: String): CapabilityCheck {
    val names = decoders
        .filter { it.mimeType == mimeType && it.hardwareAccelerated == true && it.supports1080p60 }
        .map { it.codecName }
    return CapabilityCheck(
        id = id,
        passed = names.isNotEmpty(),
        detail = names.joinToString().ifEmpty { NOT_APPLICABLE },
    )
}
