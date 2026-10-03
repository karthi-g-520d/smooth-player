package com.example.smoothplayer.engine.api.model

private const val SCHEMA_VERSION = 1
private const val INDENT = "  "

/**
 * Pretty-printed JSON for the report. Written in pure Kotlin so it runs in JVM tests
 * and does not depend on any Android JSON library.
 */
public fun CapabilityReport.toJson(): String {
    val root = JsonNode.Obj(
        listOf(
            "schemaVersion" to rawNode(SCHEMA_VERSION),
            "generatedAtMillis" to rawNode(generatedAtMillis),
            "device" to device.toNode(),
            "gl" to gl.toNode(),
            "displayModes" to JsonNode.Arr(displayModes.map { it.toNode() }),
            "activeDisplayModeId" to rawNode(activeDisplayModeId),
            "decoders" to JsonNode.Arr(decoders.map { it.toNode() }),
            "thermalHeadroom" to rawNode(thermalHeadroom),
        ),
    )
    return StringBuilder().also { root.render(it, depth = 0) }.toString()
}

private sealed interface JsonNode {
    data class Obj(val fields: List<Pair<String, JsonNode>>) : JsonNode
    data class Arr(val items: List<JsonNode>) : JsonNode
    data class Str(val value: String) : JsonNode
    /** Numbers, booleans and null, written exactly as [literal]. */
    data class Raw(val literal: String) : JsonNode
}

private fun rawNode(value: Any?): JsonNode = JsonNode.Raw(value?.toString() ?: "null")

private fun strNode(value: String): JsonNode = JsonNode.Str(value)

private fun DeviceInfo.toNode(): JsonNode = JsonNode.Obj(
    listOf(
        "manufacturer" to strNode(manufacturer),
        "model" to strNode(model),
        "sdkInt" to rawNode(sdkInt),
    ),
)

private fun GlProbeResult.toNode(): JsonNode = when (this) {
    is GlProbeResult.Unavailable -> JsonNode.Obj(
        listOf(
            "available" to rawNode(false),
            "reason" to strNode(reason),
        ),
    )
    is GlProbeResult.Available -> JsonNode.Obj(
        listOf(
            "available" to rawNode(true),
            "version" to strNode(capabilities.version),
            "shadingLanguageVersion" to strNode(capabilities.shadingLanguageVersion),
            "renderer" to strNode(capabilities.renderer),
            "esMajor" to rawNode(capabilities.esMajor),
            "esMinor" to rawNode(capabilities.esMinor),
            "extensions" to JsonNode.Arr(capabilities.extensions.map(::strNode)),
            "compute" to (capabilities.compute?.toNode() ?: rawNode(null)),
        ),
    )
}

private fun ComputeLimits.toNode(): JsonNode = JsonNode.Obj(
    listOf(
        "maxInvocations" to rawNode(maxInvocations),
        "maxWorkGroupSize" to JsonNode.Arr(maxWorkGroupSize.map(::rawNode)),
        "maxSharedMemoryBytes" to rawNode(maxSharedMemoryBytes),
    ),
)

private fun DisplayModeInfo.toNode(): JsonNode = JsonNode.Obj(
    listOf(
        "modeId" to rawNode(modeId),
        "width" to rawNode(width),
        "height" to rawNode(height),
        "refreshRateHz" to rawNode(refreshRateHz),
    ),
)

private fun DecoderInfo.toNode(): JsonNode = JsonNode.Obj(
    listOf(
        "codecName" to strNode(codecName),
        "mimeType" to strNode(mimeType),
        "hardwareAccelerated" to rawNode(hardwareAccelerated),
        "supports1080p60" to rawNode(supports1080p60),
    ),
)

private fun JsonNode.render(out: StringBuilder, depth: Int) {
    when (this) {
        is JsonNode.Str -> out.appendQuoted(value)
        is JsonNode.Raw -> out.append(literal)
        is JsonNode.Arr -> {
            if (items.isEmpty()) {
                out.append("[]")
                return
            }
            out.append("[\n")
            items.forEachIndexed { index, item ->
                out.append(INDENT.repeat(depth + 1))
                item.render(out, depth + 1)
                out.appendSeparator(index, items.lastIndex)
            }
            out.append(INDENT.repeat(depth)).append(']')
        }
        is JsonNode.Obj -> {
            if (fields.isEmpty()) {
                out.append("{}")
                return
            }
            out.append("{\n")
            fields.forEachIndexed { index, (key, value) ->
                out.append(INDENT.repeat(depth + 1))
                out.appendQuoted(key).append(": ")
                value.render(out, depth + 1)
                out.appendSeparator(index, fields.lastIndex)
            }
            out.append(INDENT.repeat(depth)).append('}')
        }
    }
}

private fun StringBuilder.appendSeparator(index: Int, lastIndex: Int) {
    if (index < lastIndex) append(',')
    append('\n')
}

private fun StringBuilder.appendQuoted(text: String): StringBuilder {
    append('"')
    for (char in text) {
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
        }
    }
    return append('"')
}
