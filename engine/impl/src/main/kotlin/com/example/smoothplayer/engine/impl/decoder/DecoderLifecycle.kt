package com.example.smoothplayer.engine.impl.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.view.Surface
import com.example.smoothplayer.engine.impl.lifecycle.LiveResources

/**
 * Creates, configures and starts a decoder, and counts it as live. If setup fails, the codec is released
 * before the error propagates, so a failed start never leaves a codec open.
 */
internal fun startDecoder(
    mime: String,
    callback: MediaCodec.Callback,
    handler: Handler,
    format: MediaFormat,
    surface: Surface?,
): MediaCodec {
    val mediaCodec = MediaCodec.createDecoderByType(mime)
    LiveResources.codecOpened()
    try {
        mediaCodec.setCallback(callback, handler)
        mediaCodec.configure(format, surface, null, 0)
        mediaCodec.start()
    } catch (e: Throwable) {
        closeDecoder(mediaCodec)
        throw e
    }
    return mediaCodec
}

/** Stops, releases and uncounts a decoder. A codec that is already stopped is fine to pass in. */
internal fun closeDecoder(mediaCodec: MediaCodec) {
    runCatching { mediaCodec.stop() }
    mediaCodec.release()
    LiveResources.codecClosed()
}
