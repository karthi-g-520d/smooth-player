package com.example.smoothplayer.engine.impl.decoder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val DECODER_THREAD_NAME = "FI-Decoder"
private const val RELEASE_TIMEOUT_MS = 500L

/**
 * Decodes one video track of [extractor] into a surface with an asynchronous [MediaCodec].
 *
 * Everything that touches the codec runs on the FI-Decoder thread. Output frames are released with
 * render=true, so they go to the surface as fast as the codec and the surface's buffer queue allow.
 * Pacing against a clock comes in a later step. While paused, input and output are both held.
 *
 * [outputSurfaceProvider] runs on the decoder thread, so waiting for the surface does not block the UI.
 * The extractor is used only from the decoder thread until [release] returns.
 */
internal class VideoDecoder(
    private val extractor: MediaExtractor,
    private val trackIndex: Int,
    private val trackFormat: MediaFormat,
    private val outputSurfaceProvider: () -> Surface,
    private val onEndOfStream: () -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val thread = HandlerThread(DECODER_THREAD_NAME).apply { start() }
    private val handler = Handler(thread.looper)
    private val freeInputs = ArrayDeque<Int>()
    private val heldOutputs = ArrayDeque<HeldOutput>()
    private var codec: MediaCodec? = null
    private var paused = false
    private var inputEnded = false

    private class HeldOutput(val index: Int, val flags: Int)

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mediaCodec: MediaCodec, index: Int) {
            freeInputs.addLast(index)
            feedInput()
        }

        override fun onOutputBufferAvailable(mediaCodec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            heldOutputs.addLast(HeldOutput(index, info.flags))
            releaseHeldOutputs()
        }

        override fun onOutputFormatChanged(mediaCodec: MediaCodec, format: MediaFormat) = Unit

        override fun onError(mediaCodec: MediaCodec, e: MediaCodec.CodecException) {
            onFailure(e.diagnosticInfo ?: e.message.orEmpty())
        }
    }

    /** Selects the track, configures the codec and starts it. Failures are reported through [onFailure]. */
    fun start() {
        handler.post {
            try {
                extractor.selectTrack(trackIndex)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val mime = checkNotNull(trackFormat.getString(MediaFormat.KEY_MIME))
                val mediaCodec = MediaCodec.createDecoderByType(mime)
                mediaCodec.setCallback(callback, handler)
                mediaCodec.configure(trackFormat, outputSurfaceProvider(), null, 0)
                mediaCodec.start()
                codec = mediaCodec
            } catch (e: Exception) {
                onFailure(e.message ?: e.toString())
            }
        }
    }

    /** Holds decoding when [value] is true, and resumes it when false. */
    fun setPaused(value: Boolean) {
        handler.post {
            paused = value
            if (!value) {
                feedInput()
                releaseHeldOutputs()
            }
        }
    }

    /** Stops and releases the codec, then the thread. Blocks briefly so the output surface is unused on return. */
    fun release() {
        val done = CountDownLatch(1)
        handler.post {
            try {
                codec?.let { mediaCodec ->
                    runCatching { mediaCodec.stop() }
                    mediaCodec.release()
                }
                codec = null
            } finally {
                done.countDown()
            }
        }
        done.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }

    private fun feedInput() {
        val mediaCodec = codec ?: return
        while (!paused && !inputEnded && freeInputs.isNotEmpty()) {
            val index = freeInputs.removeFirst()
            val buffer = checkNotNull(mediaCodec.getInputBuffer(index))
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                mediaCodec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputEnded = true
            } else {
                mediaCodec.queueInputBuffer(index, 0, size, extractor.sampleTime, extractor.sampleFlags)
                extractor.advance()
            }
        }
    }

    private fun releaseHeldOutputs() {
        val mediaCodec = codec ?: return
        while (!paused && heldOutputs.isNotEmpty()) {
            val output = heldOutputs.removeFirst()
            mediaCodec.releaseOutputBuffer(output.index, true)
            if ((output.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                onEndOfStream()
            }
        }
    }
}
