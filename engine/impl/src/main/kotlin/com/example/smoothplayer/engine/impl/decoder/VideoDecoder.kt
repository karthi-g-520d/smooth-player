package com.example.smoothplayer.engine.impl.decoder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.impl.clock.MasterClock
import com.example.smoothplayer.engine.impl.pacer.FrameDecision
import com.example.smoothplayer.engine.impl.pacer.decideFrame
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val DECODER_THREAD_NAME = "FI-Decoder"
private const val RELEASE_TIMEOUT_MS = 500L
private const val MICROS_PER_MILLI = 1_000L

/**
 * Decodes one video track of [extractor] into a surface with an asynchronous [MediaCodec].
 *
 * Everything that touches the codec runs on the FI-Decoder thread. Decoded frames wait in [heldOutputs]
 * until [clock] says they are due. A frame is then shown, dropped when it is more than 40 ms late, or
 * held and checked again later (see [decideFrame]). While paused, input and output are both held.
 *
 * [outputSurfaceProvider] runs on the decoder thread, so waiting for the surface does not block the UI.
 * The extractor is used only from the decoder thread until [release] returns.
 */
internal class VideoDecoder(
    private val extractor: MediaExtractor,
    private val trackIndex: Int,
    private val trackFormat: MediaFormat,
    private val clock: MasterClock,
    private val outputSurfaceProvider: () -> Surface,
    private val onEndOfStream: () -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val thread = HandlerThread(DECODER_THREAD_NAME).apply { start() }
    private val handler = Handler(thread.looper)
    private val feeder = SampleFeeder(extractor)
    private val heldOutputs = ArrayDeque<HeldOutput>()
    private val pollHeldOutputs = Runnable { releaseHeldOutputs() }
    private var codec: MediaCodec? = null
    private var paused = false

    private class HeldOutput(val index: Int, val ptsUs: Micros, val size: Int, val flags: Int) {
        val endOfStream: Boolean get() = (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

        /** The codec's empty end marker carries no picture, so it is never paced or shown. */
        val isEmptyEnd: Boolean get() = endOfStream && size == 0
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mediaCodec: MediaCodec, index: Int) {
            feeder.onInputAvailable(index)
            feeder.feed(mediaCodec, paused)
        }

        override fun onOutputBufferAvailable(mediaCodec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            heldOutputs.addLast(HeldOutput(index, Micros(info.presentationTimeUs), info.size, info.flags))
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
                codec?.let { feeder.feed(it, paused) }
                releaseHeldOutputs()
            }
        }
    }

    /** Stops and releases the codec, then the thread. Blocks briefly so the output surface is unused on return. */
    fun release() {
        val done = CountDownLatch(1)
        handler.post {
            try {
                handler.removeCallbacks(pollHeldOutputs)
                codec?.let { mediaCodec ->
                    runCatching { mediaCodec.stop() }
                    mediaCodec.release()
                }
                codec = null
                heldOutputs.clear()
            } finally {
                done.countDown()
            }
        }
        done.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }

    /** Releases every held frame that is due, in order. Stops at the first frame that is still early. */
    private fun releaseHeldOutputs() {
        handler.removeCallbacks(pollHeldOutputs)
        val mediaCodec = codec ?: return
        while (!paused && heldOutputs.isNotEmpty()) {
            val output = heldOutputs.first()
            val decision = if (output.isEmptyEnd) {
                FrameDecision.Render
            } else {
                decideFrame(output.ptsUs, clock.nowUs(), clock.isStalled())
            }
            if (decision is FrameDecision.Wait) {
                handler.postDelayed(pollHeldOutputs, maxOf(1L, decision.delayUs / MICROS_PER_MILLI))
                return
            }
            heldOutputs.removeFirst()
            val render = !output.isEmptyEnd && decision == FrameDecision.Render
            mediaCodec.releaseOutputBuffer(output.index, render)
            if (output.endOfStream) onEndOfStream()
        }
    }
}
