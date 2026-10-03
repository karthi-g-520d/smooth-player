package com.example.smoothplayer.engine.api.model

public sealed interface EngineError {
    public data class CodecFailed(val diagnostic: String, val recoverable: Boolean) : EngineError
    public data class UnsupportedFormat(val mime: String) : EngineError
    public data class GlFailed(val glError: Int, val where: String) : EngineError
    public data object ThermalThrottled : EngineError
}
