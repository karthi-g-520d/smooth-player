package com.example.smoothplayer.engine.impl.telemetry

import android.os.Trace

/** Section names for Perfetto. Android allows at most 127 UTF-16 units per name, so keep them short and fixed. */
internal object TraceNames {
    const val VIDEO_RELEASE = "FI:video:release"
    const val AUDIO_WRITE = "FI:audio:write"
    const val GL_DRAW = "FI:gl:draw"
    const val GL_UPDATE = "FI:gl:update"
    const val GL_COPY = "FI:gl:copy"
    const val GL_LUMA = "FI:gl:luma"
    const val GL_SWAP = "FI:gl:swap"
}

/** Runs [block] inside a trace section. The section is always closed, even when [block] throws. */
internal inline fun <T> trace(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
