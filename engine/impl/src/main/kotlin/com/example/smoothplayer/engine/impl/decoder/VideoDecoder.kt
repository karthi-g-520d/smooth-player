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
 * A seek sets [seekTargetUs]. Frames before the target are discarded, and the first frame at or after it
 * is shown at once, even while paused, so a seek always lands on a picture.
 *
 * A seek replaces the codec rather than flushing it. A flushed codec can still deliver callbacks that were
 * queued before the flush, and those name buffers the codec no longer owns. Callbacks are ignored unless
 * they come from the current codec.
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
    private val onFrameShown: (Micros) -> Unit,
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
    private var seekTargetUs: Micros? = null

    private class HeldOutput(val index: Int, val ptsUs: Micros, val size: Int, val flags: Int) {
        val endOfStream: Boolean get() = (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

        /** The codec's empty end marker carries no picture, so it is never paced or shown. */
        val isEmptyEnd: Boolean get() = endOfStream && size == 0
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mediaCodec: MediaCodec, index: Int) {
            if (!isCurrent(mediaCodec)) return
            feeder.onInputAvailable(index)
            feeder.feed(mediaCodec, !canDecode())
        }

        override fun onOutputBufferAvailable(mediaCodec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (!isCurrent(mediaCodec)) return
            heldOutputs.addLast(HeldOutput(index, Micros(info.presentationTimeUs), info.size, info.flags))
            releaseHeldOutputs()
        }

        override fun onOutputFormatChanged(mediaCodec: MediaCodec, format: MediaFormat) = Unit

        override fun onError(mediaCodec: MediaCodec, e: MediaCodec.CodecException) {
            if (!isCurrent(mediaCodec)) return
            onFailure(e.diagnosticInfo)
        }
    }

    /** Selects the track, configures the codec and starts it. Failures are reported through [onFailure]. */
    fun start() {
        handler.post {
            try {
                extractor.selectTrack(trackIndex)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                startCodec()
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
                codec?.let { feeder.feed(it, !canDecode()) }
                releaseHeldOutputs()
            }
        }
    }

    /**
     * Jumps to the sync frame at or before [target] and discards forward to it. The codec is replaced, so
     * frames decoded before the seek cannot reach the screen. Runs on the decoder thread.
     */
    fun seekTo(target: Micros) {
        handler.post {
            seekTargetUs = target
            handler.removeCallbacks(pollHeldOutputs)
            heldOutputs.clear()
            extractor.seekTo(target.value, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            feeder.reset()
            try {
                releaseCodec()
                startCodec()
            } catch (e: Exception) {
                onFailure(e.message ?: e.toString())
            }
        }
    }

    /** Stops and releases the codec, then the thread. Blocks briefly so the output surface is unused on return. */
    fun release() {
        val done = CountDownLatch(1)
        handler.post {
            try {
                handler.removeCallbacks(pollHeldOutputs)
                releaseCodec()
                heldOutputs.clear()
            } finally {
                done.countDown()
            }
        }
        done.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }

    /** Creates, configures and starts a codec for [trackFormat], then makes it the current one. */
    private fun startCodec() {
        val mime = checkNotNull(trackFormat.getString(MediaFormat.KEY_MIME))
        val mediaCodec = MediaCodec.createDecoderByType(mime)
        mediaCodec.setCallback(callback, handler)
        mediaCodec.configure(trackFormat, outputSurfaceProvider(), null, 0)
        mediaCodec.start()
        codec = mediaCodec
    }

    /** Detaches the current codec first, so any callbacks it still posts are recognised as stale. */
    private fun releaseCodec() {
        val old = codec ?: return
        codec = null
        runCatching { old.stop() }
        old.release()
    }

    private fun isCurrent(mediaCodec: MediaCodec): Boolean = mediaCodec === codec

    /** Input and output run while playing, and also while a seek is looking for its landing frame. */
    private fun canDecode(): Boolean = !paused || seekTargetUs != null

    /**
     * Releases held frames in order while [canDecode] holds. A frame before a pending seek target is dropped.
     * The first frame at or after the target is shown at once. Other frames wait for the clock.
     */
    private fun releaseHeldOutputs() {
        handler.removeCallbacks(pollHeldOutputs)
        val mediaCodec = codec ?: return
        while (canDecode() && heldOutputs.isNotEmpty()) {
            val output = heldOutputs.first()
            val target = seekTargetUs
            val decision = when {
                output.isEmptyEnd -> FrameDecision.Render
                target != null && output.ptsUs < target -> FrameDecision.Drop
                target != null -> FrameDecision.Render
                else -> decideFrame(output.ptsUs, clock.nowUs(), clock.isStalled())
            }
            if (decision is FrameDecision.Wait) {
                handler.postDelayed(pollHeldOutputs, maxOf(1L, decision.delayUs / MICROS_PER_MILLI))
                return
            }
            heldOutputs.removeFirst()
            val render = !output.isEmptyEnd && decision == FrameDecision.Render
            if (target != null && (output.ptsUs >= target || output.endOfStream)) seekTargetUs = null
            mediaCodec.releaseOutputBuffer(output.index, render)
            if (render) onFrameShown(output.ptsUs)
            if (output.endOfStream) onEndOfStream()
        }
    }
}
